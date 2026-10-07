---

description: "Tareas de implementación: facturación con reconocimiento automático"
---

# Tasks: Facturación con reconocimiento automático

**Input**: Documentos de diseño de `/specs/004-invoices/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/README.md](./contracts/README.md), [quickstart.md](./quickstart.md)

**Tests**: **obligatorios** y **antes de su implementación** (principio V, y lo
pide quien encarga la feature). Cada test se ve fallar antes de escribir lo que
prueba; si pasa antes, no prueba nada.

**Organization**: por historia de usuario, en el orden del plan: US1, US2, US4,
US3, US6, US5. US4 (permisos y datos personales) va antes que los reportes para
probar la protección en cuanto existen las primeras rutas.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: paralelizable (ficheros distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece (US1…US6)
- **🕳️**: cierra uno de los **huecos encontrados al planificar** ([plan.md](./plan.md#huecos-encontrados-al-planificar))
- **🧬**: test que se **valida por mutación**: la tarea solo está hecha cuando se
  ha roto el código a propósito, el test se ha puesto rojo, se ha restaurado y
  queda escrito en el KDoc del test qué mutación detecta (lección de las features
  002 y 003).
- **🤖**: toca el reconocimiento con Claude. **Ningún test de la suite llama a la
  API real**: usan el doble `ReconocedorFalso` (D-019).

## Path Conventions

Namespace compartido `com.granatum.core`. Rutas desde la raíz:

- `features/invoices/src/main/kotlin/com/granatum/core/…` y `features/invoices/src/test/kotlin/com/granatum/core/…`
- `common/src/…`: lo que sube desde `timetracking` (D-011, D-012)
- `features/timetracking/src/…`: solo para usar lo que sube a `common`, sin cambiar comportamiento
- `app/src/…`: `SecurityConfig`, configuración y tests que necesitan la cadena real

---

## Phase 1: Setup

**Purpose**: el módulo existe, compila y la aplicación lo carga.

- [X] T001 Añadir `include("features:invoices")` a `settings.gradle.kts`, bajo las otras features. Crear `features/invoices/build.gradle.kts` con los mismos plugins que `features/timetracking/build.gradle.kts` (`java-library`, `granatum.spring-boot-service`, `kotlin("plugin.jpa")`) y las mismas dependencias de Spring, Postgres y test, más `implementation(libs.anthropic.java)` y `implementation(libs.pdfbox)`. **Ninguna dependencia de otra feature**: solo `implementation(projects.common)` (principio I)
- [X] T002 Añadir a `gradle/libs.versions.toml` `anthropic-java` (`com.anthropic:anthropic-java`, la **última versión publicada en Maven Central** el día de implementar, no una recordada), `pdfbox` (`org.apache.pdfbox:pdfbox`, 3.x) y, para los tests, `okhttp-mockwebserver` (versión alineada con la OkHttp que trae el SDK)
- [X] T003 🕳️ Hueco 3: comprobar el Jackson 2 que resuelve Gradle tras T002 con `./gradlew :app:dependencies --configuration runtimeClasspath | grep -E "com.fasterxml.jackson.core:jackson-databind"` y con `:features:invoices:dependencies`. Si el SDK necesita una versión mayor que la `2.17.0` fijada en `libs.versions.toml`, subirla ahí y comprobar que `CorreccionFlujoIT` y `ReenvioDuplicadoIT` de `timetracking` siguen en verde: su `ObjectMapper` propio lee documentos ya guardados. Anotar la versión resuelta y el resultado en `research.md` (D-002)
- [X] T004 Añadir `implementation(projects.features.invoices)` a `app/build.gradle.kts`
- [X] T005 🕳️ Hueco 2: en `app/src/main/resources/application.yml`, `spring.servlet.multipart.max-file-size` y `max-request-size` **los dos** a `${INVOICES_MAX_REQUEST_SIZE:50MB}`, con un comentario: los valores por defecto de Spring (1 MB y 10 MB) rechazarían cualquier foto de móvil, y el límite de **10 MB por fichero** no se pone aquí sino en el servicio (T037). Si Spring lo aplicara, un solo fichero grande haría rechazar la subida entera con un 413 y el resultado `DEMASIADO_GRANDE` por fichero del contrato no podría darse nunca (hallazgo I1 del análisis). Y `server.tomcat.max-swallow-size: ${INVOICES_MAX_REQUEST_SIZE:50MB}`: con el valor por defecto de Tomcat (2 MB), una petición que supera el máximo puede acabar en una conexión cortada en lugar del 413 (hallazgo T2). Y un bloque `invoices:` con `claude.api-key: ${ANTHROPIC_API_KEY:}` (comentario: **vacío = modo manual**; nunca un valor aquí, principio VI), `claude.model: ${INVOICES_CLAUDE_MODEL:claude-opus-5-5}`, `claude.effort: ${INVOICES_CLAUDE_EFFORT:medium}`, `reconocimiento.concurrencia: ${INVOICES_RECONOCIMIENTO_CONCURRENCIA:3}`, `reconocimiento.timeout-segundos: ${INVOICES_RECONOCIMIENTO_TIMEOUT_SEGUNDOS:120}`, `reconocimiento.reintento-cron`, `reconocimiento.reintento-tras-minutos: 10`, `documentos.pdf-max-paginas: 20`
- [X] T006 [P] Añadir a `.env.example` las variables nuevas, con `ANTHROPIC_API_KEY` **comentada y sin valor**, y una línea que diga que sin ella la facturación funciona en modo manual
- [X] T007 [P] Crear `features/invoices/src/test/kotlin/com/granatum/core/InvoicesTestApplication.kt` (`@SpringBootApplication` + `@EnableJpaAuditing`, como `TimetrackingTestApplication`) y `features/invoices/src/test/resources/application.yml` con `jwt.*` como en `timetracking`, los valores de `invoices.*` y **`invoices.claude.api-key: ""` fijo**, un valor literal y no `${ANTHROPIC_API_KEY:}`: el `.env` se carga en los tests de todos los módulos (`granatum.kotlin-common.gradle.kts`), y la clave no puede llegar por ahí
- [X] T008 Verificar `./gradlew build` en verde

---

## Phase 2: Fundación

**Purpose**: lo compartido sube a `common` sin cambiar `timetracking`, el esquema
existe con RLS, nada se puede borrar y las rutas ya están protegidas.

**⚠️ CRITICAL**: ninguna historia empieza antes de acabar esta fase.

### Lo que sube a `common` (D-011, D-012)

- [X] T009 [P] Crear `common/src/test/kotlin/com/granatum/core/FormatoCsvTest.kt` con los casos de celda de `features/timetracking/src/test/kotlin/com/granatum/core/EscritorCsvTest.kt`: BOM `EF BB BF`, separador `;`, CRLF, entrecomillado RFC 4180 con comillas dobladas, y anti-fórmula para `=`, `+`, `-`, `@`, tabulador y CR, sin falsos positivos en fechas y horas. Y la **celda numérica**: un importe con coma decimal (`1764,00`, `-150,00`) se escribe **sin** apóstrofo, porque un número negativo no es una fórmula y con `'` Excel lo leería como texto; cualquier cosa que no sea un decimal estricto (`-2+3`, `-150,00x`) lanza una excepción en lugar de escribirse (hallazgo I2)
- [X] T010 Crear `common/src/main/kotlin/com/granatum/core/csv/FormatoCsv.kt` con la parte genérica de `EscritorCsv` (BOM, línea, celda de texto) y una celda numérica que solo acepta `^-?\d+(,\d{1,2})?$`. Cambiar `features/timetracking/src/main/kotlin/com/granatum/core/domain/service/EscritorCsv.kt` para que la use. **`EscritorCsvTest`, `ExportacionPersonaIT` y `DescargaMensualIT` en verde sin tocar una línea**: es la prueba de que el formato no ha cambiado
- [X] T011 [P] Crear `common/src/test/kotlin/com/granatum/core/NifValidatorTest.kt`: los casos de DNI y NIE de `DocumentoIdentidadValidatorTest` de `timetracking`, más CIF válidos e inválidos con control de dígito y de letra según la letra inicial (`A`, `B`… con dígito; `P`, `Q`, `S`… con letra; `N`, `W`… con letra), y la normalización: mayúsculas, sin espacios ni guiones, sin el prefijo `ES`
- [X] T012 Crear `common/src/main/kotlin/com/granatum/core/validation/NifValidator.kt` (`normalizar`, `esDniONieValido`, `esCifValido`, `esValido`) y hacer que `features/timetracking/src/main/kotlin/com/granatum/core/api/validation/DocumentoIdentidad.kt` delegue en él. **`DocumentoIdentidadValidatorTest` y `EmpleadoIT` en verde sin cambios**

