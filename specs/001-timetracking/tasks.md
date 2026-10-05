# Tasks: Registro horario de personal (timetracking)

**Input**: Design documents from `/specs/001-timetracking/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Incluidos y **obligatorios**. El principio V de la constitución exige
tests unitarios con MockK y de integración con Testcontainers por feature, y que
cada invariante de los principios III, IV, VI y VII tenga un test que falle si
se rompe.

**Organization**: Tareas agrupadas por historia de usuario, para que cada una se
pueda implementar, probar y entregar por separado.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Se puede ejecutar en paralelo (ficheros distintos, sin dependencias pendientes)
- **[Story]**: A qué historia pertenece (US1…US5)
- Rutas exactas en cada descripción

## Path Conventions

Monorepo Gradle multi-módulo. Módulo nuevo `timetracking/`, con el layout de
`inventory` (ver [plan.md](plan.md) y `docs/ARCHITECTURE.md`). Prefijo común:

- Main: `timetracking/src/main/kotlin/com/granatum/core/`
- Migraciones: `timetracking/src/main/resources/db/migration/`
- Tests: `timetracking/src/test/kotlin/com/granatum/core/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: crear el módulo y engancharlo al build, sin lógica de negocio.

- [X] T001 Crear el módulo y registrarlo añadiendo `include("timetracking")` en `settings.gradle.kts`
- [X] T002 Crear `timetracking/build.gradle.kts` aplicando `id("java-library")`, `id("granatum.spring-boot-service")` y `kotlin("plugin.jpa")`, con `implementation(projects.common)`, `libs.spring.boot.starter.data.jpa`, `libs.postgresql`, `libs.spring.boot.starter.validation`, `libs.spring.boot.starter.security`, `libs.jackson.datatype`; y en test `kotlin("test")`, `libs.spring.boot.starter.test`, `libs.mockk`, `libs.flyway.core`, `libs.flyway.postgresql`, `libs.spring.boot.flyway`, `platform(libs.testcontainers.bom)`, `libs.testcontainers.junit.jupiter`, `libs.testcontainers.postgresql`. **Sin versiones a mano**: solo referencias al version catalog (Restricciones Técnicas de la constitución)
- [X] T003 [P] Crear `timetracking/src/test/resources/application.yml` con `jwt.secret` y `jwt.expiration-minutes` de usar y tirar, igual que `inventory/src/test/resources/application.yml`. Sin él, el contexto de test falla con `Could not resolve placeholder 'jwt.secret'` al escanear `common`
- [X] T004 [P] Crear `timetracking/src/test/kotlin/com/granatum/core/TimetrackingTestApplication.kt` con `@SpringBootApplication`, equivalente a `InventoryTestApplication`
- [X] T005 Añadir `implementation(projects.timetracking)` en `app/build.gradle.kts`
- [X] T006 Verificar que `./gradlew build` sigue en verde con el módulo vacío enganchado, antes de añadir una sola entidad

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: esquema, tipos de dominio e infraestructura transversal. **Bloquea
todas las historias.**

### Tipos de dominio

- [X] T007 [P] Crear `domain/type/EstadoFichaje.kt` con exactamente `EN_CURSO`, `CERRADO`, `INCOMPLETO`
- [X] T008 [P] Crear `domain/type/TipoPausa.kt` con exactamente `COMIDA`, `DESCANSO`, `OTRO`
- [X] T009 [P] Crear `domain/type/TipoContrato.kt` con exactamente `JORNADA_COMPLETA`, `PARCIAL`, `POR_HORAS`
- [X] T010 [P] Crear `domain/type/EstadoSolicitud.kt` con exactamente `PENDIENTE`, `APROBADA`, `RECHAZADA`
- [X] T011 [P] Crear `domain/type/TipoOperacionFichaje.kt` con exactamente `ENTRADA`, `INICIO_PAUSA`, `FIN_PAUSA`, `SALIDA`

### Migraciones Flyway

Numeración `V6`–`V10`: `inventory` ocupa `V1`–`V5` y Flyway comparte un único
histórico en `classpath:db/migration` para todos los módulos. **Cada migración
activa RLS sobre su tabla en la misma migración que la crea** (principio VII), y
**ninguna usa `FORCE ROW LEVEL SECURITY`**, que dejaría al backend sin acceso a
sus propios datos.

