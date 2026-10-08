# Contrato HTTP: Alta del personal por el propietario

## `POST /api/auth/registro` — pública, solo el propietario

Como en la 005 (US1), pero `codigoArranque` es **obligatorio**:

| Caso | Respuesta |
|---|---|
| Sin `codigoArranque` | `400 VALIDACION` |
| Código correcto y ningún `ADMIN` | `201 {"estado":"ACTIVA", …}` |
| Código incorrecto, no configurado o ya hay `ADMIN` | `403 CODIGO_ARRANQUE_INVALIDO` |
| Correo con cuenta | `409 EMAIL_YA_REGISTRADO` |

## `POST /api/auth/altas` — `ADMIN`

```json
{
  "nombre": "Ana Martín",
  "documentoIdentidad": "12345678Z",
  "puesto": "Florista",
  "tipoContrato": "PARCIAL",
  "fechaAlta": "2026-10-01",
  "email": "ana@granatum.es",
  "rol": "EMPLEADO"
}
```

→ `201 Created`:

```json
{
  "empleadoId": "…",
  "cuentaId": "…",
  "email": "ana@granatum.es",
  "rol": "EMPLEADO",
  "fichaCreada": true,
  "passwordTemporal": "…"
}
```

`passwordTemporal` aparece **solo aquí**: si se pierde, se restablece con
`POST /api/auth/cuentas/{empleadoId}/restablecer`. Al iniciar sesión con ella,
la sesión solo permite `POST /api/auth/change-password`.

| Error | Cuándo |
|---|---|
| `400 VALIDACION` | Falta un campo, formato de correo, `tipoContrato` fuera de `JORNADA_COMPLETA`, `PARCIAL`, `POR_HORAS` |
| `422 DOCUMENTO_INVALIDO` | DNI o NIE con letra de control incorrecta |
| `409 EMAIL_YA_REGISTRADO` | El correo ya tiene cuenta |
| `409 CUENTA_YA_EXISTE` | La ficha con ese documento ya tiene cuenta |

## Rutas que desaparecen

`GET /api/auth/registros`, `POST /api/auth/registros/{id}/aprobar`,
`POST /api/auth/registros/{id}/rechazar`.
