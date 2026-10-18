# Contrato REST: feature 012

Errores con el formato habitual `{code, message}`. Todas las rutas nuevas son
**solo `ADMIN`**: cualquier otro rol recibe `403 FORBIDDEN` y sin sesión
`401 NOT_AUTHENTICATED`, como el resto de la API. Al implementar se regenera
`docs/openapi.json` (`OPENAPI_UPDATE=true ./gradlew :app:test --tests '*OpenApi*'`)
y la app fija la nueva copia.

## Cambios en rutas existentes

### Personas empleadas

`lastName` es obligatorio (no vacío, máx. 150) en:

- `POST /api/employees` y `PUT /api/employees/{id}`
- `POST /api/auth/onboarding`
- `POST /api/auth/sign-up` (registro de la propietaria con código de arranque)

Sin él, `400 VALIDATION` con el campo.

`EmployeeDto` gana dos campos:

```json
{ "id": "…", "name": "Ana", "lastName": "García López", "lastNamePending": false,
  "identityDocument": "12345678Z", "position": "Florista", "contractType": "FULL_TIME",
  "hireDate": "2026-01-15", "active": true }
```

En una persona anterior a la feature, `"lastName": null` y `"lastNamePending": true`.

### Exportación CSV del registro (`GET /api/shifts/export`)

La columna `Persona` se sustituye por `Nombre` y `Apellidos`. El resto no cambia
(UTF-8 con BOM, `;`, Madrid, sin `Documento` para `REPRESENTATIVE`).

### Registro de exportaciones (`GET /api/exports`)

Cada elemento gana `"destination": "CSV" | "GOOGLE_SHEETS"`. Una exportación a la
hoja aparece con `scope: "STAFF"`, `employeeId: null`, el mes completo en
`from`/`to`, `completed: true`, `rowCount` y `fingerprint` (SHA-256 de las filas
escritas; research D-006).

## Rutas nuevas (`/api/sheets`)

### `GET /api/sheets/settings`

```json
{
  "available": true,
  "serviceAccountEmail": "granatumprueba@proyecto-prueba-granatum.iam.gserviceaccount.com",
  "shiftsSpreadsheetId": "1AbC…",
  "shiftsSpreadsheetUrl": "https://docs.google.com/spreadsheets/d/1AbC…/edit",
  "invoicesSpreadsheetId": "1XyZ…",
  "invoicesSpreadsheetUrl": "https://docs.google.com/spreadsheets/d/1XyZ…/edit",
  "configuredAt": "2026-10-18T10:00:00Z",
  "configuredBy": "uuid"
}
```

Sin credenciales en el servidor: `available: false`, y el resto de campos, `null`.
Sin hojas configuradas: `available: true` y las hojas `null`.

### `PUT /api/sheets/settings`

```json
{ "shiftsSpreadsheet": "https://docs.google.com/spreadsheets/d/1AbC…/edit#gid=0",
  "invoicesSpreadsheet": "1XyZ…" }
```

Acepta el enlace o el id. Comprueba lectura y edición de las dos hojas (research
D-007).

| Respuesta | Cuándo |
|---|---|
| `200` + el cuerpo de `GET` | las dos hojas se pueden editar |
| `400 VALIDATION` | vacío o no es ni un enlace de hoja ni un id |
| `409 SAME_SPREADSHEET` | las dos apuntan a la misma hoja |
| `422 SPREADSHEET_NOT_ACCESSIBLE` + `"spreadsheet": "shifts" \| "invoices"` y `"reason": "NOT_FOUND" \| "NOT_SHARED" \| "READ_ONLY"` | no existe, no está compartida con la cuenta, o solo como lectora |
| `502 SHEETS_UNREACHABLE` | Google no responde; no se guarda nada |
| `503 SHEETS_UNAVAILABLE` | el servidor no tiene credenciales |

### `POST /api/sheets/shifts/{year}/{month}`

Exporta un mes de fichajes: reescribe la pestaña `AAAA-MM`.

