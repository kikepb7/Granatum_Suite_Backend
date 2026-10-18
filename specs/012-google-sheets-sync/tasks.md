---
description: "Tareas de la feature 012: copia en Google Sheets"
---

# Tasks: Copia del registro de jornada y de las facturas en Google Sheets

**Input**: `specs/012-google-sheets-sync/` (plan.md, spec.md, research.md D-001…D-009,
data-model.md, contracts/README.md, quickstart.md)

**Tests**: requeridos (research D-009). En cada fase, los tests se escriben antes
que la implementación y deben fallar primero.

**Rutas**: `SH = features/sheets/src/main/kotlin/com/granatum/core`,
`SHT = features/sheets/src/test/kotlin/com/granatum/core`,
`TT = features/timetracking/src/main/kotlin/com/granatum/core`,
`TTT = features/timetracking/src/test/kotlin/com/granatum/core`,
`AU = features/auth/src/main/kotlin/com/granatum/core`,
`IN = features/invoices/src/main/kotlin/com/granatum/core`,
`INT = features/invoices/src/test/kotlin/com/granatum/core`,
`CM = common/src/main/kotlin/com/granatum/core`.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [X] T001 Añadir al catálogo `gradle/libs.versions.toml`:
  - `google-api-services-sheets` `v4-rev20250603-2.0.0`
  - `google-auth-library-oauth2-http` `1.48.0`
  - `google-http-client-gson` `1.47.0`

  Antes, comprobar en Maven Central que son las últimas estables y ajustar si
  hay otras.

  **Hecho (2026-10-18)**: las últimas estables eran `v4-rev20260930-2.0.0`,
  `1.54.0` y `2.2.0`, y son las fijadas. Se fija también `google-api-client`
  `2.9.1`: el artefacto de Sheets aún declara la 2.7.2, compilada contra
  `google-http-client` 1.x, y la librería de autenticación trae la 2.x.
- [X] T002 Crear el módulo `features/sheets`:
  - `features/sheets/build.gradle.kts` igual que `features/invoices/build.gradle.kts`
    (plugins `java-library`, `granatum.spring-boot-service`, `kotlin("plugin.jpa")`);
    `implementation(projects.common)` como única dependencia de proyecto, más las
    tres librerías de T001, `okhttp` y `mockwebserver` para test;
  - `test` excluye el tag `sheets-real`, y la tarea `sheetsRealTest` lo incluye,
    copiando el patrón de `claudeRealTest`;
  - `include("features:sheets")` en `settings.gradle.kts`.
- [X] T003 Añadir `implementation(projects.features.sheets)` en `app/build.gradle.kts`.
- [X] T004 [P] Crear `SHT/SheetsTestApplication.kt` (`@SpringBootApplication
  @EnableJpaAuditing`, como `InvoicesTestApplication`) y
  `features/sheets/src/test/resources/application.yml` con Testcontainers,
  Flyway, `ddl-auto=validate`, `sheets.google.credentials-file: ""` y
  `sheets.worker.cron: "0 0 4 1 1 *"`.

---

## Phase 2: Foundational

**⚠️ Bloquea US4, US2 y US3** (US1 no depende de esta fase).

- [X] T005 Crear `features/sheets/src/main/resources/db/migration/V37__create_sheets_tables.sql`:
  - **`sheet_settings`**:
    - `id SMALLINT PK CHECK (id = 1)`;
    - `shifts_spreadsheet_id VARCHAR(100) NULL`, `invoices_spreadsheet_id VARCHAR(100) NULL`,
      con `CHECK (shifts_spreadsheet_id IS DISTINCT FROM invoices_spreadsheet_id OR shifts_spreadsheet_id IS NULL)`;
    - `configured_by UUID NOT NULL`, `configured_at TIMESTAMPTZ NOT NULL`, `version BIGINT NOT NULL`.
  - **`sheet_tasks`**:
    - `id UUID PK`;
    - `kind VARCHAR(20) CHECK IN ('SHIFT_MONTH','INVOICE','INVOICE_MONTH')`;
    - `reference VARCHAR(64) NOT NULL`;
    - `status VARCHAR(20) CHECK IN ('PENDING','RUNNING','DONE','FAILED')`;
    - `attempts INT NOT NULL DEFAULT 0`, `next_attempt_at TIMESTAMPTZ NOT NULL`;
    - `last_error VARCHAR(300) NULL`, `requested_by UUID NULL`;
    - `row_count INT NULL`, `last_names_pending INT NULL`, `repeat_requested BOOLEAN NOT NULL DEFAULT false`;
    - `created_at`, `updated_at` y `completed_at`, todas `TIMESTAMPTZ`;
    - índice único parcial `(kind, reference) WHERE status IN ('PENDING','RUNNING')`;
    - índice `(status, next_attempt_at)`.
  - **`sheet_invoice_rows`**: `invoice_id UUID PK`, `spreadsheet_id VARCHAR(100) NOT NULL`,
    `tab VARCHAR(7) NOT NULL`, `written_at TIMESTAMPTZ NOT NULL`.
  - `ENABLE ROW LEVEL SECURITY` en las tres, sin políticas ni `FORCE` (patrón de `V15`).