- [X] T012 Crear `db/migration/V6__create_empleados_table.sql`: `id UUID PK`, `nombre VARCHAR(150) NOT NULL`, `documento_identidad VARCHAR(20) NOT NULL`, `puesto VARCHAR(100) NOT NULL`, `tipo_contrato VARCHAR(20) NOT NULL`, `fecha_alta DATE NOT NULL`, `activo BOOLEAN NOT NULL DEFAULT TRUE`, `created_at`/`updated_at TIMESTAMPTZ NOT NULL`; `CONSTRAINT uk_empleados_documento UNIQUE (documento_identidad)`; `CHECK (tipo_contrato IN ('JORNADA_COMPLETA','PARCIAL','POR_HORAS'))`; más `ENABLE ROW LEVEL SECURITY`
- [X] T013 Crear `db/migration/V7__create_fichajes_table.sql`: `id UUID PK`, `empleado_id UUID NOT NULL REFERENCES empleados(id)`, `entrada TIMESTAMPTZ NOT NULL`, `salida TIMESTAMPTZ`, `estado VARCHAR(15) NOT NULL`, `minutos_trabajados INTEGER`, `fue_incompleto BOOLEAN NOT NULL DEFAULT FALSE`, las seis columnas de ubicación (`ubicacion_entrada_latitud`/`_longitud NUMERIC(9,6)`, `_precision_metros INTEGER`, e iguales con prefijo `ubicacion_salida_`), `created_at`/`updated_at TIMESTAMPTZ NOT NULL`; `CHECK (estado IN ('EN_CURSO','CERRADO','INCOMPLETO'))`, `CHECK (salida IS NULL OR salida > entrada)`, `CHECK (minutos_trabajados IS NULL OR minutos_trabajados >= 0)`; índice `(empleado_id, entrada DESC)`; más `ENABLE ROW LEVEL SECURITY`
- [X] T014 En `V7`, añadir el **índice único parcial** `CREATE UNIQUE INDEX uk_fichajes_empleado_en_curso ON fichajes (empleado_id) WHERE estado = 'EN_CURSO'` y el índice parcial `ON fichajes (estado) WHERE estado = 'EN_CURSO'` que usará el proceso diario. **JPA no puede expresar índices parciales**: tienen que ir en SQL a mano y son la única garantía real frente a la carrera del reintento móvil (research.md §2)
- [X] T015 Crear `db/migration/V8__create_pausas_table.sql`: `id UUID PK`, `fichaje_id UUID NOT NULL REFERENCES fichajes(id)`, `tipo VARCHAR(15) NOT NULL`, `inicio TIMESTAMPTZ NOT NULL`, `fin TIMESTAMPTZ`; `CHECK (tipo IN ('COMIDA','DESCANSO','OTRO'))`, `CHECK (fin IS NULL OR fin > inicio)`; índice `(fichaje_id)`; **índice único parcial** `uk_pausas_fichaje_abierta ON pausas (fichaje_id) WHERE fin IS NULL`; más `ENABLE ROW LEVEL SECURITY`
- [X] T016 Crear `db/migration/V9__create_solicitudes_correccion_fichaje_table.sql`: `id UUID PK`, `fichaje_id UUID NOT NULL REFERENCES fichajes(id)`, `solicitante_id UUID NOT NULL`, `motivo VARCHAR(500) NOT NULL`, `valores_propuestos JSONB NOT NULL`, `valores_originales JSONB`, `estado VARCHAR(12) NOT NULL`, `resuelta_por_id UUID`, `resuelta_en TIMESTAMPTZ`, `motivo_resolucion VARCHAR(500)`, `created_at TIMESTAMPTZ NOT NULL`; `CHECK (estado IN ('PENDIENTE','APROBADA','RECHAZADA'))`; índice `(fichaje_id, estado)`; más `ENABLE ROW LEVEL SECURITY`
- [X] T017 Crear `db/migration/V10__create_fichaje_eventos_table.sql`: `id UUID PK`, `client_event_id UUID NOT NULL`, `empleado_id UUID NOT NULL`, `fichaje_id UUID`, `tipo_operacion VARCHAR(15) NOT NULL`, `occurred_at TIMESTAMPTZ NOT NULL`, `received_at TIMESTAMPTZ NOT NULL`, `huella_peticion VARCHAR(64) NOT NULL`, `estado_respuesta INTEGER NOT NULL`, `cuerpo_respuesta JSONB NOT NULL`; `CONSTRAINT uk_fichaje_eventos_client_event_id UNIQUE (client_event_id)`; **sin `updated_at` y sin claves ajenas a `empleados` ni `fichajes`**, para que nada pueda cascadear un borrado hacia la tabla inmutable (data-model.md); más `ENABLE ROW LEVEL SECURITY`
- [X] T018 Crear `timetracking/src/test/kotlin/com/granatum/core/RowLevelSecurityIT.kt`, replicando el de `inventory`: afirma que ninguna tabla de `public` se queda sin RLS (excluyendo `flyway_schema_history`, que no se puede alterar desde una migración) y que un rol que no es propietario ve 0 filas mientras el propietario sí ve las suyas. **El de `inventory` NO sirve para este módulo**: vive en `inventory`, que solo depende de `common`, así que su contenedor Testcontainers recibe únicamente las migraciones V1–V5 por classpath y las tablas V6–V10 ni existirían en esa base de datos. Sin esta tarea, las cinco tablas nuevas —con DNI, ubicación y jornada— podrían llegar a producción sin RLS creyendo que un test lo impedía

### Entidades JPA

Todas: `class` normal **nunca `data class`**, `id: UUID = UUID.randomUUID()`,
`equals` por identificador, `hashCode` constante por clase, y `toString` que
**no referencia colecciones perezosas ni datos personales** (data-model.md,
principio VI).

- [X] T019 [P] Crear `infrastructure/database/entities/UbicacionEmbeddable.kt` como `@Embeddable` con `latitud`/`longitud: BigDecimal` y `precisionMetros: Int?`
- [X] T020 [P] Crear `infrastructure/database/entities/EmpleadoEntity.kt`. `documentoIdentidad` **fuera de `toString`**: es dato personal
- [X] T021 Crear `infrastructure/database/entities/FichajeEntity.kt` con `@ManyToOne(fetch = FetchType.LAZY)` a empleado, `@OneToMany(mappedBy = "fichaje", fetch = LAZY, cascade = [ALL], orphanRemoval = true)` a pausas, y las dos ubicaciones embebidas con `@AttributeOverrides` y prefijos `ubicacion_entrada_` / `ubicacion_salida_`
- [X] T022 [P] Crear `infrastructure/database/entities/PausaEntity.kt` con `@ManyToOne(LAZY)` a fichaje
- [X] T023 [P] Crear `infrastructure/database/entities/SolicitudCorreccionFichajeEntity.kt`, con los dos JSONB como `String` mapeados con `@JdbcTypeCode(SqlTypes.JSON)`
- [X] T024 [P] Crear `infrastructure/database/entities/FichajeEventoEntity.kt`. **Todos los campos `val`, sin setters**: la inmutabilidad empieza en el tipo, no solo en la convención
- [X] T025 Añadir en `infrastructure/database/entities/FichajeEntity.kt` los métodos de dominio que mantienen **los dos lados** de la relación bidireccional (`añadirPausa` asigna `pausa.fichaje = this` y añade a la lista; `eliminarPausa` hace lo inverso). Un grafo medio actualizado produce fallos sutiles al sincronizar

### Repositorios

