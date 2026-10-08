# Data Model: Notificaciones

Migración **V23** en `features/notifications`.

## `notificaciones`

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `id` | `UUID` | no | PK. |
| `destinatario_id` | `UUID` | no | Sujeto del token de quien la recibe (id de la persona). |
| `tipo` | `VARCHAR(24)` | no | Uno de los nueve tipos del contrato. |
| `referencia_id` | `UUID` | no | Aquello a lo que se refiere. |
| `creada_en` | `TIMESTAMPTZ` | no | |
| `leida_en` | `TIMESTAMPTZ` | sí | |

- `UNIQUE (destinatario_id, tipo, referencia_id)` (D-002).
- Índice `(destinatario_id, creada_en DESC)` para la bandeja; `(creada_en)` para la limpieza.
- `ENABLE ROW LEVEL SECURITY`.