| Respuesta | Cuándo |
|---|---|
| `202` + `{ "taskId": "uuid" }` | tarea creada (o unida a una pendiente del mismo mes) y pasada inmediata lanzada |
| `400 INVALID_PERIOD` | mes inexistente (`month` fuera de 1–12, `year` < 1), posterior al mes actual en Madrid (el mes en curso sí se admite) o que termina antes del plazo de conservación del registro (4 años) |
| `409 SHEETS_NOT_CONFIGURED` | falta la hoja de fichajes |
| `503 SHEETS_UNAVAILABLE` | sin credenciales |

### `POST /api/sheets/invoices/{year}/{month}/resync`

Reescribe la pestaña de recibos del mes con todas las facturas confirmadas de ese
mes. Respuestas iguales que la anterior, con la hoja de recibos, salvo que no hay
límite de conservación: `400 INVALID_PERIOD` solo para un mes inexistente o
futuro.

Orden de comprobación en las dos: `400` (periodo), `503` (sin credenciales),
`409` (hoja sin configurar).

### `GET /api/sheets/tasks/{id}`

```json
{ "id": "uuid", "kind": "SHIFT_MONTH", "reference": "2026-10", "status": "DONE",
  "attempts": 1, "rowCount": 92, "lastNamesPending": 1, "lastError": null,
  "errorReason": null, "createdAt": "…", "updatedAt": "…", "completedAt": "…" }
```

- `kind`: `SHIFT_MONTH` | `INVOICE` | `INVOICE_MONTH`
- `status`: `PENDING` | `RUNNING` | `DONE` | `FAILED`
- `rowCount`: filas escritas (meses; `1` o `0` en `INVOICE`); `lastNamesPending`,
  solo en `SHIFT_MONTH`. Ambos `null` hasta que termina bien.
- `lastError`: motivo legible en español, o `null`.
- `errorReason`: motivo estructurado del último intento fallido, o `null` si no
  falló (se borra al terminar bien y al reintentar a mano). Una tarea `PENDING`
  que espera un reintento lo lleva, para que se vea por qué espera.

  | Valor | Cuándo |
  |---|---|
  | `NOT_FOUND` | la hoja no existe o la cuenta no la ve (definitivo) |
  | `NOT_SHARED` | la hoja no está compartida con la cuenta (definitivo) |
  | `READ_ONLY` | compartida solo como lectora (definitivo) |
  | `BAD_REQUEST` | Google rechazó la petición (definitivo) |
  | `UNAVAILABLE` | el servidor no tiene credenciales de Google (definitivo) |
  | `NO_HANDLER` | no hay escritor para ese `kind` (definitivo) |
  | `ABORTED` | el escritor desistió, p. ej. la hoja ya no está configurada (definitivo) |
  | `TRANSIENT` | el último intento falló de forma transitoria: `PENDING` si se reintentará, `FAILED` si se agotaron los intentos |
- `404 TASK_NOT_FOUND` si no existe.

### `GET /api/sheets/status`

```json
{ "lastSuccessAt": "…", "pending": 0, "failed": 1,
  "recent": [ { …tarea… } ] }
```

`recent` lleva las 20 últimas tareas no `DONE` o terminadas en las últimas 24 h,
de la más reciente a la más antigua, con el mismo formato que
`GET /api/sheets/tasks/{id}` (`errorReason` incluido).

### `POST /api/sheets/tasks/{id}/retry`

Vuelve a poner una tarea `FAILED` en cola, con los intentos a cero.

- `202` + la tarea
- `409 TASK_NOT_FAILED` si no está fallida
- `404 TASK_NOT_FOUND` si no existe

### `POST /api/sheets/tasks/retry-failed`

Hace lo mismo con todas las fallidas: `202` + `{ "requeued": 3 }`.

## Configuración (variables de entorno)

| Variable | Defecto | Para qué |
|---|---|---|
| `GOOGLE_SHEETS_CREDENTIALS_FILE` | (vacía) | **Secreto**: ruta al JSON de la cuenta de servicio. Vacía significa integración desactivada. |
| `SHEETS_WORKER_CRON` | `*/30 * * * * *` | Frecuencia del trabajador |
| `SHEETS_MAX_ATTEMPTS` | `8` | Intentos antes de `FAILED` |
| `SHEETS_TEST_SPREADSHEET_ID` | (vacía) | Solo para `sheetsRealTest` |
