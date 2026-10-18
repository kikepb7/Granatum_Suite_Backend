# Quickstart: validar la feature 012

Guía para comprobar la feature de principio a fin en PRE. El contrato está en
[contracts/README.md](contracts/README.md) y las tablas en
[data-model.md](data-model.md).

## Requisitos

- Backend en local, en un puerto libre (el 8080 está ocupado por otro proceso: no
  tocarlo).
- Las dos hojas de prueba creadas y compartidas como **Editor** con
  `granatumprueba@proyecto-prueba-granatum.iam.gserviceaccount.com`.
- El JSON de esa cuenta guardado **fuera del repositorio**, y en el `.env` local
  `GOOGLE_SHEETS_CREDENTIALS_FILE=<ruta al JSON>`. Lo configura quien desarrolla;
  nunca se pega en un chat ni se versiona.
- Un `ADMIN` con sesión, y fichajes y facturas de prueba.

## Tests automáticos

```bash
./gradlew :features:sheets:test :features:timetracking:test :features:invoices:test
```

Ninguno toca Google.

```bash
./gradlew :app:test
```

Requiere Postgres local según el README; incluye `NoRealApiCallsInTestsIT` y
`FullSchemaRlsIT`.

```bash
SHEETS_TEST_SPREADSHEET_ID=<id hoja de prueba> ./gradlew :features:sheets:sheetsRealTest
```

Opcional y real: crea y borra una pestaña `test-…` en la hoja de PRE.

## Escenarios

1. **Sin credenciales.** Arrancar sin `GOOGLE_SHEETS_CREDENTIALS_FILE`.
   `GET /api/sheets/settings` devuelve `available: false`; fichar, confirmar
   facturas y exportar CSV funcionan igual (US4-6).
2. **Configurar.** Con credenciales, `GET /api/sheets/settings` muestra el correo
   de la cuenta. Hacer `PUT` con los dos enlaces y comprobar el `200`.
   - Con una hoja no compartida: `422 SPREADSHEET_NOT_ACCESSIBLE` con
     `NOT_SHARED`.
   - Con una compartida solo como lectora: `READ_ONLY`.
   - Con la misma hoja dos veces: `409 SAME_SPREADSHEET` (US4).
3. **Apellidos.**
   - Dar de alta a una persona sin `lastName`: `400`.
   - Con `lastName`: el `GET` lo devuelve.
   - Una persona antigua sale con `lastNamePending: true`; editarla y añadirle
     apellidos (US1).
   - El CSV de `GET /api/shifts/export` tiene las columnas `Nombre` y `Apellidos`.
4. **Exportar fichajes.** `POST /api/sheets/shifts/2026/10`, luego consultar
   `GET /api/sheets/tasks/{id}` hasta `DONE`.
   - En la hoja, la pestaña `2026-10` tiene cabecera y una fila por fichaje.
   - Una jornada abierta sale sin salida y una corregida con los valores
     vigentes.
   - Exportar otra vez deja las mismas filas.
   - `GET /api/exports` muestra la exportación con `destination: GOOGLE_SHEETS`
     (US2).
5. **Recibos.**
   - Confirmar una factura de octubre: en menos de 5 minutos (o al momento si el
     trabajador acaba de pasar) aparece en la pestaña `2026-10` de recibos.
   - Corregir su fecha a noviembre: pasa a `2026-11`.
   - Descartarla: desaparece.
   - Un borrador nunca aparece (US3).
6. **Google sin acceso.** Quitar el permiso de la cuenta en la hoja de recibos y
   confirmar una factura.
   - La confirmación funciona.
   - La tarea termina en `FAILED` con motivo `NOT_SHARED`, visible en
     `GET /api/sheets/status`.
   - Devolver el permiso y llamar a `POST /api/sheets/tasks/{id}/retry`: la fila
     aparece (edge cases, SC-004).
7. **Roles.** Con un `MANAGER`, `EMPLOYEE` o `REPRESENTATIVE`, cualquier
   `/api/sheets/**` responde `403` (SC-006).
