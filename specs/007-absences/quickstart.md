# Quickstart: validar ausencias y vacaciones

**Spec**: [spec.md](./spec.md) | **Contrato**: [contracts/README.md](./contracts/README.md)

Con la aplicación en marcha y dos personas con cuenta: `$EMP` (token de un
`EMPLEADO`) y `$ENC` (token de un `ENCARGADO`). `A=http://localhost:8080/api/ausencias`.

## 1. Vacaciones — US1

```bash
curl -s -X POST $A -H "Authorization: Bearer $EMP" -H 'Content-Type: application/json' \
  -d '{"tipo":"VACACIONES","desde":"2026-12-01","hasta":"2026-12-12"}'
curl -s "$A/saldo?anio=2026" -H "Authorization: Bearer $EMP"
```

**Esperado**: `201` `PENDIENTE`; saldo `derecho 30, pendientes 12, disponibles 18`.
Aprobar con `$ENC` (`POST $A/{id}/aprobar`) → `APROBADA`; aprobarla con `$EMP` → `403`.

## 2. Solapamiento — US2

Pedir del 10 al 11 de diciembre → `409 AUSENCIA_SOLAPADA`.

## 3. Baja — US3

`POST $A/registro` con `$ENC`, `{"empleadoId":"…","tipo":"BAJA_MEDICA","desde":"2026-10-08"}` → `201` `APROBADA` sin fin;
con `"comentario"` → `422 DATOS_AUSENCIA_INVALIDOS`; `POST $A/{id}/alta` `{"hasta":"2026-10-15"}` → cerrada.

## 4. Cancelar — US4

Cancelar la de diciembre con `$EMP` → `CANCELADA` y el saldo vuelve a 30.

## 5. Permisos

`GET $A` con un token de `REPRESENTANTE` → `403`; con `$EMP` y `?empleadoId=<otro>` → solo las suyas.
