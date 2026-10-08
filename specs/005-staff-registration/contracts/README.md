# Contrato HTTP: Registro del personal

Errores con el formato único `{ "code", "message" }` (principio VIII). Validación
de formato: `400 VALIDACION`, como en el resto de la API.

## `POST /api/auth/registro` — pública

```json
{
  "email": "ana@granatum.es",
  "password": "Una-contraseña-larga-2026",
  "nombre": "Ana Martín",
  "documentoIdentidad": "12345678Z",
  "codigoArranque": null
}
```

| Campo | Regla |
|---|---|
| `email` | Obligatorio, formato de correo, ≤ 254. |
| `password` | Obligatoria; política de la 002 (`422 PASSWORD_DEBIL` con `requisitos`). |
| `nombre` | Obligatorio, 1–150, sin solo espacios. |
| `documentoIdentidad` | Obligatorio; DNI o NIE válido tras normalizar (`422 DOCUMENTO_INVALIDO`). |
| `codigoArranque` | Opcional. Solo para el primer `ADMIN`. |

**Sin `codigoArranque`** → `202 Accepted`, **siempre igual**, exista o no el
correo (FR-006):

```json
{
  "estado": "PENDIENTE_APROBACION",
  "codigoVerificacion": "K7QM3XRP",
  "mensaje": "Solicitud recibida. Dile este código a la persona que administra Granatum para que la apruebe."
}
```

**Con `codigoArranque` correcto** y sin ningún `ADMIN` → `201 Created`:

```json
{ "estado": "ACTIVA", "codigoVerificacion": null, "mensaje": "Cuenta de administración creada. Ya puedes iniciar sesión." }
```

| Error | Cuándo |
|---|---|
| `403 CODIGO_ARRANQUE_INVALIDO` | Código incorrecto, no configurado o ya existe un `ADMIN` (las tres, igual). |
| `409 EMAIL_YA_REGISTRADO` | Solo en el arranque: el correo ya tiene cuenta. |
| `503 REGISTRO_NO_DISPONIBLE` | Hay 50 pendientes (`Retry-After: 3600`). |
| `503 SERVICIO_SATURADO` | Cola del hash llena (el de la 002, `Retry-After: 1`). |

## `GET /api/auth/registros` — `ADMIN`

Solo las pendientes, de la más antigua a la más reciente.

```json
[
  {
    "id": "…",
    "email": "ana@granatum.es",
    "nombre": "Ana Martín",
    "documentoIdentidad": "12345678Z",
    "creadaEn": "2026-10-08T09:12:00Z",
    "empleadoExistenteId": "…"
  }
]
```

`empleadoExistenteId` es `null` si no hay ficha con ese documento: entonces
aprobar exige los datos de la ficha. Nunca aparecen contraseña ni código.

## `POST /api/auth/registros/{id}/aprobar` — `ADMIN`

```json
{
  "codigoVerificacion": "K7QM3XRP",
  "rol": "EMPLEADO",
  "puesto": "Florista",
  "tipoContrato": "JORNADA_COMPLETA",
  "fechaAlta": "2026-10-01"
}
```

`puesto`, `tipoContrato` y `fechaAlta` solo se usan si no hay ficha con ese
documento; si la hay, se ignoran. → `201 Created`:

```json
{ "solicitudId": "…", "cuentaId": "…", "empleadoId": "…", "rol": "EMPLEADO", "fichaCreada": true }
```

| Error | Cuándo |
|---|---|
| `404 SOLICITUD_NO_ENCONTRADA` | No existe. |
| `409 SOLICITUD_NO_PENDIENTE` | Ya resuelta (también si este intento la anuló). |
| `422 CODIGO_INCORRECTO` | El código no coincide; cuenta un intento. |
| `422 DATOS_FICHA_REQUERIDOS` | No hay ficha y faltan puesto, tipo de contrato o fecha de alta. |
| `409 CUENTA_YA_EXISTE` | La ficha con ese documento ya tiene cuenta. |
| `409 EMAIL_YA_REGISTRADO` | El correo ya tiene cuenta; la solicitud queda anulada. |

## `POST /api/auth/registros/{id}/rechazar` — `ADMIN`

Sin cuerpo. → `204 No Content`. `404` / `409 SOLICITUD_NO_PENDIENTE` como arriba.

## Sin cambios

`POST /api/auth/login` responde `401 CREDENCIALES_INVALIDAS` a quien tiene una
solicitud sin aprobar (FR-031): no existe cuenta. `POST /api/auth/cuentas` (alta
con contraseña temporal) sigue igual (FR-030).
