# Data Model: Ausencias y vacaciones

**Fecha**: 2026-10-08 | **Spec**: [spec.md](./spec.md)

Migración **V22** en `features/absences` (numeración global: V1–V21 ocupadas).

## `ausencias`

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `id` | `UUID` | no | PK. |
| `empleado_id` | `UUID` | no | La persona. Sin clave ajena (tabla de otro módulo). |
| `tipo` | `VARCHAR(12)` | no | `VACACIONES`, `PERMISO`, `BAJA_MEDICA`. |
| `causa` | `VARCHAR(24)` | sí | Obligatoria solo en `PERMISO`: `MATRIMONIO`, `NACIMIENTO`, `FALLECIMIENTO_FAMILIAR`, `ENFERMEDAD_FAMILIAR`, `MUDANZA`, `DEBER_INEXCUSABLE`, `OTRO`. |
| `desde` | `DATE` | no | |
| `hasta` | `DATE` | sí | Nula solo en una `BAJA_MEDICA` abierta. `hasta >= desde`, `hasta - desde <= 365`. |
| `estado` | `VARCHAR(10)` | no | `PENDIENTE`, `APROBADA`, `RECHAZADA`, `CANCELADA`. |
| `comentario` | `VARCHAR(500)` | sí | Nunca en `BAJA_MEDICA`. |
| `motivo_rechazo` | `VARCHAR(500)` | sí | Obligatorio con `RECHAZADA`, nulo en otro caso. |
| `solicitada_por` | `UUID` | no | Sujeto del token de quien la pidió o registró. |
| `solicitada_en` | `TIMESTAMPTZ` | no | |
| `resuelta_por`, `resuelta_en` | | sí | Con `APROBADA` o `RECHAZADA`. |
| `cancelada_en` | `TIMESTAMPTZ` | sí | Solo con `CANCELADA`. |
| `version` | `INTEGER` | no | Bloqueo optimista. |

Índices: `(empleado_id, desde)`, `(desde, hasta)`, `(estado)`.

## `derechos_vacaciones`

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `empleado_id` | `UUID` | no | PK compuesta. |
| `anio` | `SMALLINT` | no | PK compuesta, `BETWEEN 2000 AND 2100`. |
| `dias` | `SMALLINT` | no | `BETWEEN 0 AND 366`. |
| `actualizado_por`, `actualizado_en` | | no | |

Ambas con `ENABLE ROW LEVEL SECURITY`; repositorios sin `delete`.
