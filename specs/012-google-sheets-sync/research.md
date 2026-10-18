# Research: Copia del registro de jornada y de las facturas en Google Sheets

Decisiones técnicas de la feature 012. Cada una indica qué se elige, por qué y qué
se descartó. Los hechos del código citados se comprobaron en la rama
`invoice-ml-feature` el 2026-10-18.

---

## D-001: Cliente oficial de Google con credenciales explícitas

**Decisión**: librerías oficiales de Google:

- `com.google.apis:google-api-services-sheets` `v4-rev20250603-2.0.0`
- `com.google.auth:google-auth-library-oauth2-http` `1.48.0`
- `com.google.http-client:google-http-client-gson` `1.47.0`

Al implementar, se confirma en Maven Central que son las últimas estables y se
fijan en el catálogo.

Las credenciales salen **solo** de un fichero JSON de cuenta de servicio cuya ruta
indica una variable propia, `GOOGLE_SHEETS_CREDENTIALS_FILE`:
`ServiceAccountCredentials.fromStream(fichero).createScoped(SheetsScopes.SPREADSHEETS)`.
**Nunca** `GoogleCredentials.getApplicationDefault()` ni
`GOOGLE_APPLICATION_CREDENTIALS`. Si la variable está vacía, el bean es un
`DisabledSheetsClient` y la feature queda desactivada; el arranque lo avisa una
vez en el log.

**Motivo**:

- La librería ya resuelve la firma del JWT, la renovación del token, los
  reintentos HTTP y el modelo de peticiones (`batchUpdate`, `values`).
  Reescribirlo con OkHttp no ahorra nada y añade superficie de error.
- Con el descubrimiento automático de credenciales, un entorno o un test sin
  configurar podría autenticarse con lo que encuentre en la máquina (el gcloud de
  quien desarrolla, metadatos de la nube). Es el mismo riesgo que el hallazgo S1
  del SDK de Anthropic en la feature 004, y se resuelve igual: clave explícita o
  nada.
- El alcance `spreadsheets` permite editar las hojas compartidas con la cuenta y
  nada más de Drive. No hace falta `drive` porque la cuenta no crea ni lista
  ficheros: solo edita las dos hojas que se le comparten.
- Un fichero, y no el JSON en una variable, porque la clave tiene saltos de línea
  y comillas que se rompen en un `.env`, y porque así se monta como secreto en el
  servidor sin pasar por la línea de comandos.
- El correo de la cuenta de servicio (`client_email`) se lee del propio fichero:
  PRE y PRO tienen cuentas distintas y el código no conoce ninguna.

**Alternativas descartadas**: HTTP a mano con OkHttp (descartada por lo anterior);
la variable estándar `GOOGLE_APPLICATION_CREDENTIALS` (activa el descubrimiento
automático en otras librerías); OAuth con la cuenta personal del `ADMIN` (exige
un flujo de consentimiento en la app y guardar un token de refresco por persona;
la cuenta de servicio es más simple para una empresa con Workspace).

---

## D-002: Módulo nuevo `features/sheets`, alimentado por contratos de `common`

**Decisión**:

- Un módulo nuevo, `features/sheets`, que solo depende de `common` (principio I).
- `timetracking` e `invoices` **no** dependen de él. Exponen lo que hace falta
  mediante contratos nuevos en `common/domain/contract`, que implementan ellos:
  - `ShiftSheetSource`: devuelve las filas de un mes (nombre, apellidos, DNI,
    fecha, entrada, salida) y cuántas personas tienen los apellidos pendientes.
    Lo implementa `timetracking`.
  - `ShiftExportRecorder`: anota una exportación a la hoja en el registro de
    exportaciones. Lo implementa `timetracking`, dueño de la tabla.
  - `InvoiceSheetSource`: devuelve la fila de una factura **si está
    confirmada** (si no, nada), y las filas de las facturas confirmadas de un mes.
    Lo implementa `invoices`.
- `invoices` publica un evento `InvoiceSheetChange(invoiceId)` (en
  `common/domain/event`, solo con el id, como `DomainNotice`) al confirmar,
  corregir una confirmada o descartar una confirmada.

**Motivo**:

- Es el patrón que ya usan `MaterialInventory`, `MaterialUsage`, `StaffRecords` y
  `EmployeeDirectory`: el contrato vive en `common`, lo implementa el dueño de los
  datos y lo usa quien los necesita, sin dependencias entre features.
- El evento lleva solo el id porque `sheets` vuelve a leer el estado actual al
  escribir. Así da igual cuántos cambios haya habido: siempre se escribe el
  último (D-003), y el evento no transporta datos personales.