- [X] T006 [P] Crear `SHT/RowLevelSecurityIT.kt` con `expectedTables`, las tres
  tablas y la comprobación de `relrowsecurity`, copiando el de invoices.
- [X] T007 [P] Crear las entidades y repositorios JPA en `SH/infrastructure/database/`:
  - entidades: `SheetSettingsEntity`, `SheetTaskEntity`, `SheetInvoiceRowEntity`;
  - repositorios: `SheetSettingsRepository`, `SheetTaskRepository` con:
    - `claimDue(limit)` nativa con `FOR UPDATE SKIP LOCKED` sobre
      `status='PENDING' AND next_attempt_at <= now()`, ordenada por `next_attempt_at`;
    - `upsertPending(kind, reference, requestedBy)` nativa con
      `INSERT … ON CONFLICT (kind, reference) WHERE status IN ('PENDING','RUNNING') DO UPDATE`:
      si la existente está `PENDING`, reinicia `attempts=0` y `next_attempt_at=now()`;
      si está `RUNNING`, marca `repeat_requested=true`. Devuelve el id;
    - `findStaleRunning(before)`;
  - `SheetInvoiceRowRepository`.
- [X] T008 [P] Crear el puerto `SH/domain/port/SheetsClient.kt`:
  - operaciones: `serviceAccountEmail()`, `checkAccess(spreadsheetId)`,
    `rewriteTab(spreadsheetId, tab, header, rows)`,
    `upsertInvoiceRow(spreadsheetId, tab, invoiceId, row)`,
    `removeInvoiceRow(spreadsheetId, tab, invoiceId)`;
  - excepciones `SheetsPermanentException(reason)` (`NOT_FOUND`, `NOT_SHARED`,
    `READ_ONLY`, `BAD_REQUEST`) y `SheetsTransientException`;
  - `DisabledSheetsClient`, que lanza `SheetsUnavailableException`.

  Las celdas son `String` o `BigDecimal`, para escribir con `RAW`.
- [X] T009 [P] Crear `SHT/FakeSheetsClient.kt`, un fake scriptable y en memoria:
  - pestañas con sus filas, peticiones registradas y fallos programables por
    llamada;
  - se importa con `@Import(WithFakeSheetsClient::class)` y `@Bean @Primary`.
- [X] T010 Crear `SHT/GoogleSheetsClientTest.kt` con OkHttp `MockWebServer`.
  Comprueba con `sheets.google.root-url` y `token-url` apuntando al servidor:
  - token con alcance `https://www.googleapis.com/auth/spreadsheets`;
  - `rewriteTab`: `get` con `fields=sheets.properties`, luego `addSheet` solo si
    falta, luego `values:clear` `'2026-10'!A:Z`, luego `values.update` con
    `valueInputOption=RAW`;
  - `upsertInvoiceRow` localiza el id en la columna A y actualiza esa fila, o
    hace `append` si no está;
  - `removeInvoiceRow` usa `deleteDimension` con el `sheetId` correcto;
  - errores: 403 → `NOT_SHARED`, 404 → `NOT_FOUND`, 429 y 503 → transitorio;
  - `checkAccess`: 403 en el `batchUpdate` de título → `READ_ONLY`.

  Usa un JSON de cuenta de servicio de prueba generado en el test, con una clave
  RSA efímera.