### Esquema (V17–V19), con RLS

- [X] T013 Crear `features/invoices/src/main/resources/db/migration/V17__create_empresa_and_trimestres.sql` según `data-model.md`: `empresa` con `CHECK (id = 1)`; `trimestres` con PK `(anio, trimestre)`, `CHECK (anio BETWEEN 2000 AND 2100)`, `CHECK (trimestre BETWEEN 1 AND 4)`, `cerrado BOOLEAN NOT NULL DEFAULT FALSE`; `trimestre_eventos` con `CHECK (accion IN ('CIERRE','REAPERTURA'))`, `CHECK ((accion = 'REAPERTURA') = (motivo IS NOT NULL))` y `CHECK ((accion = 'CIERRE') = (totales IS NOT NULL))`. `ENABLE ROW LEVEL SECURITY` en las tres, nunca `FORCE`
- [X] T014 Crear `features/invoices/src/main/resources/db/migration/V18__create_facturas.sql`: `facturas`, `factura_lineas_iva` y `factura_documentos` con los tipos de `data-model.md` (`NUMERIC(12,2)` en importes, `NUMERIC(5,2)` y `CHECK (tipo_iva BETWEEN 0 AND 100)` en el tipo, `CHECK (tamano BETWEEN 1 AND 10485760)`, `moneda CHAR(3) NOT NULL DEFAULT 'EUR'`, `version INTEGER NOT NULL`); el `CHECK` de una confirmada completa con `moneda = 'EUR'`; `CHECK ((estado = 'CONFIRMADA') = (confirmada_en IS NOT NULL))` y su gemelo de `DESCARTADA`; los índices únicos parciales `(documento_sha256) WHERE estado <> 'DESCARTADA'` y `(emisor_nif_normalizado, numero, fecha_emision) WHERE estado = 'CONFIRMADA'` (D-015), y los índices de consulta. RLS en las tres
- [X] T015 Crear `features/invoices/src/main/resources/db/migration/V19__create_factura_trazabilidad.sql`: `factura_reconocimientos` (`resultado` en `RECONOCIDA`, `NO_ES_FACTURA`, `VARIAS_FACTURAS`, `RECHAZADA`, `ERROR`; `error VARCHAR(200)`) y `factura_cambios` (`accion` en `CORRECCION`, `DESCARTE`, `RECLASIFICACION`; `valores_anteriores JSONB NOT NULL`). RLS en las dos
- [X] T016 [P] Crear `features/invoices/src/test/kotlin/com/granatum/core/RowLevelSecurityIT.kt` (Testcontainers) con las ocho tablas, como el de `timetracking`