- `ShiftSheetSource` vive en `timetracking` porque reutiliza la lectura de la
  exportación CSV de la feature 003 (`RegisterExportService.walk`): una única
  transacción de solo lectura `REPEATABLE READ`, paginada, con los valores
  vigentes (las correcciones aprobadas ya están aplicadas en `shifts`) y en
  `Europe/Madrid` (`CivilDateRange.ZONE`).

**Alternativas descartadas**: poner la exportación a la hoja dentro de
`timetracking` e `invoices` (los dos dependerían de Google y la cola estaría
duplicada); que `sheets` lea las tablas de otras features directamente (rompe el
principio I).

---

## D-003: Cola persistente en la misma transacción del cambio

**Decisión**: tabla `sheet_tasks` en `sheets`.

- **Facturas**: un `@TransactionalEventListener(phase = BEFORE_COMMIT)` en
  `sheets` inserta la tarea **dentro de la transacción que confirma, corrige o
  descarta la factura**. Si la integración está desactivada o sin configurar, no
  hace nada.
- **Coalescencia**: una única tarea pendiente por `(kind, reference)` (índice
  único parcial sobre las pendientes), con
  `INSERT … ON CONFLICT DO UPDATE` que reinicia intentos y espera. Diez cambios
  seguidos de la misma factura son una sola escritura con el estado final.
- **Trabajador**: un `@Scheduled` cada 30 s (`sheets.worker.cron`,
  `zone = "Europe/Madrid"`) reclama tareas vencidas con
  `SELECT … FOR UPDATE SKIP LOCKED LIMIT n` en una transacción corta. Escribe en
  Google **fuera de cualquier transacción** y anota el resultado en otra
  transacción corta. Las exportaciones y resincronizaciones que pide el `ADMIN`
  lanzan además una pasada inmediata, en un ejecutor propio, para no esperar al
  siguiente ciclo.
- **Reintentos**: espera `min(2^intentos minutos, 60 min)`. Tras 8 intentos la
  tarea queda `FAILED`, visible en el estado; el `ADMIN` puede reintentarla, lo
  que la vuelve a `PENDING` con los intentos a cero.
- **Errores**:
  - Definitivos, que no se reintentan: 403 (hoja no compartida), 404 (hoja
    borrada) y 400. La tarea pasa directamente a `FAILED` con su motivo.
  - Transitorios, que se reintentan: 429 (cuota), 5xx, tiempo agotado y red.

**Motivo**:

- `AFTER_COMMIT`, como usa `notifications`, puede perder el cambio si el
  servidor cae entre el commit y la inserción. `BEFORE_COMMIT` lo hace atómico: o
  se confirma la factura **y** queda la tarea, o ninguna de las dos. Insertar una
  fila local no es "escribir en Google", así que se sigue cumpliendo FR-020.
- `SKIP LOCKED` evita que dos instancias, o una pasada inmediata y la
  programada, cojan la misma tarea. Es más simple que añadir ShedLock, que el
  proyecto no usa.
- Separar la llamada a Google de las transacciones es la lección de las features
  002 a 004 (D-005 de 004): una llamada lenta no debe tener una conexión de la
  base de datos ocupada.

**Alternativas descartadas**: escribir en Google dentro de la transacción (viola
FR-020); cola en memoria como `RecognitionQueue` (no sobrevive a reinicios,
FR-021); ShedLock (dependencia nueva para un problema que `SKIP LOCKED` ya
resuelve).

---

## D-004: Escrituras idempotentes en la hoja

**Decisión**:

- **Fichajes, pestaña de un mes** (`SHIFT_MONTH`, referencia `2026-10`):
  1. `spreadsheets.get` con `fields=sheets.properties`, para saber si la pestaña
     existe y su `sheetId`.
  2. `batchUpdate` con `addSheet` si no existe.
  3. `values.clear` sobre `'2026-10'!A:Z`.
  4. `values.update` desde `A1` con la cabecera y todas las filas en una sola
     petición.

  Son cuatro peticiones como mucho, sea cual sea el número de fichajes.
- **Recibos, una factura** (`INVOICE`, referencia el id):
  - La columna A de cada pestaña de recibos guarda el id de la factura; queda
    oculta y protegida contra edición accidental.
  - La tabla `sheet_invoice_rows` guarda en qué pestaña está cada factura escrita
    (no el número de fila, porque una edición a mano lo desplaza).
  - Para escribir: se lee la columna A de esa pestaña, se localiza el id y se
    actualiza la fila (`values.update`), o se borra (`deleteDimension`) si la
    factura ya no está confirmada. Si el mes cambió, se borra de la pestaña vieja
    y se añade en la nueva (`values.append`).