- [X] T026 [P] Crear `infrastructure/database/repositories/EmpleadoRepository.kt`. **No extiende `JpaRepository`**: extiende `Repository<EmpleadoEntity, UUID>` y declara solo `save`, `findById`, `findAll`, `findByDocumentoIdentidad` y `existsByDocumentoIdentidad`. FR-030 prohíbe borrar empleados, y una interfaz que expone `delete` acabará usándose (principio III v1.1.0)
- [X] T027 [P] Crear `infrastructure/database/repositories/FichajeRepository.kt` extendiendo `Repository<...>`, sin operaciones de borrado, con `save`, `findById`, `findByEmpleadoIdAndEstado`, y `findAllByEstadoAndEntradaBefore` para el proceso diario
- [X] T028 [P] Crear `infrastructure/database/repositories/PausaRepository.kt` extendiendo `Repository<...>`, con `save` y `findByFichajeIdAndFinIsNull`
- [X] T029 [P] Crear `infrastructure/database/repositories/SolicitudCorreccionFichajeRepository.kt` extendiendo `Repository<...>`, sin borrado
- [X] T030 [P] Crear `infrastructure/database/repositories/FichajeEventoRepository.kt` extendiendo `Repository<...>` y declarando **únicamente** `save` y `findByClientEventId`. Es la tabla append-only: ni `delete`, ni `deleteAll`, ni `saveAll`

### Infraestructura transversal

- [X] T031 [P] Crear `domain/exception/` con las excepciones de negocio, una por código de error de `contracts/README.md`: `FichajeNotFoundException`, `EmpleadoNotFoundException`, `SolicitudNotFoundException`, `FichajeYaEnCursoException`, `PausaYaAbiertaException`, `PausaNoAbiertaException`, `PausaAbiertaAlCerrarException`, `FichajeNoEnCursoException`, `FichajeNoFinalizadoException`, `FichajeInmutableException`, `SolicitudYaResueltaException`, `EmpleadoInactivoException`, `DocumentoDuplicadoException`, `ValoresIncoherentesException`, `UbicacionNoCorregibleException`, `DesviacionRelojException`, `ClientEventIdReutilizadoException`
- [X] T032 Crear `api/exception_handling/TimetrackingExceptionHandler.kt` como `@RestControllerAdvice`, mapeando cada excepción a su `code` y HTTP de `contracts/README.md`. Formato único `{ "code", "message" }` (principio VIII). **Ningún mensaje incluye documento de identidad ni ubicación** (principio VI): los errores de validación referencian el campo, no su valor
- [X] T033 Añadir en `app/.../api/security/SecurityConfig.kt` las reglas: `/api/fichajes/**` y `/api/correcciones/**` autenticado con cualquiera de los tres roles (la propiedad del recurso se comprueba en el servicio), y `/api/empleados/**` solo `ADMIN`
- [X] T034 Añadir `@EnableScheduling` en `app/.../GranatumSuiteApplication.kt`. Verificado que `TaskSchedulingAutoConfiguration` sigue en `spring-boot-autoconfigure` de Boot 4 y registrada en su fichero de imports, así que no hace falta ninguna dependencia nueva (research.md §1)
- [X] T035 Crear `timetracking/src/main/kotlin/com/granatum/core/api/util/RangoFechas.kt`: helper que convierte `desde`/`hasta` (fechas civiles) al rango de `Instant` correspondiente **interpretándolas en `Europe/Madrid`** con `atStartOfDay()`. El día del cambio de hora tiene 23 o 25 horas, así que un desplazamiento fijo da un rango equivocado (research.md §6). **Va en este módulo y no en `common`**: hoy solo lo usa `timetracking`, y subirlo a `common` lo convertiría en superficie compartida con `inventory` sin ningún consumidor que lo justifique (principio I). Si la feature 003 lo necesita, se sube entonces
- [X] T036 Crear `service/RegistradorEventos.kt`: escribe una fila en `fichaje_eventos` por cada operación recibida, con `occurredAt`, `receivedAt`, huella SHA-256 del cuerpo canonicalizado, y estado y cuerpo de la respuesta. **Lo exige el principio III v1.1.0 para toda operación de fichaje**, no solo para la idempotencia de US4

**Checkpoint**: `./gradlew build` en verde, las cinco tablas creadas con RLS, y
`RowLevelSecurityIT` pasando. Las historias pueden empezar.

---

## Phase 3: User Story 1 - Registrar una jornada completa (Priority: P1) 🎯 MVP

**Goal**: una persona ficha entrada, dos pausas y salida, y las horas salen
correctas.

**Independent Test**: jornada de 07:00 a 16:00 con pausas que suman 60 minutos
→ `minutosTrabajados: 480`. Verificable con una sola persona empleada y sin
ninguna otra historia implementada.

### Tests for User Story 1

- [X] T037 [P] [US1] Crear `CalculadoraJornadaTest.kt` (MockK, sin Spring ni base de datos): 480 minutos con dos pausas de 60; jornada sin pausas; **jornada que cruza la medianoche** (22:00 → 06:00 = 480); **jornada que atraviesa el cambio a horario de invierno** (25-10-2026 00:00 → 08:00 Madrid = **540**, porque esa madrugada tiene 25 horas); y que pausas que suman más que la jornada produzcan `ValoresIncoherentesException` en lugar de minutos negativos
- [ ] T038 [P] [US1] Crear `FichajeServiceTest.kt` (MockK): entrada rechazada si ya hay un fichaje `EN_CURSO` (FR-002); entrada rechazada si el empleado está inactivo (FR-010); segunda pausa rechazada con una abierta (FR-004); salida rechazada con una pausa abierta (FR-006); salida anterior a la entrada rechazada (FR-011)
- [X] T039 [US1] Crear `FichajeLifecycleIT.kt` (Testcontainers, Postgres real): la jornada completa de punta a punta con migraciones reales, comprobando los minutos y que el estado acaba en `CERRADO`
- [X] T040 [US1] Añadir a `FichajeLifecycleIT.kt` la prueba de que el **índice único parcial** impide dos fichajes `EN_CURSO` del mismo empleado bajo concurrencia: dos inserciones simultáneas, una debe fallar con violación de restricción. Es la prueba de que la garantía está en el motor y no solo en el servicio