### Persistencia sin borrado

- [X] T017 Crear las entidades en `features/invoices/src/main/kotlin/com/granatum/core/infrastructure/database/entities/`: `EmpresaEntity`, `TrimestreEntity` (con su clase de id), `TrimestreEventoEntity`, `FacturaEntity` (con `@Version`), `FacturaLineaIvaEntity`, `FacturaDocumentoEntity` (separada para que listar no lea los bytes), `FacturaReconocimientoEntity` y `FacturaCambioEntity`. Clases normales, nunca `data class`; identidad por id; `toString()` **sin** nombres, NIF ni importes. Comprobar que ningún nombre existe ya en otro módulo
- [X] T018 Crear sus repositorios en `features/invoices/src/main/kotlin/com/granatum/core/infrastructure/database/repositories/`, todos sobre `Repository<T, ID>`, **sin `delete`**, con un KDoc que diga por qué
- [X] T019 [P] Crear `features/invoices/src/test/kotlin/com/granatum/core/SinBorradoFacturacionIT.kt`: recorre por reflexión una lista **fija** de los ocho repositorios y falla si alguno expone `delete*` o `remove*`. Comprobar que falla añadiendo un `delete` a uno y quitándolo después

### Dominio, errores y seguridad

- [X] T020 [P] Crear el modelo de dominio en `features/invoices/src/main/kotlin/com/granatum/core/domain/model/`: `EstadoFactura`, `TipoFactura`, `CausaSinCuota`, `Parte`, `LineaIva`, `Factura`, `Aviso(campo, codigo, bloquea, mensaje)`, `PropuestaReconocida` (todos sus campos nulables, importes como `String` decimal, `camposDudosos`, `esFactura`, `variasFacturas`, `moneda`), `Periodo` (`Mensual`, `Trimestral`, `Anual`, con `desde`/`hasta`), `TotalesGrupo` y `Reporte`. Sin JPA ni HTTP. `toString()` sin datos en los que lleven nombres, NIF o importes
- [X] T021 Crear `features/invoices/src/main/kotlin/com/granatum/core/domain/exception/InvoicesExceptions.kt` y `…/api/exception_handling/InvoicesExceptionHandler.kt` (`@Order(HIGHEST_PRECEDENCE)`) con los códigos de `contracts/README.md`. Los nombres de las clases no pueden existir en otro módulo (`SinColisionDeClasesIT`)
- [X] T022 🕳️ Hueco 2: añadir a `common/src/main/kotlin/com/granatum/core/api/exception_handling/CommonExceptionHandler.kt` un manejador de `MaxUploadSizeExceededException` que responda `413 { "code": "PETICION_DEMASIADO_GRANDE", "message" }`, en lugar del error por defecto
- [X] T023 Añadir a `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt` `.requestMatchers("/api/facturacion", "/api/facturacion/**").hasRole("ADMIN")` **antes** del comodín, con un comentario que explique por qué una sola regla para todo el prefijo (D-017)
- [X] T024 Crear `EmpresaService` y `EmpresaController` (`GET`/`PUT /api/facturacion/empresa`) en `features/invoices/src/main/kotlin/com/granatum/core/…`, con el NIF validado por `NifValidator` (`422 NIF_INVALIDO`), `404 EMPRESA_SIN_CONFIGURAR` si no hay fila, y `reconocimientoActivo` según haya clave (D-006). Con su test `EmpresaIT` en `features/invoices/src/test/kotlin/com/granatum/core/` escrito antes
- [X] T025 Verificar `./gradlew build` en verde: lo de `common` y `timetracking` sin cambios de comportamiento, `RowLevelSecurityIT` y `SinBorradoFacturacionIT` en verde

**Checkpoint**: hay módulo, esquema protegido y rutas reservadas a `ADMIN`.

---

## Phase 3: User Story 1 - Subir una factura y obtener sus datos reconocidos (Priority: P1) 🎯 MVP

**Goal**: el `ADMIN` sube imágenes o PDF y obtiene borradores reconocidos, o
pendientes si el reconocimiento no está disponible.

**Independent Test**: con el doble, subir una foto y un PDF, ver que pasan de
`PENDIENTE_RECONOCER` a `BORRADOR` con lo propuesto, que lo dudoso queda vacío y
que el original es idéntico. Sin clave, que quedan pendientes y se rellenan a
mano.

### Tests for User Story 1 ⚠️