- **Resincronizar un mes de recibos** (`INVOICE_MONTH`): igual que fichajes, la
  pestaña se reescribe entera con todas las confirmadas del mes, y se actualiza
  `sheet_invoice_rows`.
- **Formato**: `valueInputOption=RAW`, para que ningún texto (concepto, nombre,
  NIF) se interprete como fórmula. Es la misma protección que `CsvFormat` da al
  CSV. Las fechas van como texto `dd/MM/yyyy` y las horas `HH:mm`. Los importes
  van como números, para que se puedan sumar en la hoja.
- **Cuotas**: la API de Sheets permite del orden de 300 peticiones por minuto por
  proyecto y 60 por minuto por usuario; hay que confirmarlo en la consola del
  proyecto. Con 4 personas y unas decenas de facturas al mes el uso es mínimo. Un
  429 es un error transitorio (D-003).

**Motivo**: reescribir la pestaña entera hace que exportar dos veces dé el mismo
resultado (SC-002) sin tener que reconciliar fila a fila. Para facturas sueltas,
localizar por id tolera que alguien reordene o inserte filas a mano.

**Alternativas descartadas**: guardar el número de fila (se rompe con cualquier
edición manual); `USER_ENTERED` (interpretaría fórmulas que vengan en un
concepto); una pestaña por factura (absurdo para consultar).

---

## D-005: Apellidos

**Decisión**:

- Migración `V35` en `timetracking`: `employees.last_name VARCHAR(150) NULL`.
  "Apellidos pendientes" no es una columna aparte: es `last_name IS NULL`, y la
  API lo expone como `lastNamePending`.
- Altas y ediciones exigen `lastName` `@NotBlank @Size(max = 150)` en:
  `CreateEmployeeRequest`, `UpdateEmployeeRequest`, `OnboardingRequest` y
  `SignUpRequest` (el registro de la propietaria con código de arranque). Se
  añade también a los datos de servicio `SignUpData` y `OnboardingData`, y a
  `NewStaffRecord` del contrato `StaffRecords`.
- `EmployeeDto` devuelve `lastName` (nulo si falta) y `lastNamePending`.
- CSV de 003: la columna `Persona` se sustituye por `Nombre` y `Apellidos`. El
  nombre de quien pide o aprueba una corrección, en la columna `Correcciones`,
  pasa a ser el nombre completo.

**Motivo**:

- No se pueden partir los nombres existentes con seguridad: en España hay nombres
  y apellidos compuestos. Dejarlos nulos y marcarlos es honesto, y el `ADMIN` los
  completa.
- El flujo de registro con aprobación de la feature 005 ya no existe (lo quitó la
  009, migraciones `V24` y `V25`). Solo queda el registro de la propietaria.
- Las exportaciones CSV antiguas siguen siendo verificables: `/api/exports/verify`
  compara el fichero con la huella guardada de **ese** fichero, no lo regenera.

**Alternativas descartadas**: una columna booleana de pendientes (duplica
información derivable); apellidos obligatorios en la migración con un valor de
relleno (inventa datos).

---

## D-006: Registro de exportaciones con destino

**Decisión**:

- Migración `V36` en `timetracking`:
  `exports.destination VARCHAR(20) NOT NULL DEFAULT 'CSV'`, con un CHECK
  `('CSV', 'GOOGLE_SHEETS')`. Es solo `ADD COLUMN`: ninguna fila existente cambia
  de valor salvo por el valor por defecto, que es cierto para todas.
- La exportación a la hoja se anota con `scope = 'STAFF'`, `employee_id` nulo
  (toda la plantilla), el mes completo como periodo, `completed = true`, las filas
  escritas y la huella SHA-256 de las filas (ver "Verificado al implementar").
- La consulta `/api/exports` devuelve `destination`.

**Motivo**: la tabla es INSERT-only (principio III aplicado a las exportaciones).
Añadir una columna con un valor por defecto verdadero no reescribe la historia.
El registro deja constancia de que los DNI salieron hacia Google, quién lo hizo y
cuándo.

**Verificado al implementar (2026-10-18)**: el CHECK `ck_exports_employee` de
`V29` (heredero de `ck_exportaciones_empleado` de `V15`) es
`(scope = 'STAFF') = (employee_id IS NULL)`: `MONTHLY` **exige** `employee_id`,
porque es la descarga mensual de una persona. Por eso la exportación a la hoja usa
`STAFF` con el periodo del mes, sin tocar ese CHECK. Lo que la distingue de una
exportación CSV de la plantilla es `destination = 'GOOGLE_SHEETS'`.