- [X] T011 Implementar `SH/infrastructure/google/`:
  - **`SheetsGoogleSettings`**: `@Value` de `sheets.google.credentials-file`
    (`${GOOGLE_SHEETS_CREDENTIALS_FILE:}`), `root-url` y `token-url` (vacíos =
    los de Google); `active` si el fichero no es vacío; `toString` sin ruta ni
    contenido.
  - **`GoogleSheetsClient`**:
    - credenciales con `ServiceAccountCredentials.fromStream(...)
      .createScoped(SheetsScopes.SPREADSHEETS)`, **nunca** `getApplicationDefault()`;
    - el correo sale de `clientEmail`;
    - clasificación de errores según T010;
    - la columna A de recibos queda oculta y protegida al crear la pestaña.
  - **`SheetsClientConfig`**: elige `GoogleSheetsClient` o `DisabledSheetsClient`
    y avisa una vez en el log si está desactivado. Si el fichero no existe o no se
    puede leer, falla el arranque con un mensaje claro sin mostrar el contenido.

  Hacer pasar T010.
- [X] T012 Escribir `SHT/SheetTaskQueueIT.kt`:
  - `upsertPending` dos veces seguidas deja una sola tarea `PENDING`;
  - sobre una `RUNNING`, marca `repeat_requested`;
  - una `FAILED` no impide crear una nueva.
- [X] T013 Implementar `SH/service/SheetTaskQueue.kt` (`enqueue(kind, reference, requestedBy)`,
  `retry(id)`, `retryAllFailed()`) y hacer pasar T012.
- [X] T014 Escribir `SHT/SheetTaskWorkerIT.kt` con `FakeSheetsClient`:
  - una tarea vencida se ejecuta y queda `DONE` con `completed_at`;
  - error transitorio: `attempts+1` y `next_attempt_at = now + min(2^attempts, 60) min`;
  - tras `sheets.max-attempts` (8) queda `FAILED`;
  - error definitivo: `FAILED` directamente, con `last_error` legible;
  - con `repeat_requested`, se vuelve a ejecutar;
  - una `RUNNING` con más de 10 minutos vuelve a `PENDING`;
  - dos trabajadores en paralelo no ejecutan la misma tarea (`SKIP LOCKED`);
  - la llamada al cliente se hace sin transacción activa (como hace
    `FakeInvoiceRecognizer`);
  - las `DONE` de más de 90 días se depuran.
- [X] T015 Implementar `SH/scheduling/SheetTaskWorker.kt`:
  - `@Scheduled(cron = "\${sheets.worker.cron:*/30 * * * * *}", zone = "Europe/Madrid")`;
  - `runOnce()` público: reclama en una transacción corta, escribe fuera de
    transacción y anota el resultado en otra;
  - `kick()` para la pasada inmediata, en un ejecutor de un hilo;
  - despacha por `kind` a los escritores que aportan US2 y US3 mediante una
    interfaz `SheetTaskHandler` registrada por `kind`.

  Hacer pasar T014.
- [X] T016 Añadir el bloque `sheets:` a `app/src/main/resources/application.yml`:
  - `google.credentials-file: ${GOOGLE_SHEETS_CREDENTIALS_FILE:}`, con comentario:
    secreto, vacío = desactivado;
  - `worker.cron: ${SHEETS_WORKER_CRON:*/30 * * * * *}`;
  - `max-attempts: ${SHEETS_MAX_ATTEMPTS:8}`.

  Y en `app/src/test/resources/config/application.yml`: `sheets.google.credentials-file: ""`.

**Checkpoint**: la cola funciona contra el fake y el cliente real contra
`MockWebServer`.

---

## Phase 3: User Story 1 - Nombre y apellidos (Priority: P1) 🎯 MVP

**Goal**: apellidos separados en todo el ciclo de la persona empleada.

**Independent Test**: alta con y sin `lastName`; persona antigua con
`lastNamePending: true`; CSV con `Nombre` y `Apellidos`.

- [X] T017 [P] [US1] Ampliar los ITs de empleados en `TTT/` (el que cubre
  `EmployeeController`):
  - `POST` y `PUT` sin `lastName` → `400 VALIDATION`;
  - con `lastName` "García López" se devuelve;
  - una fila insertada sin `last_name` devuelve `lastName: null` y
    `lastNamePending: true`;
  - `lastName` de 151 caracteres → `400`.
- [X] T018 [P] [US1] Ampliar los ITs de onboarding y sign-up en
  `features/auth/src/test/kotlin/com/granatum/core/`: `lastName` obligatorio
  (`@NotBlank @Size(max=150)`), y la persona creada lo tiene.