- [X] T026 [P] [US1] Crear `features/invoices/src/test/kotlin/com/granatum/core/DetectorTipoFicheroTest.kt`: JPEG, PNG, WebP y PDF reconocidos **por su firma**, aunque la extensión o el `Content-Type` mientan; un fichero vacío, uno de texto y uno **HEIC** (cabecera `ftypheic`) rechazados como `FORMATO_NO_ADMITIDO` (🕳️ hueco 1, D-007)
- [X] T027 [P] [US1] Crear `features/invoices/src/test/kotlin/com/granatum/core/PreparadorDocumentoTest.kt`: un PDF cifrado y uno de 21 páginas (generados en el test con PDFBox) se rechazan como `PDF_NO_LEGIBLE`; una imagen mayor de lo que acepta la API se reduce **solo para el envío** y el original sigue con la misma huella SHA-256. Usar una foto de móvil real de más de 5 MB (sin datos personales) como recurso de test, y decidir con ella si hace falta un lector de WebP (D-007)
- [X] T028 [P] [US1] 🤖 Crear `features/invoices/src/test/kotlin/com/granatum/core/ReconocedorClaudeTest.kt` con `MockWebServer` en lugar de la API, sin red: la petición lleva el modelo y el esfuerzo configurados, un bloque `document` para PDF o `image` con su `media_type` para imágenes, **ninguna herramienta**, `output_config.format` con el esquema, y unas instrucciones que dicen que el contenido del documento es dato y nunca orden (D-004). La petición no lleva nada más que las instrucciones fijas y el documento: ni la razón social, ni el NIF de la empresa, ni datos de otras facturas (FR-028, hallazgo C1). Una respuesta grabada **con dos tipos de IVA** se convierte en `PropuestaReconocida` con dos líneas y los importes intactos (FR-003, hallazgo C6); `stop_reason: refusal` → `RECHAZADA` con su categoría; `max_tokens`, 5xx y tiempo agotado → `ERROR` sin contenido del documento en el mensaje. Los tokens y el modelo de la respuesta se devuelven para guardarlos (D-009)
- [X] T029 [P] [US1] 🤖 Crear `features/invoices/src/test/kotlin/com/granatum/core/ReconocedorFalso.kt`: el doble de `ReconocedorFacturas`, guionizable por test (propuesta correcta, con campos dudosos, "no es factura", "varias facturas", error, espera controlada con un latch), y que **anota si se le llamó con una transacción activa** (`TransactionSynchronizationManager.isActualTransactionActive()`)
- [X] T030 [P] [US1] 🤖 Crear `features/invoices/src/test/kotlin/com/granatum/core/SubidaFacturasIT.kt` (Testcontainers + doble): varios ficheros dan un resultado por fichero, en orden y sin nombres; uno de 11 MB entre otros válidos sale `DEMASIADO_GRANDE` y los demás `ACEPTADA` (hallazgo I1); una propuesta con dos tipos de IVA deja dos líneas en el borrador; el mismo fichero dos veces → `DUPLICADA` con el id de la primera; **dos subidas simultáneas del mismo fichero dejan una sola factura** (el índice único, D-015); el original guardado es idéntico byte a byte; la factura pasa de `PENDIENTE_RECONOCER` a `BORRADOR` con lo propuesto; lo dudoso queda vacío; "no es factura" deja un borrador sin cifras; un error la deja pendiente con el intento anotado en `factura_reconocimientos`; y **el doble nunca se llamó dentro de una transacción** (D-005). 🧬 Validar por mutación anotando el reconocimiento con `@Transactional`: el test tiene que ponerse rojo
- [X] T031 [P] [US1] Crear `features/invoices/src/test/kotlin/com/granatum/core/ModoManualIT.kt`: sin clave no se construye el cliente real, `GET empresa` dice `reconocimientoActivo: false`, la subida funciona y la factura queda pendiente, `POST …/reconocer` → `409 RECONOCIMIENTO_NO_DISPONIBLE`, y rellenarla a mano la lleva a `BORRADOR` (FR-006, SC-008). *Al implementar*: la parte de rellenar a mano se comprueba en T044, cuando ya existe la corrección
- [X] T032 [P] [US1] Crear `features/invoices/src/test/kotlin/com/granatum/core/ReintentoReconocimientoIT.kt`: una factura pendiente más antigua que `reintento-tras-minutos` (insertada por JDBC) la retoma el proceso programado; una reciente, no; y un fallo vuelve a dejarla pendiente sin perderla. *Al implementar*: como mucho **tres** intentos por factura, para no pagar indefinidamente uno que siempre falla (research.md D-005)

### Implementation for User Story 1

- [X] T033 [P] [US1] Crear `features/invoices/src/main/kotlin/com/granatum/core/infrastructure/documentos/DetectorTipoFichero.kt` (firmas de JPEG, PNG, WebP y PDF; nada más)
- [X] T034 [P] [US1] Crear `features/invoices/src/main/kotlin/com/granatum/core/infrastructure/documentos/PreparadorDocumento.kt` con PDFBox para cifrado y páginas, y la reducción de imágenes solo para el envío
- [X] T035 [P] [US1] Crear `features/invoices/src/main/kotlin/com/granatum/core/domain/port/AlmacenDocumentos.kt` y su implementación `…/infrastructure/database/AlmacenDocumentosPostgres.kt` (D-008)
- [X] T036 [US1] Crear `features/invoices/src/main/kotlin/com/granatum/core/domain/port/ReconocedorFacturas.kt` y `…/infrastructure/claude/ReconocedorClaude.kt` con el SDK oficial: cliente construido **solo si `invoices.claude.api-key` no está vacía**, y siempre con la clave **explícita** (`AnthropicOkHttpClient.builder().apiKey(clave)`), **nunca con `fromEnv()` ni sin clave**: el SDK busca credenciales por su cuenta (`ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` y el perfil de `ant auth login` en disco), y un cliente así podría autenticarse en un entorno o un test sin clave configurada (hallazgo S1; KDoc con el porqué). Sin clave, el bean es un `ReconocedorDeshabilitado` (modo manual, D-006); `claude-opus-5-5` y esfuerzo `medium` desde configuración; sin `thinking` explícito (en Opus 5.5 es adaptativo y no se desactiva); salida estructurada derivada de la clase de propuesta; sin herramientas; `max_tokens` 16000; respaldo del servidor `fallbacks: "default"` con su cabecera beta (D-005), **comprobando antes** que es compatible con la salida estructurada y cómo se expresa con el SDK de Java; si no lo es, se prescinde de él y se anota en D-005 (hallazgo U1); instrucciones del sistema en `features/invoices/src/main/resources/prompts/reconocimiento-factura.md`, versionadas y revisables. Si un nombre del SDK no está en la documentación del skill `claude-api`, compilar y dejar que el error lo indique, sin inventarlo
- [X] T037 [US1] Crear `features/invoices/src/main/kotlin/com/granatum/core/service/SubidaFacturas.kt`: valida cada fichero (vacío, más de 10 MB → `DEMASIADO_GRANDE` **solo para ese fichero**, tipo, PDF), calcula la huella, guarda factura y original en una transacción y **encola el reconocimiento después del commit** (`TransactionSynchronization.afterCommit`), nunca dentro
- [X] T038 [US1] Crear `features/invoices/src/main/kotlin/com/granatum/core/service/ColaReconocimiento.kt`: ejecutor con `concurrencia` hilos y un semáforo; llama al reconocedor **sin transacción** y escribe el resultado en una transacción corta y nueva (propuesta a `factura_reconocimientos`, campos al borrador). KDoc con D-005 y las lecciones de 002 y 003
- [X] T039 [US1] Crear `features/invoices/src/main/kotlin/com/granatum/core/scheduling/ReintentoReconocimientoJob.kt`
- [X] T040 [US1] Crear `features/invoices/src/main/kotlin/com/granatum/core/api/controllers/FacturaController.kt` con `POST /api/facturacion/facturas` (multipart, parte `ficheros`, `202` con un resultado por fichero), `GET /api/facturacion/facturas/{id}` y `POST …/{id}/reconocer`, y los DTO en `…/api/dto/FacturacionDtos.kt` con `toString()` seguro
- [X] T041 [US1] Verificar US1 con `./gradlew :features:invoices:test`