La huella es el SHA-256, en hexadecimal, de la pestaña tal como se escribe: la
cabecera y cada fila, con las celdas separadas por un tabulador y cada fila
terminada en `\n`, en UTF-8 (`ShiftMonthWriter.fingerprint`). Los mismos datos dan
siempre la misma huella. No se puede verificar con `/api/exports/verify`, que
compara ficheros CSV: la hoja no es un fichero entregado.

---

## D-007: Configuración y comprobación de acceso

**Decisión**:

- Tabla `sheet_settings` de una sola fila (restricción `id = 1`):
  `shifts_spreadsheet_id`, `invoices_spreadsheet_id`, `configured_by`,
  `configured_at`.
- Al guardar se acepta el enlace completo o el id. El id se extrae del patrón
  `/spreadsheets/d/{id}/`.
- Para cada hoja se comprueba:
  1. `spreadsheets.get(fields=spreadsheetId,properties.title)`: existe y es
     visible.
  2. Un `batchUpdate` con `updateSpreadsheetProperties` que reescribe el mismo
     título (`fields=title`): demuestra permiso de **edición** sin cambiar nada.

  Un 403 en el paso 2 significa que está compartida solo como lectora.
- Las dos hojas deben ser distintas (FR-007).
- Cambiar una hoja no borra las tareas pendientes; se escribirán en la hoja
  nueva.

**Motivo**: comprobar solo la lectura daría por buena una hoja compartida como
lectora, y el fallo aparecería después, en la cola. La escritura sin efecto lo
detecta al configurar (SC-007).

---

## D-008: API REST

**Decisión**: rutas bajo `/api/sheets`, todas solo `ADMIN` en `SecurityConfig`
(`.requestMatchers("/api/sheets", "/api/sheets/**").hasRole("ADMIN")`), según
`docs/english-naming.md` (inglés, segmentos kebab-case, acciones como verbo). El
detalle está en [contracts/README.md](contracts/README.md).

La exportación es asíncrona: responde `202` con el id de la tarea, y la app
consulta su estado. Si Google responde, cuatro peticiones tardan unos segundos y
la app ve el resultado casi al momento (SC-001). Si no responde, la tarea queda en
cola sin bloquear al `ADMIN`.

---

## D-009: Tests

**Decisión**:

- **Unitarios** del trabajador, de la coalescencia, de la espera y de la
  clasificación de errores, con un `FakeSheetsClient` scriptable (como
  `FakeInvoiceRecognizer`), importado con `@Bean @Primary`.
- **Cliente real contra un servidor simulado**: `GoogleSheetsClientTest` con
  OkHttp `MockWebServer`, mediante `sheets.google.root-url` y
  `sheets.google.token-url` configurables. Comprueba las peticiones exactas
  (`RAW`, rangos, `addSheet`, `deleteDimension`) sin red.
- **ITs con Testcontainers** en `features/sheets`:
  - la tarea se crea en la misma transacción (rollback, luego sin tarea);
  - coalescencia;
  - `SKIP LOCKED` con dos trabajadores;
  - persistencia tras reiniciar el contexto;
  - `RowLevelSecurityIT`.
- **ITs en `timetracking`**:
  - `lastName` en altas, ediciones y onboarding;
  - `ShiftSheetSource` con correcciones aprobadas, jornadas abiertas y que
    cruzan la medianoche;
  - el CSV con las dos columnas;
  - `destination` en el registro.
- **ITs en `invoices`**: confirmar, corregir y descartar publican el evento, y un
  borrador no lo publica (`@RecordApplicationEvents`).
- **`app`**:
  - `NoRealApiCallsInTestsIT` amplía su comprobación: con
    `GOOGLE_SHEETS_CREDENTIALS_FILE` apuntando a un fichero falso, el cliente es
    `DisabledSheetsClient`, porque el `config/application.yml` de test lo fija
    vacío;
  - `FullSchemaRlsIT` añade las tablas nuevas;
  - `SecurityConfig` rechaza `/api/sheets/**` a los demás roles.
- **Test real opcional**: `@Tag("sheets-real")`, con su tarea Gradle
  `sheetsRealTest` excluida de `test`. Hace `assumeTrue` sobre
  `GOOGLE_SHEETS_CREDENTIALS_FILE` y `SHEETS_TEST_SPREADSHEET_ID`. Escribe y borra
  una pestaña `test-<uuid>` en la hoja de PRE.

**Motivo**: las mismas garantías que la feature 004 con Claude. Ningún test
normal toca Google, y lo real se comprueba a propósito, con credenciales de PRE.