### Implementation for User Story 1

- [X] T041 [P] [US1] Crear `domain/model/FichajeModel.kt` y `domain/model/PausaModel.kt`, independientes de JPA y de HTTP
- [X] T042 [US1] Crear `domain/service/CalculadoraJornada.kt` como **función pura** sin dependencias de Spring: recibe entrada, salida y pausas, devuelve minutos. Aislada así para poder testearla sin base de datos ni contexto
- [X] T043 [P] [US1] Crear `infrastructure/database/mappers/FichajeMappers.kt` y `PausaMappers.kt` (Entity → Model)
- [X] T044 [P] [US1] Crear los DTO en `api/dto/`: `EntradaRequest`, `InicioPausaRequest`, `FinPausaRequest`, `SalidaRequest` (todos con `clientEventId: UUID` y `occurredAt: Instant` obligatorios y `@field:NotNull`), `UbicacionDto` (opcional, `latitud`/`longitud` obligatorias si se aporta), `FichajeDto` y `PausaDto`
- [X] T045 [P] [US1] Crear `api/mappers/FichajeDtoMappers.kt` (Model → Dto). `FichajeDto` **no incluye** el documento de identidad ni campos de auditoría internos
- [X] T046 [US1] Crear `service/FichajeService.kt` con `registrarEntrada`, `iniciarPausa`, `finalizarPausa` y `registrarSalida`. La identidad del empleado sale **del sujeto del JWT** vía `requestUserId`, nunca del cuerpo. Cada operación escribe su evento con `RegistradorEventos` (T036). El mapeo a DTO ocurre **dentro de la transacción**: `open-in-view` está desactivado y tocar una colección perezosa después rompería con `LazyInitializationException`, que ya pasó una vez en este proyecto con `MaterialEntity.fotos`
- [X] T047 [US1] Crear `api/controllers/FichajeController.kt` con `POST /api/fichajes/entrada`, `POST /api/fichajes/{id}/pausa/inicio`, `POST /api/fichajes/{id}/pausa/fin` y `POST /api/fichajes/{id}/salida`, según `contracts/fichajes.md`. Sin lógica de negocio: valida con `@Valid` y delega
- [X] T048 [US1] Crear `scheduling/MarcadoFichajesIncompletosJob.kt` con `@Scheduled(cron = ...)` configurable, que marca `INCOMPLETO` (y `fueIncompleto = true`) todo fichaje `EN_CURSO` cuya entrada sea de un día anterior **en `Europe/Madrid`** (FR-012). La operación es idempotente, así que es segura si la app corre en varias instancias
- [X] T049 [US1] En `service/FichajeService.kt`, hacer que `registrarEntrada` **no** cuente los fichajes `INCOMPLETO` como jornada en curso (FR-012a): un `INCOMPLETO` arrastrado no debe bloquear a la persona para fichar hoy
- [X] T050 [US1] Crear `MarcadoFichajesIncompletosIT.kt` (Testcontainers): un fichaje abierto de ayer pasa a `INCOMPLETO` con `fueIncompleto = true`; uno de hoy **no se toca**; y después la persona puede fichar entrada con normalidad (SC-009)

**Checkpoint**: US1 entregable. Una jornada se registra y se calcula bien.

> T048–T050 (el proceso diario) pueden diferirse una iteración sin romper el
> test independiente de US1, pero **tienen que entrar antes de producción**: sin
> ellas SC-009 no se cumple y un olvido de salida bloquea a la persona al día
> siguiente.

---

## Phase 4: User Story 2 - Corregir un fichaje sin destruir el original (Priority: P2)

**Goal**: un fichaje finalizado solo cambia por una corrección aprobada, y el
original sigue recuperable con autoría e instante.

**Independent Test**: sobre un fichaje cerrado, solicitar corrección, aprobarla,
y comprobar que el valor vigente cambió, el original sigue accesible, y constan
quién aprobó y cuándo.

### Tests for User Story 2

- [X] T051 [P] [US2] Crear `CorreccionServiceTest.kt` (MockK): corrección sobre fichaje `EN_CURSO` rechazada (FR-013); propuesta con `ubicacion` rechazada con `UbicacionNoCorregibleException` (FR-020a); propuesta incoherente —salida antes de entrada, pausas solapadas, pausas fuera del intervalo, minutos negativos— rechazada (FR-020b); resolver dos veces rechazado (FR-019); `EMPLEADO` que aprueba su propia solicitud rechazado (FR-015)
- [X] T052 [US2] Crear `FichajeInmutableIT.kt` (Testcontainers) — **el test que exige el principio III**. Sobre un fichaje `CERRADO`, intentar todas las vías de escritura disponibles y comprobar que entrada, salida, pausas y minutos **siguen byte a byte idénticos**. Es el test que `/speckit-analyze` debe encontrar (SC-002)
- [X] T053 [US2] Crear `DerivabilidadEstadoIT.kt` (Testcontainers) — **invariante del principio III v1.1.0**. Reconstruir el estado de un fichaje a partir del log de `fichaje_eventos` más sus correcciones aprobadas, y comprobar que coincide con la proyección almacenada. Si un valor de la proyección no se explica por un evento o una corrección, se escribió por una vía que no debería existir
- [X] T054 [US2] Crear `CorreccionFlujoIT.kt` (Testcontainers): completar un fichaje `INCOMPLETO` con una corrección aprobada lo deja `CERRADO` **conservando `fueIncompleto = true`** (FR-012c, SC-010), y añadir una pausa por corrección recalcula los minutos

### Implementation for User Story 2