- [X] T019 [P] [US1] Ampliar el test de `RegisterCsvWriter` en `TTT/`:
  - cabecera `Nombre;Apellidos;Documento;…` (sin `Documento` para
    `REPRESENTATIVE`);
  - apellidos vacíos si faltan;
  - nombre completo en `Correcciones`.
- [X] T020 [US1] Crear `features/timetracking/src/main/resources/db/migration/V35__add_employee_last_name.sql`:
  `ALTER TABLE employees ADD COLUMN last_name VARCHAR(150) NULL`, con un
  comentario: nula = pendiente; no se parten los nombres existentes.
- [X] T021 [US1] Añadir `lastName` (`@Column(name="last_name", length=150)`,
  nullable) a `TT/infrastructure/database/entities/EmployeeEntity.kt`, y los
  mapeos al dominio.
- [X] T022 [US1] En `TT/api/dto/EmployeeDtos.kt` y su servicio:
  - `CreateEmployeeRequest` y `UpdateEmployeeRequest` con
    `lastName: String` `@NotBlank @Size(min=1, max=150)`;
  - `EmployeeDto` con `lastName: String?` y `lastNamePending: Boolean`
    (= `lastName == null`).
- [X] T023 [US1] Añadir `lastName` a `NewStaffRecord` en `CM/domain/contract/StaffRecords.kt`
  (y a su `toString`, oculto) y a `TT/…/StaffRecordsJpa.kt`.
- [X] T024 [US1] En `AU/api/dto/SignUpDtos.kt`, `SignUpRequest` y
  `OnboardingRequest` con `lastName` `@NotBlank @Size(max=150)`. Propagar a
  `SignUpData` y `OnboardingData`, y luego a `NewStaffRecord`, en
  `AU/service/SignUpService.kt` y `AU/service/AccountService.kt`.
- [X] T025 [US1] En `TT/domain/service/RegisterCsvWriter.kt` y `RegisterExportService.kt`:
  - la columna `Persona` pasa a ser `Nombre` y `Apellidos`;
  - quien pide o aprueba una corrección aparece con su nombre completo.

  Hacer pasar T017 a T019.
- [X] T026 [US1] Actualizar los fixtures y helpers de tests de todos los módulos
  que crean empleados o llaman a onboarding o sign-up (buscar `NewStaffRecord(`,
  `CreateEmployeeRequest(`, `"name":` en `src/test`). Todo `./gradlew test` de
  módulos en verde.

**Checkpoint**: US1 entregable sola.

---

## Phase 4: User Story 4 - Configurar las hojas y ver el estado (Priority: P1)

**Goal**: el `ADMIN` configura las dos hojas con comprobación de edición y ve el
estado de la cola.

**Independent Test**: `PUT` con hojas válidas, no compartidas, de solo lectura e
iguales; `GET` del estado; reintento de una fallida.

- [X] T027 [P] [US4] Escribir `SHT/SheetsSettingsControllerIT.kt` con `FakeSheetsClient`:
  - `GET` sin credenciales → `available:false` (con `DisabledSheetsClient`);
  - `GET` con credenciales → `serviceAccountEmail` y hojas `null`;
  - `PUT` con enlace completo e id → `200`, con id extraído y URLs construidas;
  - `NOT_SHARED`/`READ_ONLY`/`NOT_FOUND` → `422 SPREADSHEET_NOT_ACCESSIBLE` con
    `spreadsheet` y `reason`;
  - misma hoja → `409 SAME_SPREADSHEET`;
  - Google transitorio → `502 SHEETS_UNREACHABLE` sin guardar;
  - texto inválido → `400 VALIDATION`;
  - sin credenciales → `503 SHEETS_UNAVAILABLE`.
- [X] T028 [P] [US4] Escribir `SHT/SheetsStatusControllerIT.kt`:
  - `GET /api/sheets/status`: `lastSuccessAt`, `pending`, `failed` y `recent`
    (como mucho 20, del más reciente al más antiguo);
  - `GET /api/sheets/tasks/{id}` y su `404 TASK_NOT_FOUND`;
  - `POST /api/sheets/tasks/{id}/retry` → `202`, o `409 TASK_NOT_FAILED`;
  - `POST /api/sheets/tasks/retry-failed` → `{requeued}`.
