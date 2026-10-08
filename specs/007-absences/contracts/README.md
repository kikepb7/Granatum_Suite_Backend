# Contrato HTTP: Ausencias y vacaciones

Todo bajo `/api/ausencias`. `REPRESENTANTE` → `403` en todas. Errores `{code, message}`.

| Ruta | Quién | Qué |
|---|---|---|
| `POST /api/ausencias` | `EMPLEADO`, `ENCARGADO`, `ADMIN` | Solicitud propia: `{tipo: VACACIONES|PERMISO, causa?, desde, hasta, comentario?}` → `201` `PENDIENTE` |
| `POST /api/ausencias/registro` | `ENCARGADO`, `ADMIN` | En nombre de otra persona: `{empleadoId, tipo, causa?, desde, hasta?, comentario?}` → `201` `APROBADA` (bajas incluidas) |
| `GET /api/ausencias?empleadoId&desde&hasta&estado` | todos menos `REPRESENTANTE` | Un `EMPLEADO` solo recibe las suyas, pida lo que pida |
| `GET /api/ausencias/{id}` | idem | Ajena para un `EMPLEADO` → `404` |
| `POST /api/ausencias/{id}/aprobar` | `ENCARGADO`, `ADMIN` | Propia → `403 RESOLUCION_PROPIA` |
| `POST /api/ausencias/{id}/rechazar` | `ENCARGADO`, `ADMIN` | `{motivo}` obligatorio |
| `POST /api/ausencias/{id}/cancelar` | la persona | Pendiente, o aprobada que no ha empezado |
| `POST /api/ausencias/{id}/alta` | `ENCARGADO`, `ADMIN` | `{hasta}`: cierra una baja abierta |
| `GET /api/ausencias/saldo?anio&empleadoId?` | todos menos `REPRESENTANTE` | `{anio, derecho, aprobados, pendientes, disponibles}` |
| `PUT /api/ausencias/derechos/{empleadoId}/{anio}` | `ADMIN` | `{dias}` |

| Error | Cuándo |
|---|---|
| `409 AUSENCIA_SOLAPADA` | Solapa con otra no cancelada ni rechazada |
| `422 SALDO_INSUFICIENTE` | Vacaciones por encima de lo disponible en algún año |
| `422 RANGO_INVALIDO` | Fin antes de inicio, más de 366 días, o vacaciones propias en el pasado |
| `422 DATOS_AUSENCIA_INVALIDOS` | Causa en lo que no es permiso, permiso sin causa, comentario en una baja, baja abierta que no es baja |
| `409 AUSENCIA_NO_MODIFICABLE` | Transición no permitida (ya resuelta, ya empezada, baja ya cerrada) |
| `403 RESOLUCION_PROPIA` | Resolver o registrar para uno mismo |
| `404 AUSENCIA_NO_ENCONTRADA` | No existe, o es ajena para un `EMPLEADO` |
| `404 EMPLEADO_NO_ENCONTRADO` / `409 EMPLEADO_INACTIVO` | La persona no existe o no está activa |