- [X] T055 [P] [US2] Crear `domain/model/SolicitudCorreccionModel.kt`
- [X] T056 [P] [US2] Crear en `api/dto/` los DTO: `CrearCorreccionRequest` (`motivo` obligatorio **10–500 caracteres**, `valoresPropuestos` obligatorio), `RechazarCorreccionRequest` (`motivoResolucion` **obligatorio**), `ValoresFichajeDto` (`entrada`, `salida`, `pausas` — **sin `ubicacion`**, y su presencia se rechaza) y `CorreccionDto`
- [X] T057 [P] [US2] Crear `infrastructure/database/mappers/SolicitudCorreccionMappers.kt` y `api/mappers/CorreccionDtoMappers.kt`
- [X] T058 [US2] Crear `domain/service/ValidadorValoresFichaje.kt`: función pura que aplica a unos valores propuestos **las mismas reglas de coherencia que a un fichaje normal** — salida posterior a la entrada, pausas sin solapamiento, pausas contenidas en la jornada, minutos no negativos (FR-020b). Reutilizada al solicitar y al aprobar
- [X] T059 [US2] Crear `service/CorreccionService.kt` con `solicitar`, `aprobar` y `rechazar`. Al aprobar, **en la misma transacción**: guardar `valoresOriginales` con el estado previo (FR-017), aplicar la propuesta, recalcular minutos, pasar a `CERRADO` si venía de `INCOMPLETO` dejando `fueIncompleto` intacto, y marcar la solicitud con autoría e instante (FR-016)
- [ ] T060 [US2] En `service/CorreccionService.kt`, implementar la resolución como **actualización condicionada a `estado = 'PENDIENTE'`**, de modo que dos aprobaciones simultáneas no se apliquen las dos (FR-019). Comprobar el número de filas afectadas y lanzar `SolicitudYaResueltaException` si es cero
- [X] T061 [US2] Revalidar la coherencia **al aprobar**, no solo al solicitar: el fichaje puede haber cambiado por otra corrección entremedias (`contracts/correcciones.md`)
- [X] T062 [US2] Crear `api/controllers/CorreccionController.kt` con `POST /api/fichajes/{id}/correcciones`, `POST /api/correcciones/{id}/aprobar` y `POST /api/correcciones/{id}/rechazar`
- [X] T063 [US2] **Hueco del encargo**: añadir `GET /api/fichajes/{id}/correcciones` (histórico de un fichaje) y `GET /api/correcciones?estado=PENDIENTE` (bandeja del responsable). Sin el primero, los valores originales se almacenan pero **no hay forma de leerlos por API** y SC-003 no es verificable desde fuera; la feature 003 también lo necesita para la columna "correcciones aplicadas"
- [X] T064 [US2] Verificar que **no existe** ningún `PUT` ni `PATCH` sobre `/api/fichajes/{id}`, y dejarlo anotado en el controlador: sería la forma de incumplir el principio III sin darse cuenta

**Checkpoint**: el registro es legalmente defendible. US1 + US2 son el mínimo
desplegable con datos reales.

---

## Phase 5: User Story 3 - Consultar fichajes respetando la privacidad (Priority: P2)

**Goal**: cada persona ve lo suyo; `ENCARGADO` y `ADMIN` ven todo.

**Independent Test**: con dos empleados y un responsable, comprobar que cada
empleado solo ve lo suyo, que el intento de leer lo ajeno se rechaza, y que el
responsable ve todo.

### Tests for User Story 3

- [ ] T065 [P] [US3] Crear `AutorizacionFichajesTest.kt` (MockK): un `EMPLEADO` que pide los fichajes de otro recibe `ForbiddenException` **aunque indique explícitamente el identificador ajeno** (FR-022); `ENCARGADO` y `ADMIN` acceden a cualquiera
- [ ] T066 [US3] Crear `ConsultaFichajesIT.kt` (Testcontainers): filtrado por rango correcto; **una jornada que cruza la medianoche aparece solo en el día de su entrada**, no en los dos; rango sin fichajes devuelve lista vacía y no error; y rango con `hasta` anterior a `desde` se rechaza
- [ ] T067 [US3] Añadir a `ConsultaFichajesIT.kt` la **comprobación del N+1**: contar las consultas reales al listar N fichajes con pausas y confirmar que no crecen con N. El N+1 se diagnostica contando consultas, no leyendo anotaciones

### Implementation for User Story 3

- [ ] T068 [US3] Añadir a `FichajeRepository` el método de consulta con `@EntityGraph(attributePaths = ["pausas"])` y firma `findByEmpleadoIdAndEntradaBetweenOrderByEntradaDesc(empleadoId, desde, hasta)`. **Sin paginación**: el rango de fechas acota el resultado, y un fetch join de colección no se pagina en base de datos (data-model.md)
- [ ] T069 [US3] Implementar en `service/FichajeService.kt` la consulta por empleado y rango, comparando para rol `EMPLEADO` el `{empleadoId}` de la ruta contra el **sujeto del JWT** y devolviendo `403` si no coinciden. El identificador de la ruta está por legibilidad; **no es fuente de autoridad**
- [ ] T070 [US3] Añadir `GET /api/fichajes/empleado/{empleadoId}?desde=&hasta=` a `FichajeController`, usando el helper de T035 para interpretar las fechas en `Europe/Madrid`

**Checkpoint**: el módulo se puede usar con datos personales reales.

---

## Phase 6: User Story 4 - Fichar sin conexión y reenviar sin duplicar (Priority: P3)

**Goal**: reenviar una operación no la duplica, y se conserva la hora del hecho.

**Independent Test**: enviar la misma operación dos veces con el mismo
`clientEventId` y comprobar que hay un único fichaje y que la segunda respuesta
es equivalente a la primera.

### Tests for User Story 4