- [X] T029 [US4] Implementar `SH/service/SheetSettingsService.kt`:
  - extrae el id de `/spreadsheets/d/{id}/` o lo acepta tal cual (regex
    `[A-Za-z0-9_-]{20,100}`);
  - `checkAccess` de las dos hojas fuera de transacción y guarda en una
    transacción corta;
  - `configured_by` = persona autenticada.
- [X] T030 [US4] Implementar `SH/api/dto/SheetsDtos.kt`,
  `SH/api/controllers/SheetsController.kt` (settings, status, tasks y retry) y
  `SH/api/exception_handling/SheetsExceptionHandler.kt` con los códigos de
  contracts/README.md. Hacer pasar T027 y T028.

---

## Phase 5: User Story 2 - Exportar los fichajes de un mes (Priority: P1)

**Goal**: el `ADMIN` exporta un mes y la pestaña `AAAA-MM` queda reescrita; queda
anotado en `exports`.

**Independent Test**: exportar octubre con el fake; filas correctas; repetir sin
duplicados; `destination: GOOGLE_SHEETS` en `/api/exports`.

- [ ] T031 [P] [US2] Crear los contratos en `CM/domain/contract/`:
  - **`ShiftSheetSource.kt`**: `month(YearMonth): ShiftSheetMonth`, con
    `ShiftSheetMonth(rows, lastNamesPending)` y
    `ShiftSheetRow(name, lastName?, identityDocument, date: LocalDate, clockIn: LocalTime, clockOut: LocalTime?)`
    en Europe/Madrid. El `toString` oculta el documento.
  - **`ShiftExportRecorder.kt`**:
    `recordSheetExport(requesterId, requesterRole, month: YearMonth, rowCount, fingerprint)`.
- [ ] T032 [P] [US2] Escribir `TTT/ShiftSheetSourceIT.kt`:
  - filas ordenadas por fecha y entrada;
  - valores vigentes tras una corrección aprobada;
  - jornada abierta o `INCOMPLETE` con `clockOut` nulo;
  - la que cruza la medianoche va en el mes y la fecha de su entrada;
  - personas dadas de baja incluidas;
  - `lastNamesPending` cuenta personas distintas sin apellidos;
  - no escribe nada (cuenta de filas de `shifts` y `shift_events` igual antes y
    después).
- [ ] T033 [P] [US2] Escribir `TTT/ShiftExportRecorderIT.kt`: la fila de
  `exports` lleva `destination='GOOGLE_SHEETS'`, el periodo del mes, `row_count`
  y `fingerprint`, y `/api/exports` devuelve `destination`. Las exportaciones CSV
  siguen con `CSV`.
- [ ] T034 [US2] Crear `features/timetracking/src/main/resources/db/migration/V36__add_export_destination.sql`:
  `ALTER TABLE exports ADD COLUMN destination VARCHAR(20) NOT NULL DEFAULT 'CSV'
  CHECK (destination IN ('CSV','GOOGLE_SHEETS'))`.

  **Antes, verificar el CHECK de `scope` y `employee_id` (`V15`/`V29`)**: si
  `MONTHLY` exige `employee_id`, la exportación a la hoja usa `STAFF`. Anotar el
  resultado en research.md D-006.
- [ ] T035 [US2] Añadir `destination` a `RegisterExportEntity` y al DTO de
  `/api/exports`, e implementar `TT/infrastructure/contract/ShiftExportRecorderJpa.kt`.
- [ ] T036 [US2] Implementar `TT/infrastructure/contract/ShiftSheetSourceJpa.kt`,
  reutilizando la lectura de `RegisterExportService.walk`: `REPEATABLE READ` de
  solo lectura, por lotes, `CivilDateRange.ZONE`. Hacer pasar T032 y T033.
- [ ] T037 [US2] Escribir `SHT/ShiftMonthExportIT.kt` con `FakeSheetsClient` y
  `ShiftSheetSource` y `ShiftExportRecorder` falsos:
  - `POST /api/sheets/shifts/2026/10` → `202 {taskId}`;
  - tras `runOnce`, la pestaña `2026-10` tiene la cabecera
    `Nombre | Apellidos | DNI | Fecha | Entrada | Salida` y las filas, con fechas
    `dd/MM/yyyy`, horas `HH:mm` y salida vacía si es nula;
  - repetir deja las mismas filas;
  - la tarea queda con `rowCount` y `lastNamesPending`;
  - el recorder se llama con la huella SHA-256 de las filas;
  - mes futuro → `400 INVALID_PERIOD`;
  - sin hoja de fichajes → `409 SHEETS_NOT_CONFIGURED`.
