# Data Model: Registro del personal

**Fecha**: 2026-10-08 | **Spec**: [spec.md](./spec.md) | **Decisiones**: [research.md](./research.md)

Dos migraciones en `features/auth`, siguiendo la numeración global (V1–V19
ocupadas): **V20** crea `solicitudes_registro` con RLS; **V21** amplía los tipos
de `eventos_seguridad`.

## V20 — `solicitudes_registro`

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `id` | `UUID` | no | PK. |
| `estado` | `VARCHAR(12)` | no | `PENDIENTE`, `APROBADA`, `RECHAZADA`, `CADUCADA`, `ANULADA`. |
| `email` | `VARCHAR(254)` | sí | Normalizado (minúsculas, sin espacios). Solo mientras `PENDIENTE`. |
| `nombre` | `VARCHAR(150)` | sí | Solo mientras `PENDIENTE`. Mismo tamaño que `empleados.nombre`. |
| `documento_identidad` | `VARCHAR(20)` | sí | Normalizado y válido (DNI o NIE). Solo mientras `PENDIENTE`. |
| `password_hash` | `VARCHAR(255)` | sí | Salida del `PasswordEncoder` de la 002. Solo mientras `PENDIENTE`. |
| `codigo_hash` | `VARCHAR(64)` | sí | `SHA-256(id ‖ código)` en hexadecimal (D-001). Solo mientras `PENDIENTE`. |
| `intentos_codigo` | `SMALLINT` | no | `DEFAULT 0`, `CHECK (intentos_codigo BETWEEN 0 AND 5)`. |
| `creada_en` | `TIMESTAMPTZ` | no | |
| `resuelta_en` | `TIMESTAMPTZ` | sí | Obligatoria fuera de `PENDIENTE`. |
| `resuelta_por` | `UUID` | sí | Sujeto del token del `ADMIN`; nulo si la resolvió el sistema (caducidad, anulación automática). |
| `cuenta_id` | `UUID` | sí | La cuenta que salió de ella; solo con `APROBADA`. |

**Restricciones**:

- `CHECK (estado <> 'PENDIENTE' OR (email IS NOT NULL AND nombre IS NOT NULL AND
  documento_identidad IS NOT NULL AND password_hash IS NOT NULL AND codigo_hash
  IS NOT NULL))`: una pendiente está completa.
- `CHECK (estado = 'PENDIENTE' OR (email IS NULL AND nombre IS NULL AND
  documento_identidad IS NULL AND password_hash IS NULL AND codigo_hash IS
  NULL))`: una resuelta no guarda datos personales (FR-026, SC-005).
- `CHECK ((estado = 'PENDIENTE') = (resuelta_en IS NULL))`.
- `CHECK ((estado = 'APROBADA') = (cuenta_id IS NOT NULL))`.

**Índices**: `(estado, creada_en)` para la lista y la caducidad; `(email) WHERE
estado = 'PENDIENTE'` y `(documento_identidad) WHERE estado = 'PENDIENTE'` para
anular duplicadas. **No** son únicos: varias pendientes con el mismo correo son
legítimas (D-001).

`cuenta_id` es clave ajena a `cuentas_acceso(id)`: las dos tablas son de `auth`,
así que el principio I no lo impide. Con `empleados` (de `timetracking`) no hay
clave ajena: la ficha se maneja solo a través del contrato.

**Sin borrado**: el repositorio extiende `Repository<T, ID>` sin `delete`; la
caducidad es un `UPDATE`.

`ALTER TABLE solicitudes_registro ENABLE ROW LEVEL SECURITY;` en la misma
migración, nunca `FORCE`.

## V21 — tipos de `eventos_seguridad`

`ALTER TABLE eventos_seguridad DROP CONSTRAINT ck_eventos_seguridad_tipo` y
`ADD CONSTRAINT` con los trece tipos de la V14 más los ocho de D-009. No se edita
la V14 (principio II).

## Estados de una solicitud

```
                 aprobar (código correcto)
 PENDIENTE ─────────────────────────────► APROBADA
    │ │ │
    │ │ └── rechazar (ADMIN) ───────────► RECHAZADA
    │ └──── 7 días sin resolver ────────► CADUCADA
    └────── 5 códigos incorrectos,
            otra aprobada con su correo
            o documento, correo ya con
            cuenta, primer ADMIN ───────► ANULADA
```

Todos los estados distintos de `PENDIENTE` son finales.

## Contrato `FichasPersonal` (common)

```
FichasPersonal
  buscarPorDocumento(documento: String): EntityId?   # documento ya normalizado
  crear(alta: AltaFichaPersonal): EntityId
AltaFichaPersonal(nombre, documento, puesto, tipoContrato, fechaAlta)
```

## Modelo de dominio (sin JPA)

```
SolicitudRegistro(id, estado, email?, nombre?, documento?, creadaEn, resueltaEn?, resueltaPor?, cuentaId?)
EstadoSolicitudRegistro = PENDIENTE | APROBADA | RECHAZADA | CADUCADA | ANULADA
CodigoVerificacion: generar(): String; huella(id, codigo): String; coincide(id, codigo, huella): Boolean
```