- [ ] T071 [P] [US4] Crear `IdempotenciaServiceTest.kt` (MockK): `clientEventId` nuevo se procesa; repetido con la misma huella devuelve la respuesta guardada; repetido con **huella distinta** lanza `ClientEventIdReutilizadoException`
- [ ] T072 [P] [US4] Crear `ToleranciaRelojTest.kt` (MockK): `occurredAt` 10 minutos en el futuro rechazado; 4 minutos aceptado; 48 horas en el pasado aceptado; 5 días en el pasado rechazado. Límites configurables, por defecto **5 minutos futuro / 72 horas pasado**
- [ ] T073 [US4] Crear `ReenvioDuplicadoIT.kt` (Testcontainers) — **el test que el encargo pide explícitamente**. Enviar dos veces la misma petición de entrada y comprobar que **el `id` de fichaje es el mismo** en ambas respuestas y que hay **una sola fila** en `fichajes`. Si la segunda devolviera `409 FICHAJE_YA_EN_CURSO`, la idempotencia no está implementada: estaría tratando el reintento como una entrada nueva
- [ ] T074 [US4] Añadir a `ReenvioDuplicadoIT.kt` que el fichaje consta con `occurredAt` y **no** con `receivedAt` (FR-025), y que ambos quedan guardados por separado en `fichaje_eventos`

### Implementation for User Story 4

- [ ] T075 [US4] Crear `service/IdempotenciaService.kt`: calcula la huella SHA-256 del cuerpo canonicalizado, busca por `clientEventId`, y devuelve la respuesta guardada o cede el paso al procesamiento. **Se guarda la respuesta original y no se recalcula**: devolver el estado actual daría una respuesta distinta si la jornada avanzó entre el envío y el reintento, que es justo el caso que motiva la feature (research.md §5)
- [ ] T076 [US4] Crear `service/ValidadorReloj.kt` con los dos límites como propiedades de configuración (`timetracking.reloj.tolerancia-futuro`, `...-pasado`), por defecto `5m` y `72h`
- [ ] T077 [US4] Integrar ambos en las cuatro operaciones de `service/FichajeService.kt`: validar el reloj **antes** de procesar, y envolver el procesamiento en la comprobación de idempotencia
- [ ] T078 [US4] En `api/exception_handling/TimetrackingExceptionHandler.kt`, mapear `DesviacionRelojException` a `422 DESVIACION_RELOJ`, **distinguible del resto de errores de validación** para que la app móvil pueda explicar a la persona que el reloj de su dispositivo está desajustado (FR-026b)

**Checkpoint**: el modo sin conexión de la app móvil es seguro.

---

## Phase 7: User Story 5 - Dar de alta y de baja personal (Priority: P3)

**Goal**: `ADMIN` gestiona el personal; la baja conserva el histórico.

**Independent Test**: dar de alta, comprobar que puede fichar, marcar inactiva,
comprobar que ya no puede y que su histórico sigue consultable.

### Tests for User Story 5

- [ ] T079 [P] [US5] Crear `DocumentoIdentidadValidatorTest.kt`: DNI y NIE con dígito de control válido aceptados; dígito inválido rechazado; `12345678z` y `12345678-Z` **normalizan al mismo valor**, para que la unicidad de FR-029 no se burle con un guion
- [ ] T080 [P] [US5] Crear `EmpleadoServiceTest.kt` (MockK): documento duplicado rechazado (FR-029); **no existe operación de borrado** (FR-030)
- [ ] T081 [US5] Crear `EmpleadoIT.kt` (Testcontainers): alta, baja lógica, y que **el histórico de fichajes sigue íntegro tras la baja**; `ENCARGADO` y `EMPLEADO` reciben `403` en todo el recurso (FR-027)

### Implementation for User Story 5

- [ ] T082 [P] [US5] Crear `domain/model/EmpleadoModel.kt`
- [ ] T083 [US5] Crear `api/validation/DocumentoIdentidad.kt`: anotación de Jakarta Validation con validador que comprueba formato y dígito de control de DNI y NIE, y normaliza a mayúsculas sin espacios ni guiones
- [ ] T084 [P] [US5] Crear los DTO `CrearEmpleadoRequest`, `ActualizarEmpleadoRequest`, `CambiarActivoRequest` y `EmpleadoDto` con las restricciones de `contracts/empleados.md`: `nombre` **1–150**, `puesto` **1–100**, `tipoContrato` del enum, `fechaAlta` como `LocalDate` obligatoria
- [ ] T085 [P] [US5] Crear `infrastructure/database/mappers/EmpleadoMappers.kt` y `api/mappers/EmpleadoDtoMappers.kt`
- [ ] T086 [US5] Crear `service/EmpleadoService.kt` con `crear`, `actualizar`, `cambiarActivo`, `findById` y `findAll`. **`documentoIdentidad` no es modificable** en `actualizar`, y `activo` solo cambia por su endpoint propio, para que una baja sea una acción explícita y no el efecto colateral de editar datos
- [ ] T087 [US5] Crear `api/controllers/EmpleadoController.kt` con `POST`, `GET`, `GET /{id}`, `PUT /{id}` y `PATCH /{id}/activo`. **Sin `DELETE`**, y anotado el motivo: borrar destruiría el histórico de jornada que debe conservarse 4 años
- [ ] T088 [US5] **Hueco del encargo**: resolver qué pasa al dar de baja a alguien con un fichaje `EN_CURSO`. Implementar la opción coherente con FR-012 —**permitir la baja y dejar que el proceso diario lo marque `INCOMPLETO`**— en lugar de bloquear una gestión administrativa por un olvido de la persona. Documentar la decisión en `contracts/empleados.md` y cubrirla con un test

