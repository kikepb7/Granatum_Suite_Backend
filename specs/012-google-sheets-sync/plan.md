# Implementation Plan: Copia del registro de jornada y de las facturas en Google Sheets

**Branch**: `invoice-ml-feature` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/012-google-sheets-sync/spec.md`

## Summary

Las personas empleadas pasan a tener nombre y apellidos por separado.

Un módulo nuevo, `features/sheets`, copia en dos hojas de cálculo de Google
Workspace de la empresa, con una pestaña por mes:

- los fichajes de un mes, cuando el `ADMIN` lo pide;
- las facturas confirmadas, solas al confirmarlas, corregirlas o descartarlas.

Se escribe con la API oficial de Google Sheets y una cuenta de servicio cuyas
credenciales solo entran por un fichero indicado en una variable de entorno.
Cada escritura pasa por una cola persistente en Postgres, creada en la misma
transacción del cambio y procesada fuera de ella, con reintentos y estado visible.

`timetracking` e `invoices` no dependen del módulo nuevo: ofrecen sus datos
mediante contratos de `common` y un evento con solo el id de la factura.

## Technical Context

**Language/Version**: Kotlin 2.2.0, Java 21 (toolchain)

**Primary Dependencies**:

- Spring Boot 4.0.8 (web, data JPA, scheduling), Flyway, springdoc 3.0.3
- Nuevas:
  - `google-api-services-sheets` `v4-rev20250603-2.0.0`
  - `google-auth-library-oauth2-http` `1.48.0`
  - `google-http-client-gson` `1.47.0`

  Se confirman en Maven Central al implementar (research D-001).

**Storage**: PostgreSQL (Supabase). Migraciones `V35` y `V36` (timetracking) y
`V37` (sheets).

**Testing**: JUnit 5, MockK, Testcontainers `postgres:16-alpine`, OkHttp
`MockWebServer`, `@RecordApplicationEvents`, y la tarea real opcional
`sheetsRealTest`.

**Target Platform**: servidor Linux (Docker), un entorno PRE y otro PRO, cada uno
con su cuenta de servicio y sus hojas.

**Project Type**: servicio web (backend multi-módulo).

**Performance Goals**:

- exportar un mes de unos 100 fichajes en menos de 1 minuto;
- una factura confirmada en la hoja en menos de 5 minutos;
- con trabajador cada 30 s y pasada inmediata, en la práctica segundos.

**Constraints**:

- ninguna llamada a Google dentro de una transacción ni bloqueando una operación
  de negocio;
- las credenciales nunca en logs, respuestas ni ficheros versionados;
- `RAW` para no interpretar fórmulas;
- registro horario solo de lectura.

**Scale/Scope**: 4 personas al principio; decenas de facturas y unos 100
fichajes al mes. Muy por debajo de las cuotas de la API.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Cómo se cumple | Estado |
|---|---|---|
| **I. Features independientes** | `features/sheets` solo depende de `common`. `timetracking` e `invoices` implementan contratos nuevos en `common/domain/contract` (`ShiftSheetSource`, `ShiftExportRecorder`, `InvoiceSheetSource`) y publican `InvoiceSheetChange` en `common/domain/event`; ninguna depende de `sheets` (D-002). | ✅ |
| **II. Flyway** | Tres migraciones nuevas (`V35`–`V37`), ninguna editada. Se añaden a la lista de versiones de `docs/english-naming.md`. | ✅ |
| **III. Inmutabilidad del registro horario** | La exportación solo lee, con los valores vigentes y sin escribir en `shifts`, `shift_events` ni correcciones (FR-013). Queda constancia en `exports`, que sigue siendo INSERT-only: `destination` es un `ADD COLUMN` con un valor por defecto cierto para todas las filas (D-006). La hoja es una copia de consulta; el registro legal sigue en la base de datos. | ✅ |
| **IV. Autorización por roles** | `/api/sheets/**` solo `ADMIN`. `REPRESENTATIVE` sigue sin ver DNI: el CSV no cambia esa regla y la hoja solo la exporta `ADMIN`. Test de `SecurityConfig` para los demás roles. | ✅ |
| **V. Tests y CI** | Unitarios, ITs con Testcontainers por módulo, `RowLevelSecurityIT` del módulo nuevo, `FullSchemaRlsIT` y `NoRealApiCallsInTestsIT` ampliados. El test real va aparte y nunca en CI (D-009). | ✅ |
| **VI. Secretos** | `GOOGLE_SHEETS_CREDENTIALS_FILE` solo por entorno, comentado como secreto en `.env.example`. Credenciales explícitas, sin descubrimiento automático (D-001). `toString` y logs sin clave, y `last_error` sin contenido sensible. | ✅ |
| **VII. RLS** | `sheet_settings`, `sheet_tasks` y `sheet_invoice_rows` habilitan RLS en la misma migración, sin políticas ni `FORCE`, como el resto. | ✅ |
| **VIII. Contrato REST** | Rutas en inglés según `english-naming.md`, errores `{code, message}`, y `docs/openapi.json` regenerado; la app fija la nueva copia (contracts/README.md). | ✅ |
| **IX. Documentación** | `specs/012-google-sheets-sync/`, sección en el README (cómo crear la cuenta de servicio, compartir las hojas y configurar), CHANGELOG y `.env.example`. | ✅ |

**Re-check tras el diseño (Phase 1)**: sin violaciones. Hay un punto a verificar
al implementar que no afecta a la puerta: si el CHECK de `exports.scope` admite
`MONTHLY` con `employee_id` nulo (D-006). Si no lo admite, se usa `STAFF`.

## Project Structure

### Documentation (this feature)

```text
specs/012-google-sheets-sync/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/README.md
├── checklists/requirements.md
└── tasks.md              # /speckit-tasks
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/granatum/core/domain/
├── contract/
│   ├── ShiftSheetSource.kt          # nuevo
│   ├── ShiftExportRecorder.kt       # nuevo
│   ├── InvoiceSheetSource.kt        # nuevo
│   └── StaffRecords.kt              # NewStaffRecord + lastName
└── event/
    └── InvoiceSheetChange.kt        # nuevo

features/timetracking/src/main/
├── kotlin/com/granatum/core/
│   ├── api/dto/EmployeeDtos.kt                       # lastName, lastNamePending
│   ├── infrastructure/database/entities/EmployeeEntity.kt
│   ├── infrastructure/database/entities/RegisterExportEntity.kt  # destination
│   ├── domain/service/RegisterCsvWriter.kt           # Nombre | Apellidos
│   ├── infrastructure/contract/ShiftSheetSourceJpa.kt   # nuevo
│   └── infrastructure/contract/ShiftExportRecorderJpa.kt # nuevo
└── resources/db/migration/
    ├── V35__add_employee_last_name.sql
    └── V36__add_export_destination.sql

features/auth/src/main/kotlin/com/granatum/core/
├── api/dto/SignUpDtos.kt            # SignUpRequest, OnboardingRequest + lastName
└── service/{SignUpService,AccountService}.kt

features/invoices/src/main/kotlin/com/granatum/core/
├── service/InvoiceReview.kt         # publica InvoiceSheetChange
└── infrastructure/contract/InvoiceSheetSourceJpa.kt   # nuevo

features/sheets/                     # módulo nuevo
├── build.gradle.kts                 # implementation(projects.common) + librerías de Google
└── src/
    ├── main/kotlin/com/granatum/core/
    │   ├── api/controllers/SheetsController.kt
    │   ├── api/dto/SheetsDtos.kt
    │   ├── api/exception_handling/SheetsExceptionHandler.kt
    │   ├── domain/port/SheetsClient.kt            # puerto; DisabledSheetsClient
    │   ├── domain/model/                          # SheetTask, SheetSettings, filas
    │   ├── service/SheetSettingsService.kt
    │   ├── service/SheetTaskQueue.kt              # encolar + coalescer
    │   ├── service/InvoiceSheetChangeListener.kt  # BEFORE_COMMIT
    │   ├── service/SheetWriters.kt                # mes de fichajes, factura, mes de recibos
    │   ├── scheduling/SheetTaskWorker.kt          # SKIP LOCKED, reintentos
    │   ├── infrastructure/google/GoogleSheetsClient.kt
    │   ├── infrastructure/google/SheetsGoogleSettings.kt
    │   ├── infrastructure/google/SheetsClientConfig.kt
    │   └── infrastructure/database/               # entidades y repositorios
    ├── main/resources/db/migration/V37__create_sheets_tables.sql
    └── test/kotlin/com/granatum/core/
        ├── SheetsTestApplication.kt
        ├── FakeSheetsClient.kt
        ├── GoogleSheetsClientTest.kt              # MockWebServer
        ├── SheetTaskWorkerIT.kt
        ├── InvoiceSheetChangeListenerIT.kt
        ├── SheetsControllerIT.kt
        ├── RowLevelSecurityIT.kt
        └── SheetsRealIT.kt                        # @Tag("sheets-real")

app/
├── build.gradle.kts                 # implementation(projects.features.sheets)
├── src/main/resources/application.yml           # bloque sheets:
├── src/main/kotlin/.../api/security/SecurityConfig.kt  # /api/sheets/** ADMIN
└── src/test/kotlin/com/granatum/core/
    ├── NoRealApiCallsInTestsIT.kt   # + DisabledSheetsClient
    └── FullSchemaRlsIT.kt           # + tablas nuevas

settings.gradle.kts                  # include features:sheets
gradle/libs.versions.toml            # librerías de Google
.env.example, README.md, CHANGELOG.md, docs/english-naming.md, docs/openapi.json
```

**Structure Decision**: un módulo de feature más, con la misma forma que los
existentes (`api`, `domain`, `service`, `scheduling`, `infrastructure`, paquete
raíz `com.granatum.core` para que el escaneo de Spring lo encuentre). Los cambios
de apellidos van en los módulos dueños de cada DTO (`timetracking` y `auth`), y
los contratos, en `common`.

## Orden de implementación sugerido

1. **Apellidos (US1)**: `V35`, entidad, DTOs de `timetracking` y `auth`,
   `NewStaffRecord`, CSV. Se puede entregar sola.
2. **Base de `sheets`**: módulo, `V37`, cliente con puerto y versión
   desactivada, configuración y comprobación de acceso (US4), estado.
3. **Exportar fichajes (US2)**: `ShiftSheetSource`, `ShiftExportRecorder`, `V36`,
   escritor de mes y endpoint.
4. **Recibos (US3)**: evento, `InvoiceSheetSource`, listener, escritores de
   factura y de mes, resincronización.
5. **Cierre**: `SecurityConfig`, `NoRealApiCallsInTestsIT`, `FullSchemaRlsIT`,
   OpenAPI, README, CHANGELOG, `.env.example`, `english-naming.md`, y la prueba
   real con la hoja de PRE.

## Complexity Tracking

Sin violaciones de la constitución que justificar.
