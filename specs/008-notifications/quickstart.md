# Quickstart: validar las notificaciones

1. Con una persona `EMPLEADO` y un `ENCARGADO` con cuenta, la persona pide unas
   vacaciones (`POST /api/ausencias`).
2. El `ENCARGADO` consulta `GET /api/notificaciones/no-leidas` → `{"total": 1}` y
   `GET /api/notificaciones` → un `AUSENCIA_PENDIENTE` con el id de la ausencia.
3. El `ENCARGADO` la aprueba; la persona ve un `AUSENCIA_APROBADA`.
4. `POST /api/notificaciones/{id}/leida` con el token de otra persona → `404`.
5. Un fichaje abierto hace más de 10 horas genera, al pasar la comprobación, un
   único `FICHAJE_SIN_SALIDA`.
