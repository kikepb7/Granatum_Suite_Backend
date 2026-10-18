# Data Model: Copia del registro de jornada y de las facturas en Google Sheets

Todas las tablas nuevas habilitan RLS en la misma migración que las crea: sin
políticas, sin `FORCE`, igual que el resto del esquema (principio VII). Los
nombres siguen `docs/english-naming.md`.

## Cambios en tablas existentes

### `employees` (timetracking, migración `V35`)

| Columna | Tipo | Reglas |
|---|---|---|
| `last_name` | `VARCHAR(150)` NULL | Nueva. Nula solo en las personas anteriores a la feature. La API exige no vacía en altas y ediciones (máx. 150). |

- `lastNamePending` en la API equivale a `last_name IS NULL`; no se guarda.
- `name` no cambia: las personas existentes conservan su valor.

### `exports` (timetracking, migración `V36`)

| Columna | Tipo | Reglas |
|---|---|---|
| `destination` | `VARCHAR(20)` NOT NULL DEFAULT `'CSV'` | CHECK `IN ('CSV','GOOGLE_SHEETS')`. Las filas existentes quedan en `CSV`, que es cierto para todas. |

- La tabla sigue siendo INSERT-only. Una exportación a la hoja se anota con
  `scope = 'STAFF'` y `employee_id` nulo (el CHECK de `V29` exige persona en
  `MONTHLY`; ver research D-006), el periodo del mes, `row_count` y `fingerprint`.

## Tablas nuevas (sheets, migración `V37`)

### `sheet_settings`

Una sola fila por entorno.

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | `SMALLINT` PK | CHECK `id = 1` |
| `shifts_spreadsheet_id` | `VARCHAR(100)` NULL | id de la hoja de fichajes |
| `invoices_spreadsheet_id` | `VARCHAR(100)` NULL | id de la hoja de recibos; CHECK distinto de `shifts_spreadsheet_id` |
| `configured_by` | `UUID` NOT NULL | persona `ADMIN` que la guardó (sin FK, como el resto de referencias entre features) |
| `configured_at` | `TIMESTAMPTZ` NOT NULL | |
| `version` | `BIGINT` NOT NULL | bloqueo optimista |

### `sheet_tasks`

La cola de escrituras.

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | `UUID` PK | |
| `kind` | `VARCHAR(20)` NOT NULL | CHECK `IN ('SHIFT_MONTH','INVOICE','INVOICE_MONTH')` |
| `reference` | `VARCHAR(64)` NOT NULL | `AAAA-MM` para los meses; id de la factura para `INVOICE` |
| `status` | `VARCHAR(20)` NOT NULL | CHECK `IN ('PENDING','RUNNING','DONE','FAILED')` |
| `attempts` | `INT` NOT NULL DEFAULT 0 | |
| `next_attempt_at` | `TIMESTAMPTZ` NOT NULL | cuándo puede reclamarse |
| `last_error` | `VARCHAR(300)` NULL | motivo legible, sin credenciales ni contenido de la hoja |
| `error_reason` | `VARCHAR(30)` NULL | migración `V38`. Motivo estructurado del último intento fallido: CHECK `IN ('NOT_FOUND','NOT_SHARED','READ_ONLY','BAD_REQUEST','UNAVAILABLE','NO_HANDLER','ABORTED','TRANSIENT')`. Se fija en cada fallo (también en los transitorios que se reintentan) y se borra al terminar bien o al reintentar a mano |
| `requested_by` | `UUID` NULL | quien pidió la exportación o resincronización; nulo en las automáticas |
| `row_count` | `INT` NULL | filas escritas (meses) |
| `last_names_pending` | `INT` NULL | solo `SHIFT_MONTH` |
| `created_at`, `updated_at`, `completed_at` | `TIMESTAMPTZ` | |

- **Índice único parcial** `(kind, reference) WHERE status IN ('PENDING','RUNNING')`
  para la coalescencia (research D-003).
- **Índice** `(status, next_attempt_at)` para la reclamación con `SKIP LOCKED`.
- Las tareas `DONE` se depuran a los 90 días con el mismo trabajador. Son
  operativas, no registro legal: la constancia legal está en `exports`.

**Estados**

```text
PENDING ──reclamada──▶ RUNNING ──éxito──▶ DONE
   ▲                      │
   │   error transitorio  │  (attempts < 8: next_attempt_at = now + min(2^attempts min, 60 min))
   └──────────────────────┤
                          └──error definitivo o 8 intentos──▶ FAILED ──reintento ADMIN──▶ PENDING
```

- Una tarea `RUNNING` cuyo `updated_at` tenga más de 10 minutos se considera
  abandonada (servidor caído a mitad) y vuelve a `PENDING`.
- Un cambio nuevo sobre una tarea `RUNNING` crea otra `PENDING`. El índice
  parcial lo impide, así que el `ON CONFLICT` la marca para repetir: se escribe
  otra vez con el estado final.

### `sheet_invoice_rows`

Dónde está cada factura escrita.

| Columna | Tipo | Reglas |
|---|---|---|
| `invoice_id` | `UUID` PK | |
| `spreadsheet_id` | `VARCHAR(100)` NOT NULL | hoja en la que se escribió |
| `tab` | `VARCHAR(7)` NOT NULL | `AAAA-MM` |
| `written_at` | `TIMESTAMPTZ` NOT NULL | |

- Se borra la fila al quitar la factura de la hoja (descartada).
- Guarda la pestaña, no el número de fila (research D-004).

## Contratos nuevos en `common`

| Contrato | Implementa | Usa | Devuelve |
|---|---|---|---|
| `ShiftSheetSource.month(YearMonth)` / `.retentionCutoff()` | timetracking | sheets | `ShiftSheetMonth(rows: List<ShiftSheetRow>, lastNamesPending: Int)`; `ShiftSheetRow(name, lastName?, identityDocument, date, clockIn, clockOut?)` en hora de Madrid, ordenadas por fecha y entrada; `retentionCutoff()` es la primera fecha del plazo de conservación, para el `400 INVALID_PERIOD` |
| `ShiftExportRecorder.recordSheetExport(...)` | timetracking | sheets | anota en `exports` con `destination = GOOGLE_SHEETS` |
| `InvoiceSheetSource.row(id)` / `.month(YearMonth)` | invoices | sheets | `InvoiceSheetRow?`: nulo si la factura no está confirmada |
| Evento `InvoiceSheetChange(invoiceId)` | invoices publica | sheets escucha (`BEFORE_COMMIT`) | solo el id |

`InvoiceSheetRow` lleva: `id`, `issueDate`, `type` (`ISSUED`/`RECEIVED`),
`issuerName`, `issuerNif`, `recipientName`, `recipientNif`, `number`, `concept`,
`base`, `vatAmount`, `surcharge`, `withholdings` y `total`. Base, IVA y recargo
son la suma de las líneas de IVA. Los `toString` ocultan el DNI y los NIF, como
`NewStaffRecord`.

## Columnas de las pestañas

**Fichajes** (`AAAA-MM`): `Nombre | Apellidos | DNI | Fecha | Entrada | Salida`

**Recibos** (`AAAA-MM`):

```text
Id (oculta) | Fecha | Tipo | Emisor | NIF emisor | Receptor | NIF receptor | Número | Concepto | Base | IVA | Recargo | Retenciones | Total
```

- `Tipo`: "Emitida" o "Recibida".
- Fechas en texto `dd/MM/yyyy`, horas `HH:mm`, importes como números.
- Todo se escribe con `RAW` (research D-004).