**Checkpoint**: subir funciona, con y sin clave; el MVP de entrada de facturas.

---

## Phase 4: User Story 2 - Revisar, corregir y confirmar una factura (Priority: P1)

**Goal**: nada se confirma sin cuadrar, sin NIF válido o duplicado; y lo
confirmado tiene historial.

**Independent Test**: un borrador con el total mal leído muestra `NO_CUADRA`, no
se confirma; corregido, sí; y queda el historial.

### Tests for User Story 2 ⚠️

- [X] T042 [P] [US2] Crear `features/invoices/src/test/kotlin/com/granatum/core/ValidadorFacturaTest.kt` (unitario): cada código de aviso de `contracts/README.md` y si bloquea; la tolerancia de 0,01 € (cuadra con 0,01 de diferencia, no con 0,02); una rectificativa admite negativos y una normal no; la simplificada sin destinatario se acepta; cuota 0 sin causa bloquea; moneda distinta de `EUR` bloquea; fecha futura y de hace más de cuatro años solo avisan; `DUDOSO` solo avisa
- [X] T043 [P] [US2] Crear `features/invoices/src/test/kotlin/com/granatum/core/ClasificadorFacturaTest.kt` (unitario): emitida y recibida por el NIF normalizado de la empresa (con `ES`, espacios y guiones), y `NO_ES_DE_LA_EMPRESA` si no coincide ninguno (D-014)
- [X] T044 [P] [US2] Crear `features/invoices/src/test/kotlin/com/granatum/core/RevisionFacturasIT.kt`: corregir un borrador; confirmar con avisos bloqueantes → `FACTURA_INCOHERENTE`; confirmar bien → `CONFIRMADA`, `tipo` deducido y `confirmada_por`; sin empresa → `EMPRESA_SIN_CONFIGURAR`; versión antigua → `VERSION_DESACTUALIZADA`; descartar conserva el original y la consulta, y anota `descartada_por` y `descartada_en` (FR-017, hallazgo C4); cambiar una confirmada de emitida a recibida deja una `RECLASIFICACION` en `factura_cambios` (FR-008, hallazgo C3); corregir una confirmada deja `valores_anteriores` completos en `factura_cambios` y nunca la deja incompleta; y la propuesta original sigue intacta en `factura_reconocimientos` después de corregir (FR-005)
- [X] T045 [P] [US2] 🧬 Crear `features/invoices/src/test/kotlin/com/granatum/core/DuplicadoLogicoIT.kt`: dos fotos distintas de la misma factura (mismo emisor, número y fecha) no pueden quedar las dos confirmadas, tampoco confirmándolas a la vez desde dos hilos. Validar por mutación: quitar la comprobación del servicio (la base de datos sigue impidiéndolo, test en verde con el error traducido a `FACTURA_DUPLICADA`) y quitar el índice único (test en rojo). Anotar las dos en el KDoc. *Al implementar*: quitar solo el índice deja el test **en verde**, porque el bloqueo de fila del trimestre ya serializa las confirmaciones de la misma fecha. Se validó en tres capas: sin la comprobación, verde; sin el índice, verde; sin índice ni bloqueo, **rojo**. Cada garantía aguanta sola

### Implementation for User Story 2

- [X] T046 [P] [US2] Crear `features/invoices/src/main/kotlin/com/granatum/core/domain/service/ValidadorFactura.kt` (puro: factura + empresa + fecha de hoy → avisos)
- [X] T047 [P] [US2] Crear `features/invoices/src/main/kotlin/com/granatum/core/domain/service/ClasificadorFactura.kt`
- [X] T048 [US2] Crear `features/invoices/src/main/kotlin/com/granatum/core/service/RevisionFacturas.kt` (corregir, confirmar, descartar, reclasificar) que, antes de cambiar una factura o de confirmarla, bloquee la fila de su trimestre con `SELECT … FOR UPDATE` (creándola si no existe) y rechace `TRIMESTRE_CERRADO` (D-016). Traducir la violación de los índices únicos a `FACTURA_DUPLICADA`
- [X] T049 [US2] Añadir a `FacturaController.kt` `PUT /api/facturacion/facturas/{id}`, `POST …/confirmar` y `POST …/descartar`, y los `avisos` en el `GET`
- [X] T050 [US2] Verificar US2 con `./gradlew :features:invoices:test`