**Checkpoint**: las cinco historias completas.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T089 [P] Actualizar `README.md`: estado del módulo `timetracking` como implementado, y cómo ejercitarlo (principio IX). Incluir que el login real sigue pendiente y que `POST /api/dev/token` emite el token de pruebas
- [ ] T090 [P] Actualizar `docs/ARCHITECTURE.md`: el módulo en el diagrama, la separación hechos/estado del principio III v1.1.0, y por qué las migraciones comparten numeración
- [ ] T091 **Deuda declarada en la constitución**: estrechar `inventory/.../HistorialMaterialRepository.kt` para que no extienda `JpaRepository` y declare solo `save` y `findAllByMaterialIdOrderByFechaDesc`. Hoy nadie llama a `delete`/`deleteAll` —verificado—, pero la interfaz ofrece la fuga sobre una tabla append-only. Es **prerrequisito de dar `timetracking` por terminado**, para no replicar el patrón
- [ ] T092 Crear `timetracking/src/test/kotlin/com/granatum/core/SinDatosPersonalesEnLogsIT.kt`: engancha un appender de captura al logger raíz, ejercita el alta de empleado y una jornada completa con un DNI y una ubicación **conocidos y distintivos**, y afirma que ninguno de los dos aparece en la salida de log, ni en el `toString` de las entidades, ni en el cuerpo de las respuestas de error. **Tiene que ser un test y no una revisión manual**: el principio V exige que los invariantes de los principios III, IV, VI y VII tengan un test que falle si se rompen, porque "una regla sin test es una intención, no una garantía"
- [ ] T093 Ejecutar `./gradlew clean build` y confirmar que **todos** los tests pasan, con las 10 migraciones aplicadas y `RowLevelSecurityIT` en verde
- [ ] T094 Recorrer los 8 escenarios de [quickstart.md](quickstart.md) contra la app arrancada y confirmar cada resultado esperado, en especial el escenario 7 (**540 minutos** en el cambio de hora, no 480)
- [ ] T095 Crear `app/src/test/kotlin/com/granatum/core/EsquemaCompletoRlsIT.kt`: el **único** sitio donde el esquema está completo, porque `app` depende de todos los módulos y por tanto ve las 10 migraciones. Afirma que ninguna tabla de `public` carece de RLS, excluyendo solo `flyway_schema_history`. Los tests por módulo (T018 y el de `inventory`) nunca pueden ser totales: cada uno solo ve las migraciones de su propio classpath, así que un módulo futuro que olvide RLS no lo detectaría ninguno
- [ ] T096 [P] Crear `timetracking/src/test/kotlin/com/granatum/core/SinBorradoIT.kt`: afirma por reflexión que **ninguno** de los cinco repositorios del módulo expone un método cuyo nombre empiece por `delete` o `remove`, y que `FichajeEventoRepository` no expone tampoco `saveAll`. Cubre FR-031 para fichajes, pausas, solicitudes y eventos; hoy solo `Empleado` tiene esa comprobación (T080). Un test por reflexión no se queda obsoleto cuando alguien añada un repositorio nuevo
- [ ] T097 Medir SC-007 y SC-008 con volumen representativo (plantilla de 50 personas × 1 fichaje diario × 1 mes ≈ 1.100 fichajes con pausas) y registrar los tiempos en `specs/001-timetracking/quickstart.md`. Si alguno no se cumple, **no** relajar el criterio: corregir la consulta. Hasta que exista esta tarea, SC-007 y SC-008 son objetivos declarados pero no verificados
- [X] T098 Añadir `REPRESENTANTE` a `common/src/main/kotlin/com/granatum/core/domain/type/Role.kt` y actualizar su KDoc. Lo exige el principio IV desde v2.0.0, para dar soporte a la puesta a disposición del registro a la representación legal (art. 34.9)
- [ ] T099 [US3] Implementar en `service/FichajeService.kt` el alcance de `REPRESENTANTE`: lectura de la jornada de cualquier persona, y **omisión de los campos de ubicación** en la respuesta (FR-023a, FR-023b). Omitir el campo, **no** devolverlo a `null`: `null` sería indistinguible de un fichaje sin ubicación registrada
- [ ] T100 [US3] Crear `timetracking/src/test/kotlin/com/granatum/core/RepresentanteSoloLecturaIT.kt`: un `REPRESENTANTE` lee la jornada de cualquier persona y **ninguna respuesta contiene ubicación**; y el 100% de sus intentos de escritura —fichar, solicitar corrección, aprobar, rechazar, gestionar personal— devuelven `403` (FR-023c, SC-011)
- [ ] T101 [US3] Añadir a `app/.../api/security/SecurityConfig.kt` que `REPRESENTANTE` no alcanza `/api/empleados/**` ni las rutas de inventario, y que en `/api/fichajes/**` y `/api/correcciones/**` solo se le permiten métodos de lectura
- [ ] T102 [P] Crear `domain/service/CalculadoraResumenMensual.kt`: función pura que agrega los fichajes de una persona y un mes natural en el resumen de FR-032, con total del mes, y marcando por día `reconstruido` (desde `fueIncompleto`) y `corregido` (si hubo corrección aprobada). **Solo el cálculo**: la descarga y el formato de fichero son de la feature de exportación
- [ ] T103 Añadir `GET /api/fichajes/empleado/{empleadoId}/resumen?anio=&mes=` a `api/controllers/FichajeController.kt` según `contracts/fichajes.md`, con las mismas reglas de visibilidad que el listado, incluida la omisión de ubicación para `REPRESENTANTE`
- [ ] T104 [P] Crear `timetracking/src/test/kotlin/com/granatum/core/ResumenMensualTest.kt` (MockK): el total del mes cuadra con la suma de las jornadas; una jornada reconstruida se marca `reconstruido`; una corregida se marca `corregido` y refleja los valores **vigentes**, no los originales (FR-034, SC-014)
- [X] T105 Crear `db/migration/V11__create_depuraciones_retencion_table.sql`: `id UUID PK`, `ejecutada_en TIMESTAMPTZ NOT NULL`, `fecha_corte DATE NOT NULL`, y los cuatro recuentos `INTEGER NOT NULL`; **sin ningún dato personal** (un registro de depuración que conservase identificadores sobreviviría al plazo que la depuración viene a cumplir); más `ENABLE ROW LEVEL SECURITY`
- [ ] T106 Crear `scheduling/DepuracionRetencionJob.kt`: proceso automático que elimina fichajes, pausas, eventos y solicitudes cuya fecha de entrada tenga **más de 4 años cumplidos por completo**, y anota el recuento en `depuraciones_retencion` (FR-031b, FR-031c). **Deshabilitado por defecto** mediante una propiedad de configuración, y con un arranque que falla si se habilita sin que la descarga mensual esté disponible (FR-031d)
- [ ] T107 Crear `timetracking/src/test/kotlin/com/granatum/core/RetencionIT.kt`: un registro con el plazo **vencido por completo** se elimina y queda anotado con su recuento; uno al que le falta **un solo día** permanece intacto; y la ejecución deja constancia recuperable (SC-013)
- [ ] T108 Crear `timetracking/src/test/kotlin/com/granatum/core/SinBorradoDentroDelPlazoIT.kt`: afirma que **ninguna vía** —ningún endpoint, ningún rol incluido `ADMIN`, ninguna operación de repositorio— borra un registro cuyo plazo de conservación sigue vigente (FR-031a, SC-012). Es el test que el principio III exige explícitamente desde v2.0.0

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup)
   └─► Phase 2 (Foundational)  ◄── BLOQUEA TODO
          ├─► Phase 3 (US1, P1) 🎯 MVP
          │      └─► Phase 4 (US2, P2)   necesita fichajes cerrados que corregir
          ├─► Phase 5 (US3, P2)          solo necesita Foundational
          ├─► Phase 6 (US4, P3)          necesita las operaciones de US1
          └─► Phase 7 (US5, P3)          solo necesita Foundational
                 └─► Phase 8 (Polish)
