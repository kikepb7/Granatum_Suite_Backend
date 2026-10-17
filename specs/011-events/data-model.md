# Modelo de datos: eventos

Migración `V27__create_eventos_tables.sql` en `features/events`.

## eventos

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | UUID PK | |
| `nombre` | VARCHAR(100) NOT NULL | 1–100 |
| `descripcion` | VARCHAR(500) | opcional |
| `fecha` | DATE NOT NULL | hoy o futura al crear/editar (servicio) |
| `estado` | VARCHAR(20) NOT NULL | `CHECK (estado IN ('PROGRAMADO','FINALIZADO'))` |
| `creado_por` | UUID NOT NULL | sujeto del JWT; sin FK (D-007) |
| `finalizado_en` | TIMESTAMPTZ | solo si `FINALIZADO` (`CHECK`) |
| `created_at`, `updated_at` | TIMESTAMPTZ NOT NULL | auditoría JPA |

Índice `idx_eventos_estado_fecha (estado, fecha)` para la tarea y la lista.

## evento_materiales

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | UUID PK | |
| `evento_id` | UUID NOT NULL | FK `eventos(id) ON DELETE CASCADE` (misma feature) |
| `material_id` | UUID NOT NULL | id en inventario, **sin FK** (D-007) |
| `cantidad` | INTEGER NOT NULL | `CHECK (cantidad > 0)` |
| `descripcion` | VARCHAR(500) NOT NULL | copia al asignar |
| `categoria` | VARCHAR(100) NOT NULL | copia al asignar |

`UNIQUE (evento_id, material_id)`; índice `idx_evento_materiales_material (material_id)` para
`UsoMateriales`.

Ambas tablas activan RLS en la misma migración (principio VII).

## Transiciones

```
PROGRAMADO --(tarea diaria, fecha < hoy | finalizar)--> FINALIZADO   [devuelve todo]
PROGRAMADO --(borrar)--> ∅                                           [devuelve todo]
FINALIZADO --(borrar)--> ∅                                           [sin movimientos]
```
