# Contrato HTTP: Notificaciones

Todo bajo `/api/notificaciones`, para los cuatro roles; cada persona solo ve las
suyas (el sujeto del token).

| Ruta | Qué |
|---|---|
| `GET /api/notificaciones?soloNoLeidas=false` | Las suyas, de la más reciente, hasta 100 |
| `GET /api/notificaciones/no-leidas` | `{ "total": 3 }` |
| `POST /api/notificaciones/{id}/leida` | `204`; ajena o inexistente → `404 NOTIFICACION_NO_ENCONTRADA` |
| `POST /api/notificaciones/leidas` | Marca todas como leídas → `204` |

Una notificación:

```json
{
  "id": "…",
  "tipo": "AUSENCIA_PENDIENTE",
  "referenciaId": "…",
  "mensaje": "Hay una solicitud de ausencia pendiente de resolver.",
  "creadaEn": "2026-10-08T09:12:00Z",
  "leidaEn": null
}
```

| `tipo` | Destinatarios | `referenciaId` |
|---|---|---|
| `FICHAJE_SIN_SALIDA` | titular | fichaje |
| `FICHAJE_INCOMPLETO` | titular | fichaje |
| `CORRECCION_PENDIENTE` | `ENCARGADO`, `ADMIN` menos quien la pidió | solicitud de corrección |
| `CORRECCION_APROBADA`, `CORRECCION_RECHAZADA` | titular del fichaje | solicitud de corrección |
| `AUSENCIA_PENDIENTE` | `ENCARGADO`, `ADMIN` menos quien la pidió | ausencia |
| `AUSENCIA_APROBADA`, `AUSENCIA_RECHAZADA` | titular | ausencia |
| `REGISTRO_PENDIENTE` | `ADMIN` | solicitud de registro |