```

### User Story Dependencies

- **US1** solo depende de Foundational. Es el MVP.
- **US2** depende de US1: no hay nada que corregir sin fichajes cerrados.
- **US3** es independiente de US1 y US2 en código, aunque sin datos su test es
  vacío. Se puede desarrollar en paralelo a US1.
- **US4** depende de US1: envuelve sus cuatro operaciones.
- **US5** es independiente del resto. En rigor su alta es **prerrequisito
  funcional** de US1 (hace falta un empleado para fichar), pero para el
  desarrollo basta insertar una fila de prueba, así que no bloquea.

### Within Each User Story

Tests → modelos de dominio → mappers → DTO → servicio → controlador.

### Parallel Opportunities

- **Phase 2**: T007–T011 (enums) y T019–T030 (entidades y repositorios, una vez
  hechas sus migraciones) son todas `[P]`.
- **US1 y US5** pueden ir en paralelo desde el final de Foundational, por dos
  personas distintas, sin tocar los mismos ficheros.
- **US3** puede ir en paralelo a US1 salvo el método del repositorio (T068) y el
  controlador (T070), que comparten fichero con US1.

---

## Parallel Example: User Story 1

```bash
# Los tests de dominio son independientes entre sí: ficheros distintos, sin
# dependencias pendientes
T037  CalculadoraJornadaTest.kt
T038  FichajeServiceTest.kt

# Los modelos, mappers y DTO también, una vez existen las entidades
T041  FichajeModel.kt / PausaModel.kt
T043  FichajeMappers.kt / PausaMappers.kt
T044  los DTO de api/dto/
T045  FichajeDtoMappers.kt
```

`T046` (servicio) y `T047` (controlador) **no** son paralelizables entre sí ni
con los anteriores: el servicio depende de todo lo de arriba y el controlador
del servicio.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

Phases 1 → 2 → 3. Al terminar hay un registro horario funcional: una persona
ficha su jornada y las horas salen bien. Es ya un registro válido, aunque sin
correcciones ni modo sin conexión.

### Incremental Delivery

1. **US1** — se registra la jornada. 🎯
2. **US2** — el registro es legalmente defendible (correcciones con aprobación y
   original conservado). **US1 + US2 es el mínimo desplegable con datos
   reales**: sin US2, un error de fichaje no tiene arreglo legítimo.
3. **US3** — utilizable con datos personales sin riesgo de filtración entre
   empleados.
4. **US4** — la app móvil puede operar sin conexión.
5. **US5** — gestión de personal por la API en lugar de a mano en la base de
   datos.

### Parallel Team Strategy

Con dos personas, tras Foundational: una toma US1 → US2 → US4 (el camino
crítico, todo sobre `FichajeService`), la otra US5 → US3. Se evitan conflictos
porque el único fichero compartido es `FichajeController` (T047, T070), que debe
tocar una sola de las dos.

---

## Notes

- **108 tareas**, de las cuales 26 son de test y son obligatorias.
- T098–T108 responden a tres decisiones de producto posteriores al análisis:
  el cuarto rol `REPRESENTANTE`, el resumen mensual y la depuración a los 4
  años. Las dos últimas exigieron enmendar la constitución a v2.0.0, porque
  chocaban con "tres roles" y con "ninguna fila se borra nunca".
- Los invariantes de los principios III, IV, VI y VII tienen **test
  automatizado**, no revisión manual: T052 (inmutabilidad), T053
  (derivabilidad), T065 (autorización), T092 (datos personales en logs), T096
  (ausencia de borrado) y T018 + T095 (RLS).
- T095, T096 y T097 se añadieron tras `/speckit-analyze`, que encontró dos
  garantías que el plan afirmaba tener y no tenía: el RLS de las tablas nuevas
  no lo verificaba nadie, y el invariante del principio VI tenía una revisión
  manual donde la constitución exige un test.
- **Nada se fusiona con la CI en rojo** (principio V). Un test que falla no se
  desactiva: se arregla o se revierte el cambio.
- Dos huecos del encargo se resuelven explícitamente, en T063 (consulta de
  correcciones) y T088 (baja con fichaje abierto), en lugar de dejarse a
  criterio de implementación.
- `/speckit-analyze` debe confirmar que **T052 existe** y demuestra que un
  fichaje cerrado no se puede modificar.