**Checkpoint**: entrada completa de facturas, con garantías de cuadre y de duplicados.

---

## Phase 5: User Story 4 - Solo quien administra ve las facturas (Priority: P2)

**Goal**: ninguna otra persona llega a las facturas, el texto de una factura no
da órdenes y nada acaba en los logs.

**Independent Test**: cada ruta con cada rol distinto de `ADMIN` da `403`, y sin
token `401`.

### Tests for User Story 4 ⚠️

- [X] T051 [P] [US4] Crear `app/src/test/kotlin/com/granatum/core/AutorizacionFacturacionIT.kt` (puerto real): **todas** las rutas de `contracts/README.md`, incluida la subida multipart, con `ENCARGADO`, `EMPLEADO` y `REPRESENTANTE` → `403`, sin token → `401`, y `ADMIN` pasa la autorización (ni `401` ni `403`) (SC-005)
- [X] T052 [P] [US4] 🤖 Crear `features/invoices/src/test/kotlin/com/granatum/core/InyeccionInstruccionesIT.kt`: el doble devuelve una propuesta "envenenada" (total 0, un NIF ajeno, otro concepto), como haría una factura con instrucciones en su texto. Afirmar que solo cambian los campos del borrador de **esa** factura: no se confirma sola, y ni la empresa, ni otras facturas, ni los trimestres cambian. Y en `ReconocedorClaudeTest` (T028), que la petición no lleva herramientas (FR-007, D-004)
- [X] T053 [P] [US4] 🧬 Crear `app/src/test/kotlin/com/granatum/core/SinDatosPersonalesEnFacturacionIT.kt` con el logger raíz en `DEBUG`: subir, reconocer con el doble, corregir, confirmar, pedir el reporte en CSV y PDF y descargar el original de una factura con un nombre, un NIF y un importe distintivos. Ninguno aparece en ningún log del servidor (FR-027, D-018). Validar por mutación con un `log.debug` del NIF en el servicio. *Al implementar*: los reportes aún no existen en esta fase, así que el CSV y el PDF se añaden a este test en T064
- [X] T054 [P] [US4] 🕳️ Crear `app/src/test/kotlin/com/granatum/core/SinLlamadasRealesEnTestsIT.kt`: el `.env` se carga en los tests (`DotEnv.kt`), así que un `ANTHROPIC_API_KEY` puesto ahí haría que la suite llamara a la API real y gastara dinero en cada build. Con `ANTHROPIC_API_KEY` definida a un valor falso, el contexto de test **no** construye el cliente real: el bean de `ReconocedorFacturas` es el deshabilitado. Para ello, crear `app/src/test/resources/config/application.yml` con `invoices.claude.api-key: ""` y nada más. **No** `app/src/test/resources/application.yml`: ese nombre sustituiría entero el de producción, mientras que `config/application.yml` se fusiona con él y le gana (hallazgo S2). Los tests de `app` funcionan en modo manual; el que necesite reconocer (T053) importa el doble con `@Import`

### Implementation for User Story 4

- [X] T055 [US4] Corregir lo que hayan encontrado T051–T054 y verificar con `./gradlew :app:test --tests "*Facturacion*" --tests "*SinLlamadasReales*"`

**Checkpoint**: la protección está probada antes de que existan los reportes.

---

## Phase 6: User Story 3 - Reportes por periodo (Priority: P2)

**Goal**: totales exactos por mes, trimestre y año, en pantalla, CSV y PDF.

**Independent Test**: con facturas de importes conocidos, el reporte trimestral
coincide al céntimo con la suma manual, y el CSV y el PDF con la pantalla.

### Tests for User Story 3 ⚠️

- [X] T056 [P] [US3] Crear `features/invoices/src/test/kotlin/com/granatum/core/CalculadoraReporteTest.kt` (unitario): sumas exactas con `BigDecimal`, IVA por tipo, recargo, retenciones, rectificativas que restan, `sinCuota` por causa, `pendientes`, y que tres mensuales suman el trimestral y cuatro trimestrales el anual (FR-020)
- [X] T057 [P] [US3] Crear `features/invoices/src/test/kotlin/com/granatum/core/ReportesFacturacionIT.kt`: solo cuentan las confirmadas; los límites de trimestre (31 de marzo en el 1T, 1 de abril en el 2T); `PERIODO_INVALIDO` y `VALIDACION` según `contracts/README.md`; y 100 facturas con céntimos que suman exacto (SC-004)
- [X] T058 [P] [US3] Crear `features/invoices/src/test/kotlin/com/granatum/core/EscritorReporteCsvTest.kt`: usa `FormatoCsv`, importes con coma decimal en celdas numéricas, un total **negativo** por rectificativas que sale `-150,00` y no `'-150,00` (hallazgo I2), las mismas cifras que el JSON, y nombre de fichero `reporte-facturacion_2026-T3.csv`
- [X] T059 [P] [US3] Crear `features/invoices/src/test/kotlin/com/granatum/core/EscritorReportePdfTest.kt`: genera el PDF, extrae su texto con PDFBox y comprueba que cada cifra coincide con la del reporte JSON y el CSV (SC-010)

### Implementation for User Story 3