- [ ] T038 [US2] Implementar `SH/service/ShiftMonthWriter.kt`
  (`SheetTaskHandler` de `SHIFT_MONTH`) y el endpoint en `SheetsController`
  (encola, llama a `kick()` y responde `202`). El `ADMIN` que lo pide queda en
  `requested_by`, y se usa para `recordSheetExport`. Hacer pasar T037.

---

## Phase 6: User Story 3 - Recibos a la hoja (Priority: P2)

**Goal**: facturas confirmadas, corregidas o descartadas reflejadas solas en la
pestaña del mes de su fecha de emisión.

**Independent Test**: confirmar, corregir el mes y descartar con el fake; el
borrador nunca se escribe; resincronizar un mes.

- [ ] T039 [P] [US3] Crear `CM/domain/event/InvoiceSheetChange.kt` (`data class
  InvoiceSheetChange(val invoiceId: UUID)`) y `CM/domain/contract/InvoiceSheetSource.kt`:
  - `row(id): InvoiceSheetRow?`, nulo si la factura no está `CONFIRMED`;
  - `month(YearMonth): List<InvoiceSheetRow>`;
  - `InvoiceSheetRow` con id, `issueDate`, `type`, `issuerName`, `issuerNif`,
    `recipientName`, `recipientNif`, `number`, `concept`, `base`, `vatAmount`,
    `surcharge`, `withholdings` y `total`, donde base, IVA y recargo son la suma
    de las líneas. El `toString` oculta los NIF.
- [ ] T040 [P] [US3] Escribir `INT/InvoiceSheetChangeEventsIT.kt` con
  `@RecordApplicationEvents`:
  - publican el evento: confirmar, corregir una confirmada y descartar una
    confirmada;
  - no lo publican: corregir o descartar un borrador ni la subida.
- [ ] T041 [P] [US3] Escribir `INT/InvoiceSheetSourceIT.kt`:
  - una confirmada devuelve la fila con las sumas de las líneas de IVA;
  - una en borrador o descartada devuelve `null`;
  - `month` filtra por la fecha de emisión.
- [ ] T042 [US3] Publicar `InvoiceSheetChange` en `IN/service/InvoiceReview.kt`
  (`confirm`, y `correct`/`discard` cuando la factura estaba `CONFIRMED`) con
  `ApplicationEventPublisher`, e implementar
  `IN/infrastructure/contract/InvoiceSheetSourceJpa.kt`. Hacer pasar T040 y T041.
- [ ] T043 [US3] Escribir `SHT/InvoiceSheetSyncIT.kt` con `FakeSheetsClient` y
  `InvoiceSheetSource` falso:
  - **atomicidad**:
    - el evento dentro de una transacción que hace rollback no deja tarea;
    - con commit, deja una `INVOICE`;
    - sin integración o sin hoja de recibos, no deja nada;
  - **escritura**:
    - la fila se escribe en la pestaña del mes con las columnas de data-model.md
      (Tipo "Emitida"/"Recibida", importes como números);
    - si cambia el mes, se borra de la pestaña vieja (según
      `sheet_invoice_rows`) y se añade en la nueva;
  - **borrado**: `row` nulo quita la fila y la entrada de `sheet_invoice_rows`;
  - **coalescencia**: diez cambios seguidos, una escritura;
  - **resincronizar**: `POST /api/sheets/invoices/2026/10/resync` reescribe la
    pestaña con todas las confirmadas y actualiza `sheet_invoice_rows`.
- [ ] T044 [US3] Implementar:
  - `SH/service/InvoiceSheetChangeListener.kt`:
    `@TransactionalEventListener(phase = BEFORE_COMMIT)` que encola `INVOICE` si
    hay cliente activo y hoja de recibos;
  - `SH/service/InvoiceRowWriter.kt` (handler `INVOICE`);
  - `SH/service/InvoiceMonthWriter.kt` (handler `INVOICE_MONTH`);
  - el endpoint `resync`.

  Hacer pasar T043.

---

## Phase 7: Polish & Cross-Cutting

