---

description: "Tareas de implementación: exportación del registro de jornada"
---

# Tasks: Exportación del registro de jornada

**Input**: Documentos de diseño de `/specs/003-timetracking-export/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/README.md](./contracts/README.md), [quickstart.md](./quickstart.md)

**Tests**: **obligatorios**. El principio V exige unitarios, integración con
Testcontainers contra Postgres real, y un test que falle si se rompe cada
invariante de los principios III, IV, VI y VII.

**Organization**: por historia de usuario, en el orden de prioridad de la spec.
Cada fase deja el build en verde.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: paralelizable (ficheros distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece (US1…US5)
- **🔍**: cierra uno de los **dos huecos encontrados en código existente**
  ([plan.md](./plan.md#huecos-encontrados-en-código-existente))
- **🧬**: test que se **valida por mutación**. Sus garantías son invisibles para
  los tests secuenciales, así que la tarea solo está hecha cuando se ha roto el
  código a propósito, el test se ha puesto rojo, se ha restaurado y se ha dejado
  escrito en el propio test qué mutación detecta. Es la lección de la feature 002:
  dos garantías de concurrencia pasaban toda la suite secuencial con el código roto.

## Path Conventions

Proyecto multi-módulo. Namespace compartido `com.granatum.core`. Rutas desde la raíz:

- `timetracking/src/main/kotlin/com/granatum/core/…` y `timetracking/src/test/kotlin/com/granatum/core/…`
- `common/src/main/kotlin/com/granatum/core/…` — solo el hueco de `VALIDACION`
- `app/src/…` — `SecurityConfig` y los tests que necesitan la cadena de filtros real

---

## Phase 1: Setup

**Purpose**: configuración que necesitan todas las historias.

- [X] T001 Añadir a `app/src/main/resources/application.yml`, bajo `timetracking:`, el bloque `exportacion:` con `concurrencia: ${TIMETRACKING_EXPORTACION_CONCURRENCIA:2}`, `espera-ms: ${TIMETRACKING_EXPORTACION_ESPERA_MS:1000}`, `timeout-segundos: ${TIMETRACKING_EXPORTACION_TIMEOUT_SEGUNDOS:120}`, `tamano-lote: ${TIMETRACKING_EXPORTACION_TAMANO_LOTE:500}` y `verificacion-max-bytes: ${TIMETRACKING_VERIFICACION_MAX_BYTES:104857600}`. Añadir también `spring.mvc.async.request-timeout: 130s`, con un comentario que explique por qué: `StreamingResponseBody` corre en modo asíncrono y el tiempo por defecto del contenedor cortaría una exportación grande a mitad sin error visible. Tiene que ser mayor que `timeout-segundos` para que el límite que actúe sea el de la transacción (D-002)
- [X] T002 [P] Añadir el mismo bloque `timetracking.exportacion` a `timetracking/src/test/resources/application.yml` con valores de test: `concurrencia: 2`, `espera-ms: 2000`, `timeout-segundos: 60`, `tamano-lote: 500`, `verificacion-max-bytes: 1048576`
- [X] T003 [P] Añadir las cinco variables `TIMETRACKING_EXPORTACION_*` y `TIMETRACKING_VERIFICACION_MAX_BYTES` a `.env.example`, con un comentario que diga que no son secretos
- [X] T004 Verificar `./gradlew build` en verde antes de escribir lógica

**Checkpoint**: configuración en su sitio, nada roto.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: esquema, los dos huecos de código existente, el límite de conservación y el escritor puro.

**⚠️ CRITICAL**: ninguna historia empieza hasta terminar esta fase.

### Migraciones (V15–V16)

- [X] T005 Crear `timetracking/src/main/resources/db/migration/V15__create_exportaciones_table.sql` con estas columnas: `id UUID PRIMARY KEY`, `solicitante_id UUID NOT NULL`, `rol_solicitante VARCHAR(20) NOT NULL`, `alcance VARCHAR(10) NOT NULL`, `empleado_id UUID`, `desde DATE NOT NULL`, `hasta DATE NOT NULL`, `generada_en TIMESTAMPTZ NOT NULL`, `completada BOOLEAN NOT NULL`, `filas INTEGER NOT NULL`, `huella VARCHAR(64)`. Restricciones: `CHECK (rol_solicitante IN ('ADMIN','ENCARGADO','EMPLEADO','REPRESENTANTE'))`, `CHECK (alcance IN ('PERSONA','PLANTILLA','MENSUAL'))`, `CHECK ((alcance = 'PLANTILLA') = (empleado_id IS NULL))`, `CHECK (hasta >= desde)`, `CHECK (filas >= 0)`, `CHECK (huella IS NULL OR char_length(huella) = 64)` y `CHECK (completada = (huella IS NOT NULL))`. Índices: `idx_exportaciones_huella (huella)`, `idx_exportaciones_empleado (empleado_id, generada_en)` e `idx_exportaciones_hasta (hasta)`. Terminar con `ALTER TABLE exportaciones ENABLE ROW LEVEL SECURITY`, nunca `FORCE`. Comentar en el fichero: es de **solo inserción**; no tiene clave ajena para que ningún borrado se propague a una tabla inmutable; la huella no es `UNIQUE` porque dos exportaciones idénticas comparten huella a propósito (FR-011); y no guarda ningún dato exportado (FR-026)
- [X] T006 Crear `timetracking/src/main/resources/db/migration/V16__add_exportaciones_eliminadas_to_depuraciones.sql` con `ALTER TABLE depuraciones_retencion ADD COLUMN exportaciones_eliminadas INTEGER NOT NULL DEFAULT 0 CHECK (exportaciones_eliminadas >= 0)`. Comentar por qué es una migración nueva y no una edición de V11: V11 ya está aplicada en `main` y el principio II prohíbe editarla. Y por qué el `DEFAULT 0`: las depuraciones anteriores no borraron exportaciones porque la tabla no existía, y con ese valor siguen siendo ciertas
- [X] T007 [P] Añadir `"exportaciones"` a `tablasEsperadas` en `timetracking/src/test/kotlin/com/granatum/core/RowLevelSecurityIT.kt`

### 🔍 Hueco 1: los `400` de validación sin el formato del contrato (D-019)

- [X] T008 🔍 Crear `app/src/test/kotlin/com/granatum/core/ValidacionFormatoIT.kt` (puerto real, `ClientePruebaHttp`) y comprobar que **falla antes de T009**. Casos: `POST /api/auth/login` con un correo mal formado; `GET /api/fichajes/empleado/{id}/resumen` sin `anio`; `GET /api/fichajes/empleado/{id}/resumen?anio=x&mes=1` con un tipo erróneo; y `POST /api/categorias` con un JSON ilegible. En todos: estado `400`, `code` igual a `VALIDACION`, un cuerpo **sin** clave `trace`, y **sin el valor enviado**: el correo mal formado no puede aparecer en el cuerpo. KDoc con el motivo: hasta esta feature esos `400` salían con el cuerpo por defecto de Spring, y en `dev` con la traza y el valor rechazado; los tests de `auth` solo comprobaban el estado y por eso no lo vieron
- [X] T009 🔍 Añadir a `common/src/main/kotlin/com/granatum/core/api/exception_handling/CommonExceptionHandler.kt` manejadores `400 { "code": "VALIDACION", "message": … }` para `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException` y `HttpMessageNotReadableException`. El mensaje nombra el **campo o el parámetro** y **nunca** el valor rechazado: puede ser una contraseña. KDoc: no compite con los `@Order(HIGHEST_PRECEDENCE)` de los módulos, porque son excepciones del framework y no subclases de las suyas

### Límite del plazo de conservación, en un solo sitio (D-009)

- [X] T010 [P] Crear `timetracking/src/test/kotlin/com/granatum/core/PlazoConservacionTest.kt` (unitario, con `Clock` fijo): con 4 años y hoy `2026-10-06`, `fechaCorte()` es `2022-10-06`; el primer día que puede tener datos es ese mismo; y un año bisiesto (`2028-02-29` menos 4 años) da `2024-02-29`
- [X] T011 Crear `timetracking/src/main/kotlin/com/granatum/core/service/PlazoConservacion.kt` como `@Component` que posee `${timetracking.retencion.anios:4}` y un `Clock` inyectado con valor por defecto, y expone `fechaCorte(): LocalDate`. KDoc: es el **único** sitio que calcula el límite. Si la exportación y la depuración lo calcularan cada una por su cuenta, cambiar el plazo en una y no en la otra haría que la exportación anunciara datos ya depurados o escondiera datos que existen
- [X] T012 Cambiar `timetracking/src/main/kotlin/com/granatum/core/scheduling/DepuracionRetencionJob.kt` para que obtenga el corte de `PlazoConservacion` en lugar de calcularlo; sin cambio de comportamiento. `RetencionIT` tiene que seguir en verde sin tocarlo

### 🔍 Hueco 2: el resumen mensual leía toda la historia (D-011)

- [X] T013 🔍 Crear `timetracking/src/test/kotlin/com/granatum/core/ResumenMensualConsultasIT.kt` y comprobar que **falla antes de T015**. Sembrar 50 correcciones aprobadas de **otras** personas y de **otros** meses, y una del mes y de la persona consultados. Con las estadísticas de Hibernate, afirmar que `resumenMensual` carga como mucho **una** `SolicitudCorreccionFichajeEntity` (la del mes) y que el número de sentencias no crece con las correcciones ajenas. KDoc: la versión anterior cargaba todas las correcciones aprobadas de la tabla para cada resumen, aunque su comentario decía "una consulta sobre el mes"
- [X] T014 Añadir a `timetracking/src/main/kotlin/com/granatum/core/infrastructure/database/repositories/SolicitudCorreccionFichajeRepository.kt` `findFichajeIdsConEstado(fichajeIds: Collection<UUID>, estado: EstadoSolicitud): List<UUID>`, con `@Query` sobre `s.fichaje.id IN :ids AND s.estado = :estado` y servida por `idx_solicitudes_fichaje_estado`. Añadir también `findAllByFichajeIdInAndEstadoOrderByResueltaEnAsc(fichajeIds, estado)`, que la exportación usará en T030
- [X] T015 🔍 Cambiar `resumenMensual` en `timetracking/src/main/kotlin/com/granatum/core/service/FichajeService.kt` para que obtenga los fichajes corregidos con `findFichajeIdsConEstado(<ids del mes>, APROBADA)` y no con `findAllByEstado(APROBADA)`, y corregir el comentario que decía lo contrario. Con lista vacía, sin consulta. `ResumenMensualIT` sigue en verde y `ResumenMensualConsultasIT` pasa a verde

### Dominio y escritor puro

- [X] T016 [P] Crear `timetracking/src/main/kotlin/com/granatum/core/domain/model/Exportacion.kt` con `FilaRegistro` (persona, documento?, puesto, fecha, entrada, salida?, pausas, minutosTrabajados?, estado, completadoAPosteriori, corregido, original: ValoresOriginales?, correcciones: List<CorreccionAplicada>), `CorreccionAplicada(resueltaEn, solicitante, aprobador)`, `ValoresOriginales(entrada, salida?, pausas)`, `AlcanceExportacion` (sealed: `Persona(empleadoId)`, `Plantilla`, `Mensual(empleadoId, anio, mes)`) y `RangoEfectivo(desde, hasta, recortado)`. Sin JPA ni HTTP
- [X] T017 Crear `timetracking/src/test/kotlin/com/granatum/core/EscritorCsvTest.kt` (unitario, sin Spring). Comprobar: el fichero empieza por los bytes `EF BB BF`; separador `;`; fin de línea CRLF; cabecera en el orden de D-004; entrecomillado de valores con `;`, `"`, CR o LF, con `"` interiores duplicadas, y el mismo número de columnas en todas las filas (FR-010); **anti-fórmula**: valores que empiezan por `=`, `+`, `-`, `@`, tabulador o CR salen precedidos de `'` y entrecomillados (FR-009); **sin falsos positivos**: fechas `yyyy-MM-dd`, `yyyy-MM-dd HH:mm`, horas `0:05`, `8:30` y `12:00` y minutos enteros salen sin `'`; horas y minutos vacíos (no `0`) para un fichaje sin `minutosTrabajados` (FR-006); sin documento, la columna `Documento` **no existe** (D-012); `Sí`/`No` en los indicadores; las mismas filas producen **los mismos bytes** (FR-011); BOM y `;` son lo que hace que se abra sin asistente en una hoja de cálculo española (FR-008, SC-005); y la protección contra fórmulas cubre SC-006; el bloque final mensual (línea vacía, `Total del mes`, `Tipo de contrato`, `Mes cerrado`) **con y sin** columna `Documento`, comprobando que el total cae bajo "Horas trabajadas" y "Minutos trabajados" en los dos casos (hallazgo M1); y la celda de correcciones con el formato `yyyy-MM-dd HH:mm solicitada por X, aprobada por Y` separadas por ` | `
- [X] T018 Crear `timetracking/src/main/kotlin/com/granatum/core/domain/service/EscritorCsv.kt` como objeto puro que escribe sobre un `OutputStream`: BOM, cabecera según `incluirDocumento`, una fila por `FilaRegistro` y, opcionalmente, el bloque mensual. Formatea en `Europe/Madrid` (FR-003). KDoc con D-004 y D-005, incluido por qué ningún valor legítimo empieza por un carácter peligroso. Sin `ObjectMapper` ni librerías de CSV: ninguna aplica la protección contra fórmulas por defecto

### Persistencia del registro de exportaciones

- [X] T019 [P] Crear `timetracking/src/main/kotlin/com/granatum/core/infrastructure/database/entities/ExportacionEntity.kt`: clase normal, nunca `data class`; todos los campos `val`; `rolSolicitante` como `Role` y `alcance` como enum `AlcanceRegistro { PERSONA, PLANTILLA, MENSUAL }`, ambos con `EnumType.STRING`; `huella` nulable y de longitud 64; identidad por `id`; un `toString()` con id, alcance y `completada`
- [X] T020 Crear `timetracking/src/main/kotlin/com/granatum/core/infrastructure/database/repositories/ExportacionRepository.kt` que extiende **`Repository<ExportacionEntity, UUID>`** con `save`, `findAllByHuellaOrderByGeneradaEnAsc(huella)` y una consulta con filtros opcionales por `empleadoId`, por `generadaEn` entre dos instantes y por periodo cubierto (`desde <= :cubreHasta AND hasta >= :cubreDesde`), ordenada por `generadaEn` descendente. **Sin `delete`**: el único borrado es el de `RetencionPurgaRepository` (T052). Es lo que sostiene FR-027. Añadir además `ExportacionRepository::class.java` a `repositoriosNormales` en `timetracking/src/test/kotlin/com/granatum/core/SinBorradoDentroDelPlazoIT.kt`: ese test recorre una lista **fija** de seis repositorios, y sin esta línea nada fallaría si alguien añadiera `delete` al repositorio nuevo (principio III; hallazgo C1 del análisis)
- [X] T021 Crear `timetracking/src/main/kotlin/com/granatum/core/service/RegistroExportaciones.kt` con `registrar(...)` en una transacción **propia** (`@Transactional`, sin anidar). KDoc en mayúsculas: **no se llama nunca con una transacción de lectura abierta**, porque en la feature 002 escribir en una transacción anidada mientras otra seguía abierta bloqueó el pool. Inserta una sola vez, completa o interrumpida (D-003)
- [X] T022 Añadir a `timetracking/src/main/kotlin/com/granatum/core/domain/exception/TimetrackingExceptions.kt` `ExportacionSaturadaException` y `FicheroDemasiadoGrandeException`, y comprobar que sus nombres no existen en ningún otro módulo (la lección de colisión de la feature 002). Mapearlas en `timetracking/src/main/kotlin/com/granatum/core/api/exception_handling/TimetrackingExceptionHandler.kt`: `503 EXPORTACION_SATURADA` con `Retry-After: 1`, y `413 FICHERO_DEMASIADO_GRANDE`
- [X] T023 Verificar `./gradlew build` en verde: `ValidacionFormatoIT`, `PlazoConservacionTest`, `ResumenMensualConsultasIT` y `EscritorCsvTest` en verde, y `RetencionIT` y `ResumenMensualIT` sin cambios

**Checkpoint**: los dos huecos cerrados, el límite calculado en un solo sitio y el escritor probado. Empiezan las historias.

---

## Phase 3: User Story 1 - Descargar mi propio registro (Priority: P1) 🎯 MVP

**Goal**: una persona descarga su registro de un rango, coherente y anotado.

**Independent Test**: con varios fichajes —normal, corregido, abierto, partido, en día de cambio de hora—, exportar el rango y cruzar cada fila con el fichaje vigente.

### Tests for User Story 1 ⚠️

- [X] T024 [P] [US1] Crear `timetracking/src/test/kotlin/com/granatum/core/ExportacionPersonaIT.kt` (Testcontainers), llamando al servicio con un `ByteArrayOutputStream`. Comprobar: una fila por fichaje (FR-001); **cruce de todas las filas** con los fichajes vigentes en entrada, salida, pausas, minutos y estado (SC-002); un fichaje abierto y uno `INCOMPLETO` con horas vacías (FR-006); un corregido con los valores vigentes, los **originales de la primera corrección aprobada** y la celda de correcciones con nombres (FR-004, FR-005, SC-010); las correcciones pendientes y rechazadas no aparecen; una jornada partida da dos filas; la columna de pausas lleva tipo, inicio y fin (FR-002); una jornada que cruza el cambio a horario de invierno —de 22:00 a 07:00, con el reloj retrocediendo a las 03:00— cuenta 600 minutos y muestra `22:00` y `07:00` en hora local (*corregido al implementar: el enunciado original decía 07:00–16:00, que no cruza el cambio y dura 540*); un fichaje que cruza la medianoche pertenece al día de su entrada y muestra la salida con su fecha; un rango sin fichajes da solo la cabecera; un rango invertido se rechaza; un `EMPLEADO` que pide a otra persona recibe `ForbiddenException`; una persona inexistente da `EmpleadoNotFoundException`; y **exportar no escribe nada** (FR-018): ni filas nuevas en las tablas del registro ni cambios en `updated_at`. Cubre SC-001 en el servicio; T056 lo cubre por HTTP
- [X] T025 [P] [US1] Crear `timetracking/src/test/kotlin/com/granatum/core/RegistroExportacionesIT.kt`. Una exportación completa inserta **una** fila con `completada = true`, `filas` igual a las filas de datos y `huella` igual al SHA-256 de los bytes recibidos, BOM incluido. Una interrumpida, con un `OutputStream` que lanza `IOException` a mitad, inserta **una** fila con `completada = false`, las filas escritas hasta el corte y sin huella. La fila no contiene nombres, documentos ni horas (FR-026). Cubre FR-025 y SC-007
- [X] T026 [P] [US1] 🧬 Crear `timetracking/src/test/kotlin/com/granatum/core/InstantaneaExportacionIT.kt` con `tamano-lote` = 1. El `OutputStream` del test, al recibir la primera fila, aprueba **desde otro hilo**, y espera a que confirme, una corrección sobre un fichaje que todavía no se ha escrito. El fichero tiene que mostrar ese fichaje **sin** la corrección: la exportación entera ve la instantánea del principio (D-002). **Validación por mutación**: sustituir la transacción única por una transacción por lote, comprobar que el test se pone rojo, restaurar, y dejar escrito el resultado en el KDoc del test
- [X] T027 [P] [US1] Crear `timetracking/src/test/kotlin/com/granatum/core/RangoConservacionIT.kt`: un rango que empieza antes de `PlazoConservacion.fechaCorte()` se recorta a esa fecha y devuelve `RangoEfectivo(recortado = true)`; uno dentro del plazo no se toca (FR-017)

### Implementation for User Story 1

- [X] T028 [US1] Añadir a `timetracking/src/main/kotlin/com/granatum/core/infrastructure/database/repositories/FichajeRepository.kt` una consulta paginada por clave con `@EntityGraph(attributePaths = ["pausas"])`: fichajes de una persona con `entrada` en `[inicio, fin)` y `(entrada, id) > (:entradaPrevia, :idPrevio)`, ordenados por `entrada, id` ascendente, con `Pageable` para limitar al tamaño de lote. KDoc: clave y no `OFFSET`, servida por `idx_fichajes_empleado_entrada`. *Al implementar*: `@EntityGraph` sobre la colección `pausas` junto con `Pageable` hace que Hibernate quite el `LIMIT` del SQL y pagine **en memoria** tras leer todo el rango. Se divide en dos consultas: ids por clave con `LIMIT` (`findIdsSiguienteLote`) y fichajes con pausas por id (`findAllConPausasByIdIn`). Son cuatro consultas por lote en vez de tres
- [X] T029 [US1] Añadir a `timetracking/src/main/kotlin/com/granatum/core/infrastructure/database/repositories/EmpleadoRepository.kt` `findAllByIdIn(ids: Collection<UUID>): List<EmpleadoEntity>`, para cargar en una sola consulta por lote los nombres de quien solicitó y quien aprobó
- [X] T030 [US1] Crear `timetracking/src/main/kotlin/com/granatum/core/service/ExportacionService.kt` con `exportar(alcance, desde, hasta, solicitanteId, rol, salida: OutputStream): ResultadoExportacion`. Valida el rango (FR-016). Comprueba la propiedad contra el **sujeto del token**: un `EMPLEADO` solo puede pedirse a sí mismo (FR-013). Recorta con `PlazoConservacion` (FR-017). Abre **una** transacción con `TransactionTemplate` de solo lectura, `ISOLATION_REPEATABLE_READ` y `timeout = timeout-segundos`. Recorre por lotes de `tamano-lote` (T028) y carga en cada lote las correcciones aprobadas (`findAllByFichajeIdInAndEstadoOrderByResueltaEnAsc`) y los nombres (T029): **tres consultas por lote y ninguna por fila**. Escribe con `EscritorCsv` a través de un `DigestOutputStream` (SHA-256) que también cuenta filas. Y **después** de cerrar la transacción llama a `RegistroExportaciones` (T021), también si se interrumpió. KDoc con D-002 y D-003. *Al implementar*: se divide en `preparar` (validación, propiedad y recorte, antes del primer byte) y `escribir`, porque con el cuerpo ya en marcha el estado es `200` y un error no puede cambiarlo. Un rango **entero** anterior al plazo se rechaza con `VALORES_INCOHERENTES` indicando desde qué fecha hay datos: un fichero vacío se leería como "sin fichajes", que es lo que FR-017 quiere evitar
- [X] T031 [US1] Crear `timetracking/src/main/kotlin/com/granatum/core/api/controllers/ExportacionController.kt` con `GET /api/fichajes/export` (`formato` por defecto `csv`; cualquier otro valor da `ValoresIncoherentesException`). Devuelve `ResponseEntity<StreamingResponseBody>` con `Content-Type: text/csv; charset=UTF-8`, `Content-Disposition` con el id de la persona o `plantilla` y el **rango efectivo**, **nunca el nombre** (D-017), y `X-Registro-Disponible-Desde` solo si se recortó. El sujeto y el rol del token se leen en el hilo de la petición, **antes** de devolver el cuerpo: `StreamingResponseBody` corre en otro hilo, donde el `SecurityContext` no está. *Al implementar*: probado por HTTP real, la descarga cortaba la conexión al final. La descarga termina con un *async dispatch* que vuelve a pasar por la cadena de seguridad, `JwtAuthFilter` lo salta, y se denegaba con el fichero ya enviado. Se arregla permitiendo `DispatcherType.ASYNC` en `SecurityConfig` y lo protege `app/src/test/kotlin/com/granatum/core/ExportacionHttpIT.kt`, que T056 amplía
- [X] T032 [US1] Verificar US1 con `./gradlew :timetracking:test`: `timetracking/src/test/kotlin/com/granatum/core/ExportacionPersonaIT.kt`, `RegistroExportacionesIT.kt`, `InstantaneaExportacionIT.kt` y `RangoConservacionIT.kt` en verde, y el KDoc de `InstantaneaExportacionIT.kt` con el resultado de la mutación

**Checkpoint**: una persona descarga su registro; el MVP funciona.

---

## Phase 4: User Story 2 - Descarga mensual del registro de una persona (Priority: P1)

**Goal**: el documento mensual que desbloquea la depuración (FR-031d de la feature 001) y que sirve de resumen del art. 12.4.c.

**Independent Test**: un mes con jornadas normales, una corregida y una reconstruida; el total del fichero es exactamente el del resumen en pantalla.

### Tests for User Story 2 ⚠️

- [X] T033 [P] [US2] Crear `timetracking/src/test/kotlin/com/granatum/core/DescargaMensualIT.kt`. Descarga del mes natural con cada jornada (FR-019). El total del bloque final es **exactamente** `totalMinutosTrabajados` de `resumenMensual` para la misma persona y mes, incluidas una jornada corregida y una reconstruida (FR-020, SC-003). Las dos aparecen señaladas. `Tipo de contrato;PARCIAL` para un contrato a tiempo parcial (FR-021). `Mes cerrado;No` para el mes en curso y `Sí` para uno pasado (FR-022). La propia persona, `ENCARGADO`, `ADMIN` y `REPRESENTANTE` pueden descargarlo; otro `EMPLEADO` no (FR-023). Y queda anotado como `MENSUAL`, con `desde` el día 1 y `hasta` el último día del mes
- [X] T034 [P] [US2] Crear `timetracking/src/test/kotlin/com/granatum/core/DepuracionPorDefectoIT.kt` (FR-024): sin propiedades, `DepuracionRetencionJob` **no borra nada** aunque haya registros vencidos. Hasta ahora ningún test lo comprobaba: `RetencionIT` solo prueba la depuración activada. Esta feature cumple la condición que la bloqueaba, y lo que tiene que quedar fijado es que eso no la activa sola

### Implementation for User Story 2

- [X] T035 [US2] Añadir `descargarMensual(empleadoId, anio, mes, solicitanteId, rol, salida)` a `timetracking/src/main/kotlin/com/granatum/core/service/ExportacionService.kt`. Reutiliza el recorrido de US1 sobre el mes completo y calcula el total con **`CalculadoraResumenMensual`**, la misma función del resumen en pantalla, nunca un cálculo propio (D-011). Escribe el bloque final con `EscritorCsv` y anota como `MENSUAL`. *Al implementar*: `DescargaMensualIT` encontró que el resumen en pantalla y la descarga **no coincidían** (1155 frente a 915 minutos). `resumenMensual` usa `EntradaBetween`, que incluye los dos extremos, y contaba en el mes un fichaje que empieza a las 00:00 exactas del día 1 del mes siguiente. Se corrige el resumen excluyendo ese extremo (`FichajeService.resumenMensual`), porque el fichaje es del mes siguiente. Los listados por rango de `FichajeService` tienen el mismo defecto y quedan como tarea aparte
- [X] T036 [US2] Añadir `GET /api/fichajes/empleado/{empleadoId}/resumen/descarga?anio=&mes=` a `timetracking/src/main/kotlin/com/granatum/core/api/controllers/ExportacionController.kt`, con nombre `registro-mensual_<empleadoId>_<anio>-<mes>.csv`. Mes fuera de 1–12 → `ValoresIncoherentesException`, como el resumen existente
- [X] T037 [US2] Verificar US2 con `./gradlew :timetracking:test`: `timetracking/src/test/kotlin/com/granatum/core/DescargaMensualIT.kt` y `timetracking/src/test/kotlin/com/granatum/core/DepuracionPorDefectoIT.kt` en verde

**Checkpoint**: la condición de FR-031d se cumple, y la depuración sigue apagada.

---

## Phase 5: User Story 3 - Exportar el registro de toda la plantilla (Priority: P2)

**Goal**: un único fichero con toda la plantilla para `ENCARGADO` y `ADMIN`, idéntico byte a byte si no ha cambiado nada.

**Independent Test**: varias personas, una dada de baja y dos con el mismo nombre; dos exportaciones seguidas son idénticas.

### Tests for User Story 3 ⚠️

- [X] T038 [P] [US3] Crear `timetracking/src/test/kotlin/com/granatum/core/ExportacionPlantillaIT.kt`. Aparecen todas las personas con fichajes en el rango, **incluida una dada de baja** (FR-015, SC-011). El orden es por nombre con colación española —`Ángel` antes que `Zoe`, `Óscar` entre `Nuria` y `Pablo`— y, a igual nombre, por id. Un `EMPLEADO` recibe `ForbiddenException`, y `ENCARGADO`, `ADMIN` y `REPRESENTANTE` pueden exportar la plantilla o una persona (FR-014)
- [X] T039 [P] [US3] 🧬 Crear `timetracking/src/test/kotlin/com/granatum/core/DeterminismoExportacionIT.kt`. Dos exportaciones seguidas de la plantilla, sin cambios entre medias, dan **los mismos bytes** y la misma huella (FR-011, SC-008). Dos personas con el mismo nombre insertadas en orden inverso a su id no alteran el resultado. **Validación por mutación**, las dos: (a) quitar el desempate por id; (b) escribir la fecha de generación dentro del fichero. Comprobar que el test se pone rojo en cada caso, restaurar, y dejarlo escrito en el KDoc
- [X] T040 [P] [US3] Crear `timetracking/src/test/kotlin/com/granatum/core/SaturacionExportacionIT.kt` con `concurrencia = 1` y `espera-ms` corto. Una segunda exportación simultánea lanza `ExportacionSaturadaException`. Y el permiso **se devuelve** aunque la exportación termine con excepción: la siguiente funciona
- [X] T041 [P] [US3] Crear `timetracking/src/test/kotlin/com/granatum/core/ClienteLentoExportacionIT.kt` con puerto real: un cliente lee los primeros bytes de una exportación grande y **deja de leer**. Afirmar que la conexión vuelve al pool (`HikariPoolMXBean.activeConnections`) en menos de `timeout-segundos` + 15 s y que la exportación queda anotada como **interrumpida** (D-003). Si la conexión no vuelve, añadir un corte explícito —`server.tomcat.connection-timeout` o una salida que compruebe el plazo en cada escritura— hasta que el test pase, y escribir en D-002 el mecanismo que lo consigue. El tiempo máximo de transacción **no** sirve: solo se aplica a las consultas, y un hilo bloqueado escribiendo al socket no lanza ninguna (hallazgo H2) *Al implementar*: vive en `app/src/test/kotlin/com/granatum/core/ClienteLentoExportacionIT.kt`, porque `timetracking` no tiene servidor web ni la cadena de seguridad real, con su propio Postgres en Testcontainers para no sembrar 40.000 fichajes en la base de desarrollo. Medido: con el `connection-timeout` implícito de Tomcat (60 s) la conexión se retuvo 65 s; fijado a 10 s en `application.yml` (`SERVER_TOMCAT_CONNECTION_TIMEOUT`), vuelve en 15,5 s. Escrito en D-002

### Implementation for User Story 3

- [X] T042 [US3] Implementar en `timetracking/src/main/kotlin/com/granatum/core/service/ExportacionService.kt` el alcance `Plantilla`: carga primero las personas con fichajes en el rango, activas e inactivas; las ordena en Kotlin con `java.text.Collator` de `Locale.of("es", "ES")` y luego por id (D-008); y recorre sus fichajes persona a persona con la misma consulta por clave. KDoc: el orden no se hace en SQL porque la *collation* de Postgres cambia entre Docker y Supabase
- [X] T043 [US3] Envolver `exportar` y `descargarMensual` en un `Semaphore(concurrencia, true)` con `tryAcquire(espera-ms)`, y liberarlo en `finally`, en `timetracking/src/main/kotlin/com/granatum/core/service/ExportacionService.kt` (D-002) *Al implementar*: el permiso se toma en `preparar`, en el hilo de la petición, para que la saturación sea un `503` y no un `200` cortado, y se devuelve en `escribir` con `finally` y una guarda contra la doble devolución. Una preparación rechazada no se queda el permiso
- [X] T044 [US3] Verificar US3 con `./gradlew :timetracking:test`: `timetracking/src/test/kotlin/com/granatum/core/ExportacionPlantillaIT.kt`, `DeterminismoExportacionIT.kt` y `SaturacionExportacionIT.kt` en verde, y el KDoc de `DeterminismoExportacionIT.kt` con el resultado de las dos mutaciones

**Checkpoint**: la entrega para la Inspección funciona y es reproducible.

---

## Phase 6: User Story 4 - Puesta a disposición de la representación legal (Priority: P2)

**Goal**: `REPRESENTANTE` obtiene el registro sin documento y sin ubicación.

**Independent Test**: exportar la plantilla con fichajes que tienen ubicación; el fichero no contiene ni la columna `Documento` ni ninguna coordenada.

### Tests for User Story 4 ⚠️

- [X] T045 [P] [US4] Crear `timetracking/src/test/kotlin/com/granatum/core/ExportacionRepresentanteIT.kt`. Sembrar fichajes **con ubicación** y documentos distintivos. Con rol `REPRESENTANTE`, de la plantilla y de una persona: no aparece la cabecera `Documento` ni ningún documento sembrado (FR-012); no aparece **ninguna latitud ni longitud** sembrada, y con rol `ADMIN` tampoco (FR-007, SC-004); exportar no escribe nada en el registro de jornada; la fila del registro de exportaciones tiene `rol_solicitante = REPRESENTANTE`; y lo mismo para la **descarga mensual** con rol `REPRESENTANTE` (FR-023): ni cabecera `Documento`, ni documentos, ni coordenadas. Sale por otro método (T035), así que probar solo la plantilla dejaría pasar una fuga en el mensual (hallazgo H1)

### Implementation for User Story 4

- [X] T046 [US4] En `timetracking/src/main/kotlin/com/granatum/core/service/ExportacionService.kt`, construir `FilaRegistro.documento` como `null` y llamar a `EscritorCsv` con `incluirDocumento = false` cuando el rol es `REPRESENTANTE`. KDoc: se omite la **columna**, no se deja vacía, porque una cabecera vacía sugiere que el dato existe y falta (D-012). La ubicación no entra en `FilaRegistro` para ningún rol
- [X] T047 [US4] Verificar US4 con `./gradlew :timetracking:test`: `timetracking/src/test/kotlin/com/granatum/core/ExportacionRepresentanteIT.kt` en verde

**Checkpoint**: se cumple la obligación del art. 34.9 con el mínimo de datos.

---

## Phase 7: User Story 5 - Saber quién exportó qué y verificar un fichero (Priority: P3)

**Goal**: consulta del registro de exportaciones, verificación de ficheros y depuración de exportaciones antiguas.

**Independent Test**: exportar, alterar un carácter, verificar ambos ficheros.

### Tests for User Story 5 ⚠️

- [X] T048 [P] [US5] Crear `timetracking/src/test/kotlin/com/granatum/core/VerificacionExportacionesIT.kt`. Un fichero recién exportado **coincide** y se devuelve su exportación (FR-028). Dos exportaciones idénticas aparecen **las dos**. El mismo fichero con un solo carácter cambiado **no coincide** (SC-008). Un cuerpo mayor que `verificacion-max-bytes` lanza `FicheroDemasiadoGrandeException` sin leerlo entero. Y no queda ningún fichero ni fragmento guardado
- [X] T049 [P] [US5] Crear `timetracking/src/test/kotlin/com/granatum/core/ConsultaExportacionesIT.kt` con los tres filtros de FR-029 —persona, **fecha de generación** (`generadaDesde`/`generadaHasta`) y **periodo cubierto** (`cubreDesde`/`cubreHasta`, por solapamiento)— y el orden descendente. Incluir el solapamiento parcial: una exportación de febrero a abril aparece al pedir marzo, y una de enero no
- [X] T050 [P] [US5] Ampliar `timetracking/src/test/kotlin/com/granatum/core/RetencionIT.kt`: con la depuración activada, una exportación cuyo `hasta` es anterior al corte se borra y cuenta en `exportacionesEliminadas`; una que aún cubre fechas dentro del plazo sobrevive (D-013, FR-027: ninguna anotación se borra mientras dure el plazo de lo que cubre)

### Implementation for User Story 5

- [X] T051 [US5] Crear `timetracking/src/main/kotlin/com/granatum/core/service/VerificacionExportaciones.kt`: lee un `InputStream` por bloques, calcula SHA-256 y corta al pasar de `verificacion-max-bytes`; **no guarda ni registra** el contenido (D-007); devuelve la huella y las exportaciones de `findAllByHuellaOrderByGeneradaEnAsc`
- [X] T052 [US5] Añadir `borrarExportacionesAnterioresA(corte: LocalDate)` a `timetracking/src/main/kotlin/com/granatum/core/infrastructure/database/repositories/RetencionPurgaRepository.kt`, como `@Modifying @Query` acotada por `hasta < :corte`. Añadir `exportacionesEliminadas` a `timetracking/src/main/kotlin/com/granatum/core/infrastructure/database/entities/DepuracionRetencionEntity.kt`, y en `DepuracionRetencionJob` borrar y contar las exportaciones. KDoc: es el único borrado posible de esa tabla
- [X] T053 [US5] Crear `timetracking/src/main/kotlin/com/granatum/core/api/controllers/AuditoriaExportacionController.kt` con `GET /api/exportaciones` (filtros opcionales `empleadoId`, `generadaDesde`, `generadaHasta`, `cubreDesde`, `cubreHasta`) y `POST /api/exportaciones/verificar`, que consume `text/csv` y `application/octet-stream` con un parámetro `InputStream` y nunca un DTO. Las respuestas, en DTO de `timetracking/src/main/kotlin/com/granatum/core/api/dto/ExportacionDtos.kt`, solo con identificadores (FR-026). Con `toString()` seguro aunque solo lleven ids, por coherencia con la lección de la feature 002 *Al implementar*: la consulta vive en `RegistroExportaciones.consultar` (el dueño del registro), que pasa las fechas de generación a días civiles de Madrid con los dos extremos incluidos. El filtro por persona **incluye las exportaciones de la plantilla**, que también contienen sus datos (decidido al implementar; recogido en el contrato)
- [X] T054 [US5] Añadir a `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt` la regla `.requestMatchers("/api/exportaciones/**", "/api/exportaciones").hasRole("ADMIN")` **antes** del comodín, con un comentario que explique por qué la ruta no cuelga de `/api/fichajes` (D-014)
- [X] T055 [US5] Verificar US5 con `./gradlew :timetracking:test`: `timetracking/src/test/kotlin/com/granatum/core/VerificacionExportacionesIT.kt`, `ConsultaExportacionesIT.kt` y `RetencionIT.kt` en verde

**Checkpoint**: las cinco historias funcionan.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: los tests que solo pueden vivir en `app`, la documentación y la prueba contra la aplicación en marcha.

- [X] T056 [P] Crear `app/src/test/kotlin/com/granatum/core/ExportacionHttpIT.kt` sobre puerto real con bytes reales. Comprobar: los tres primeros bytes `EF BB BF`; `Content-Type` `text/csv`; `Content-Disposition` con el id y **no** con el nombre; `X-Registro-Disponible-Desde` con un rango recortado; `REPRESENTANTE` sin columna `Documento`; un `EMPLEADO` con el id de otra persona recibe `403`; `formato=pdf` da `400 VALORES_INCOHERENTES`; el fichero descargado verifica como coincidente por `POST /api/exportaciones/verificar` y, con un carácter cambiado, no; un `EMPLEADO` obtiene su propio registro por HTTP (SC-001); y con concurrencia saturada se recibe `503 EXPORTACION_SATURADA` con `Retry-After` *(Ya existe desde T031 con tres tests: descarga completa por HTTP, que protege el arreglo del async dispatch, recorte del plazo, y rechazos con su código. Ampliarlo, no crearlo.)*
- [X] T057 [P] Crear `app/src/test/kotlin/com/granatum/core/AutorizacionExportacionesIT.kt`: `GET /api/exportaciones` y `POST /api/exportaciones/verificar` dan `403` a `ENCARGADO`, `EMPLEADO` y `REPRESENTANTE` y `401` sin token, y `ADMIN` pasa la capa de autorización
- [X] T058 Ampliar `app/src/test/kotlin/com/granatum/core/ValidacionFormatoIT.kt` (T008) con la familia de exportación: `GET /api/fichajes/export` sin `desde` y con `desde=no-es-fecha` dan `400 VALIDACION`, sin traza y sin el valor enviado
- [X] T059 Crear `app/src/test/kotlin/com/granatum/core/SinDatosPersonalesEnExportacionIT.kt` con el logger raíz en `DEBUG`: una exportación de la plantilla, una descarga mensual y una verificación por HTTP real. Ni el documento, ni el nombre, ni ninguna coordenada aparecen en ningún log; se excluye solo el logger del cliente del propio test (`org.springframework.web.client.*`). KDoc: la lección de la feature 002 es que en `DEBUG` Spring MVC registra cuerpos, y la verificación recibe un fichero lleno de documentos
- [X] T060 [P] Añadir `"exportaciones"` a `tablasEsperadas` en `app/src/test/kotlin/com/granatum/core/EsquemaCompletoRlsIT.kt`, y actualizar el comentario: ahora son dieciséis migraciones
- [X] T061 [P] Actualizar `README.md`: la exportación pasa a ✅; cómo exportar y cómo descargar el mes; la depuración queda **desbloqueada pero desactivada**, y cómo activarla (`timetracking.retencion.habilitada`) con el aviso de que es irreversible; las variables nuevas; y el hueco de `VALIDACION` cerrado
- [X] T062 [P] Actualizar `docs/ARCHITECTURE.md`: en *Borrado*, la depuración incluye `exportaciones` y su recuento; `PlazoConservacion` como única fuente del límite; `VALIDACION` en `CommonExceptionHandler` para toda la API
- [X] T063 [P] Actualizar la lista de **deuda declarada** de `.specify/memory/constitution.md` como enmienda PATCH (v2.0.1), con una línea de justificación. La deuda nº 2 (`HistorialMaterialRepository`) y la nº 3 (`REPRESENTANTE`) ya se cerraron en la feature 001. La nº 4 (la depuración no existe) pasa a "existe y está desbloqueada, pero desactivada hasta que se decida activarla en cada entorno". Solo la nº 1 sigue igual
- [X] T064 Recorrer `specs/003-timetracking-export/quickstart.md` **contra la aplicación en marcha**, apartado por apartado; corregir cualquier instrucción que falle; y anotar en `research.md` (D-018) la medición real de SC-009 con 100 personas sintéticas y un año *Hecho el 2026-10-07* contra la aplicación en el puerto 8081 y un Postgres temporal (el 5432 y el 8080 los ocupaba Squadfy_Backend). Los diez apartados pasan. Se corrigieron tres textos del quickstart: el apartado 3 (un `EMPLEADO` sin `empleadoId` recibe su registro, no `403`), el 6 (el aprobador sale por id con tokens de desarrollo) y el 8 (el primer error es `422`). SC-009: 0,7–0,8 s
- [X] T065 Ejecutar `./gradlew build --rerun-tasks` en verde de punta a punta y comprobar que `git status` no incluye artefactos de build ni `.env`

**Checkpoint**: feature completa, invariantes con test, documentación al día.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)**: sin dependencias.
- **Foundational (2)**: depende de Setup. **Bloquea todas las historias.**
- **US1 (3)**: depende de la fase 2. Es el MVP.
- **US2 (4)**: depende de US1, porque reutiliza su recorrido y su anotación.
- **US3 (5)**: depende de US1.
- **US4 (6)**: depende de US1; es independiente de US2 y US3.
- **US5 (7)**: depende de la fase 2 y de que exista al menos una exportación (US1).
- **Polish (8)**: depende de todas.

### Dependencias concretas

- **T008 antes de T009** y **T013 antes de T015**: los dos tests de los huecos tienen que fallar primero. Si pasan antes del arreglo, no prueban el hueco.
- **T011 antes de T012** y **antes de T027 y T030**: el límite se calcula en un solo sitio desde el principio.
- **T016 antes de T017 y T018**: el escritor y su test usan los modelos.
- **T021 antes de T030**, y **T030 nunca llama a T021 dentro de su transacción** (D-003).
- **T014 antes de T030**: el lote de correcciones usa la consulta nueva.
- **T030 antes de T035, T042 y T046**: las demás historias extienden su recorrido.
- **T052 antes de T050**: el test amplía `RetencionIT` sobre la columna nueva. Hasta que T006 y T052 estén, quedará en rojo, y es lo esperado.
- **T054 antes de T057**: la regla de `ADMIN` es lo que prueba ese test.

### Parallel Opportunities

- Fase 2: T007, T010, T016 y T019 en paralelo; T005 y T006 son ficheros distintos.
- Dentro de cada historia, sus tests marcados `[P]`.
- US3 y US4 en paralelo una vez cerrado US1.
- Fase 8: T056, T057, T060, T061, T062 y T063.

---

## Parallel Example: Phase 2

```bash
Task: "PlazoConservacionTest.kt"                 # T010
Task: "Exportacion.kt (modelos de dominio)"      # T016
Task: "ExportacionEntity.kt"                     # T019
Task: "RowLevelSecurityIT: + exportaciones"      # T007
```

---

## Implementation Strategy

### MVP (fases 1–3)

Configuración, fundación y US1: cada persona descarga su registro, coherente,
anotado y con la huella. Con eso ya se cumple la puesta a disposición del art. 34.9
para la propia persona.

### Entrega incremental

| Incremento | Fases | Qué añade |
|---|---|---|
| 1 (MVP) | 1–3 | Descarga del propio registro; los dos huecos cerrados |
| 2 | 4 | Descarga mensual: **desbloquea la depuración** (sigue apagada) |
| 3 | 5–6 | Plantilla entera para la Inspección y para la representación legal |
| 4 | 7 | Auditoría, verificación y depuración del propio registro de exportaciones |
| 5 | 8 | Tests de `app`, documentación y quickstart en marcha |

### Qué NO hace esta lista

- **No activa la depuración** en ningún entorno (FR-024). T034 fija que sigue
  apagada por defecto.
- **No arregla la fuga de DNI y ubicación en los logs** de los DTO existentes de
  `timetracking`. Es una tarea aparte ya abierta. T059 comprueba que esta feature no
  añade otra.
- **No exporta la ubicación** ni el motivo de las correcciones.

---

## Notes

- 🔍 = hueco encontrado en código existente (T008, T009, T013, T015).
- 🧬 = test validado por mutación (T026, T039). La tarea no está hecha hasta que la
  mutación se ha ejecutado y su resultado está escrito en el test.
- Comprobar que cada test falla antes de implementar lo que prueba.
- **Un test no se reescribe para que pase** si el fallo revela un comportamiento
  incorrecto del código (principio V).
- Marcar cada tarea `[X]` al terminarla.