- [X] T060 [US3] Añadir a los repositorios las consultas agregadas por grupo y tipo de IVA, sumando en SQL sobre `NUMERIC` (D-013). Si un filtro es opcional, usar SQL nativo con `CAST` explícito (lección de la feature 003). *Al implementar*: la consulta carga las facturas confirmadas del periodo y la suma la hace `CalculadoraReporte` con `BigDecimal`, igual de exacta y única fuente de todas las cifras (research.md D-013)
- [X] T061 [P] [US3] Crear `features/invoices/src/main/kotlin/com/granatum/core/domain/service/CalculadoraReporte.kt`
- [X] T062 [US3] Crear `features/invoices/src/main/kotlin/com/granatum/core/service/ReportesFacturacion.kt`
- [X] T063 [P] [US3] Crear `features/invoices/src/main/kotlin/com/granatum/core/infrastructure/reportes/EscritorReporteCsv.kt` y `EscritorReportePdf.kt` (PDFBox, D-010)
- [X] T064 [US3] Crear `features/invoices/src/main/kotlin/com/granatum/core/api/controllers/ReporteFacturacionController.kt` (`GET /api/facturacion/reportes`, `formato` `json`, `csv` o `pdf`, con su `Content-Disposition`)
- [X] T065 [US3] Verificar US3 con `./gradlew :features:invoices:test`

**Checkpoint**: reportes exactos en los tres formatos.

---

## Phase 7: User Story 6 - Cerrar y reabrir un trimestre (Priority: P2)

**Goal**: un trimestre declarado no cambia sin que alguien lo reabra, con motivo.

**Independent Test**: cerrar, intentar corregir (rechazado), reabrir con motivo,
corregir y cerrar, y ver los tres eventos.

### Tests for User Story 6 ⚠️

- [ ] T066 [P] [US6] Crear `features/invoices/src/test/kotlin/com/granatum/core/TrimestresIT.kt`: cerrar con pendientes → `TRIMESTRE_CON_PENDIENTES`; cerrar bien guarda la instantánea, que coincide con el reporte; confirmar, corregir o descartar una factura del trimestre → `TRIMESTRE_CERRADO`; una **subida** con fecha de un trimestre cerrado sí entra, como borrador (D-016); reabrir sin motivo → `VALIDACION`; con motivo, sí; reabrir uno abierto → `TRIMESTRE_ABIERTO`; el reporte mensual de un mes del trimestre cerrado y el anual que lo contiene indican que está cerrado y desde cuándo (FR-033, hallazgo C5); y el historial en orden (SC-009)
- [ ] T067 [P] [US6] 🧬 Crear `features/invoices/src/test/kotlin/com/granatum/core/CierreConcurrenteIT.kt` con el cruce **forzado**, no dejado al azar (hallazgo T1): una sonda (`SondaConcurrencia`, que en producción no hace nada) detiene la confirmación con un latch justo después de comprobar que el trimestre está abierto; mientras está detenida, otro hilo intenta cerrar el trimestre; al soltarla, nunca puede quedar una factura confirmada en un trimestre cerrado que no esté en su instantánea. Con el bloqueo, el cierre espera a la confirmación. Validar por mutación quitando el `FOR UPDATE`: el test tiene que ponerse rojo de forma determinista, no a veces

### Implementation for User Story 6

- [ ] T068 [US6] Crear `features/invoices/src/main/kotlin/com/granatum/core/service/Trimestres.kt` (estado, cerrar con instantánea, reabrir con motivo; mismo bloqueo de fila que `RevisionFacturas`) y la interfaz `SondaConcurrencia` con su implementación vacía, llamada desde `RevisionFacturas` tras comprobar el trimestre. KDoc: existe solo para que T067 pueda forzar el cruce
- [ ] T069 [US6] Crear `features/invoices/src/main/kotlin/com/granatum/core/api/controllers/TrimestreController.kt` y añadir `trimestresCerrados` al reporte (FR-033)
- [ ] T070 [US6] Verificar US6 con `./gradlew :features:invoices:test`

**Checkpoint**: las cifras declaradas quedan fijas.

---

## Phase 8: User Story 5 - Consultar las facturas y recuperar el original (Priority: P3)

**Goal**: encontrar cualquier factura y justificar cualquier cifra con su documento.

**Independent Test**: filtrar por cada criterio y descargar un original idéntico.

### Tests for User Story 5 ⚠️

- [ ] T071 [P] [US5] Crear `features/invoices/src/test/kotlin/com/granatum/core/ConsultaFacturasIT.kt`: filtros `desde`/`hasta`, `parte` (nombre o NIF, sin distinguir mayúsculas), `tipo` y `estado`, combinables; orden por fecha descendente con las que no tienen fecha primero; paginación; el original idéntico byte a byte con `Content-Disposition: attachment; filename="factura-<id>.<ext>"` (nunca el nombre original); y el historial con reconocimientos y cambios

### Implementation for User Story 5

- [ ] T072 [US5] Añadir la consulta filtrada (SQL nativo con `CAST` en los filtros opcionales) y `GET /api/facturacion/facturas`, `GET …/{id}/original` y `GET …/{id}/historial` a `FacturaController.kt`
- [ ] T073 [US5] Verificar US5 con `./gradlew :features:invoices:test`

---

## Phase 9: Pulido