- [ ] T045 Añadir `.requestMatchers("/api/sheets", "/api/sheets/**").hasRole("ADMIN")`
  en `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt`,
  antes del catch-all, y un IT en `app/src/test/…` que compruebe que `MANAGER`,
  `EMPLOYEE` y `REPRESENTATIVE` reciben `403` en `/api/sheets/settings` y
  `/api/sheets/shifts/2026/10`.
- [ ] T046 [P] Ampliar `app/src/test/kotlin/com/granatum/core/NoRealApiCallsInTestsIT.kt`:
  con `GOOGLE_SHEETS_CREDENTIALS_FILE=/tmp/no-existe.json` en properties, el bean
  `SheetsClient` es `DisabledSheetsClient`.
- [ ] T047 [P] Añadir `sheet_settings`, `sheet_tasks` y `sheet_invoice_rows` a
  `expectedTables` en `app/src/test/kotlin/com/granatum/core/FullSchemaRlsIT.kt`.
- [ ] T048 [P] Crear `SHT/SheetsRealIT.kt` con `@Tag("sheets-real")`:
  - `assumeTrue` sobre `GOOGLE_SHEETS_CREDENTIALS_FILE` y `SHEETS_TEST_SPREADSHEET_ID`;
  - con el cliente real, `checkAccess`, `rewriteTab` en `test-<uuid>`,
    `upsertInvoiceRow`, `removeInvoiceRow`, y borra la pestaña al acabar.
- [ ] T049 Regenerar `docs/openapi.json` con `OPENAPI_UPDATE=true ./gradlew :app:test --tests '*OpenApi*'`
  usando Postgres desechable, y comprobar que aparecen `/api/sheets/**`,
  `lastName`, `lastNamePending` y `destination`.
- [ ] T050 [P] Documentar:
  - `.env.example`: sección `# --- Google Sheets (feature 012) ---` con
    `# GOOGLE_SHEETS_CREDENTIALS_FILE=` (SECRETO: ruta a un JSON fuera del
    repositorio), `SHEETS_WORKER_CRON` y `SHEETS_MAX_ATTEMPTS`;
  - `README.md`: sección "Copia en Google Sheets": crear la cuenta de servicio,
    activar la API de Sheets, compartir las hojas como Editor, configurar desde la
    app; PRE y PRO con cuentas distintas;
  - `CHANGELOG.md`, bajo Añadido y Cambiado: apellidos, columnas del CSV,
    `destination`;
  - `docs/english-naming.md`: versiones V35–V37 y rutas `/api/sheets`.
- [ ] T051 Verificación final:
  - `./gradlew test` de todos los módulos;
  - `:app:test` con Postgres desechable (`SPRING_APPLICATION_JSON`, contenedor
    eliminado después; no tocar el puerto 8080 ni los contenedores de squadfy);
  - grep de que no se usa `getApplicationDefault` ni `GOOGLE_APPLICATION_CREDENTIALS`;
  - marcar las tareas como completadas.

---

## Dependencies & Execution Order

- **Setup (T001–T004)**: lo primero.
- **US1 (T017–T026)**: solo depende de Setup. Puede ir en paralelo con la fase
  Foundational, porque toca `timetracking`, `auth` y `common`, no `sheets`.
- **Foundational (T005–T016)**: bloquea US4, US2 y US3.
- **US4 (T027–T030)**: tras Foundational.
- **US2 (T031–T038)**: tras Foundational y US1, porque las filas llevan
  apellidos; necesita la configuración de US4 para el `409`.
- **US3 (T039–T044)**: tras Foundational y US4; independiente de US2.
- **Polish (T045–T051)**: al final.

## Parallel Opportunities

- En Phase 2: T006, T007, T008 y T009 en paralelo.
- En US1: T017, T018 y T019 (tests de módulos distintos) en paralelo.
- En US2: T031, T032 y T033 en paralelo.
- En US3: T039, T040 y T041 en paralelo.
- US2 y US3 pueden ir en paralelo entre sí una vez hecha US4.

## Implementation Strategy

1. **MVP**: Setup y US1, con los apellidos entregables solos.
2. Foundational y US4: la integración configurable con estado visible.
3. US2: exportación de fichajes, lo primero que pide el negocio.
4. US3: recibos.
5. Polish y prueba real en PRE con la cuenta
   `granatumprueba@proyecto-prueba-granatum.iam.gserviceaccount.com`.