- [ ] T074 [P] Añadir las ocho tablas a `tablasEsperadas` en `app/src/test/kotlin/com/granatum/core/EsquemaCompletoRlsIT.kt` y actualizar el comentario: diecinueve migraciones, cuatro features
- [ ] T075 [P] Comprobar que `app/src/test/kotlin/com/granatum/core/SinColisionDeClasesIT.kt` recorre también el módulo nuevo, y ampliarlo si tiene una lista fija
- [ ] T076 [P] Ampliar `app/src/test/kotlin/com/granatum/core/ValidacionFormatoIT.kt`: el reporte sin `anio` y la subida sin ficheros dan `400 VALIDACION`, sin traza; y una petición de 60 MB **recibe de verdad** `413 PETICION_DEMASIADO_GRANDE` por HTTP, sin conexión cortada (hallazgo T2)
- [ ] T077 [P] 🤖 Crear `features/invoices/src/test/kotlin/com/granatum/core/PrecisionReconocimientoIT.kt` etiquetado `@Tag("claude-real")` y **excluido de `test`** en `features/invoices/build.gradle.kts` (`useJUnitPlatform { excludeTags("claude-real") }`), con una tarea aparte `claudeRealTest`. Solo corre con `ANTHROPIC_API_KEY` y `INVOICES_REFERENCIA_DIR` (facturas reales con su JSON esperado, que **no** se versionan). Mide el porcentaje de campos obligatorios correctos (SC-002, ≥ 90%), que ningún campo se invente, el tiempo por factura (SC-001) y los tokens y el coste real; incluye un documento con instrucciones dirigidas a la IA (FR-007). Si faltan la clave o el directorio, se salta y dice por qué
- [ ] T078 [P] Actualizar `README.md`: la facturación en el estado del proyecto; las rutas; las variables nuevas; que sin `ANTHROPIC_API_KEY` funciona en modo manual; el coste estimado; el aviso de Supabase (D-008); y la condición de protección de datos (Anthropic como encargado del tratamiento)
- [ ] T079 [P] Actualizar `docs/ARCHITECTURE.md`: el módulo `features/invoices`; la integración con Claude (asíncrona, fuera de transacción, salida estructurada, sin herramientas); `FormatoCsv` y `NifValidator` en `common`; la numeración V17–V19
- [ ] T080 Recorrer `specs/004-invoices/quickstart.md` contra la aplicación en marcha: apartados 1–8 sin clave; el 9 solo si hay clave y facturas reales. **Si no las hay, SC-001 y SC-002 quedan sin verificar y el informe final lo dice así**, sin darlos por cumplidos (hallazgo C2). Corregir el quickstart donde no coincida con la realidad
- [ ] T081 Ejecutar `./gradlew build --rerun-tasks` en verde y comprobar que `git status` no incluye artefactos, `.env` ni ninguna factura real

**Checkpoint**: feature completa, invariantes con test, documentación al día.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)** → **Fundación (2)** → historias.
- **US1 (3)** antes que todo lo demás: sin facturas subidas no hay nada que revisar.
- **US2 (4)** depende de US1.
- **US4 (5)** depende de que existan las rutas de US1 y US2.
- **US3 (6)** depende de US2: los reportes solo cuentan confirmadas.
- **US6 (7)** depende de US3 (la instantánea es un reporte) y de US2 (el bloqueo
  de fila ya está en `RevisionFacturas`).
- **US5 (8)** solo depende de US1 y US2; puede ir en paralelo con US3 y US6.
- **Pulido (9)** al final.

### Within Each User Story

- Los tests primero, y fallando. Después el dominio puro, los servicios, los
  controladores y la verificación.
- **T010 y T012**: lo que sube a `common` no puede cambiar ningún test de
  `timetracking`. Si uno cambia, se ha cambiado comportamiento.
- **T029 antes que T030–T032, T044, T052 y T053**: todos usan el doble.
- **T037 y T038**: el reconocimiento **nunca** dentro de una transacción (T030 lo
  vigila).

### Parallel Opportunities

- Fase 2: T009, T011, T016 y T020 en paralelo; T013–T015 son ficheros distintos.
- US1: T026–T032 en paralelo, y T033–T035.
- US4: sus cuatro tests en paralelo.
- US3: T056–T059 en paralelo, y T061 con T063.
- US5 en paralelo con US3 y US6.

---

## Parallel Example: User Story 1

```bash
Task: "DetectorTipoFicheroTest.kt"   # T026
Task: "PreparadorDocumentoTest.kt"   # T027
Task: "ReconocedorClaudeTest.kt"     # T028
Task: "ReconocedorFalso.kt"          # T029
```

---

## Implementation Strategy

### MVP (fases 1–4)

Setup, fundación, US1 y US2: las facturas entran, se reconocen (o se rellenan a
mano), se revisan y se confirman con garantías. Con eso ya existe el archivo de
facturas fiable sobre el que se construye todo lo demás.

### Entrega incremental

| Incremento | Fases | Qué añade |
|---|---|---|
| 1 (MVP) | 1–4 | Subir, reconocer, revisar y confirmar |
| 2 | 5 | Permisos, inyección de instrucciones y logs, probados |
| 3 | 6 | Reportes en pantalla, CSV y PDF |
| 4 | 7 | Cierre de trimestres |
| 5 | 8 | Consulta y originales |
| 6 | 9 | Documentación, quickstart y medición real |

### Qué NO hace esta lista

- **No llama a la API real en la suite.** Solo `claudeRealTest`, a mano, con clave.
- **No guarda la clave** en ningún fichero versionado.
- **No versiona facturas reales**: las de referencia viven fuera del repositorio.
- **No borra facturas**: no hay depuración en esta feature.

---

## Notes

- 🕳️ = hueco encontrado al planificar (T003, T005, T022, T026, T054).
- 🧬 = test validado por mutación (T030, T045, T053, T067).
- **Análisis del 2026-10-08**: 14 hallazgos (4 altos: S1, S2, I1, I2), todos incorporados a estas tareas, al contrato, a `research.md` y al plan.
- 🤖 = toca el reconocimiento; en la suite siempre con el doble.
- **Un test no se reescribe para que pase** si el fallo revela un comportamiento
  incorrecto del código (principio V).
- Marcar cada tarea `[X]` al terminarla.
