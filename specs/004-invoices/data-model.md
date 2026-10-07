# Data Model: Facturación con reconocimiento automático

**Fecha**: 2026-10-08 | **Spec**: [spec.md](./spec.md) | **Decisiones**: [research.md](./research.md)

Ocho tablas nuevas en el módulo `features/invoices`, en tres migraciones,
**V17–V19**, siguiendo la numeración global (`inventory` V1–V5,
`timetracking` V6–V11, `auth` V12–V14, exportación V15–V16). Todas con `ENABLE
ROW LEVEL SECURITY` en su propia migración (principio VII), nunca `FORCE`.

Ninguna tabla tiene clave ajena hacia otro módulo. Los autores (`subida_por`,
`autor_id`…) son el sujeto del token, como en `exportaciones`.

---

## V17 — empresa y trimestres

### `empresa`

Una sola fila: los datos propios que deciden qué es emitido y qué recibido
(FR-029, D-014).

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `id` | `SMALLINT` | no | PK, `CHECK (id = 1)`: fila única. |
| `razon_social` | `VARCHAR(200)` | no | |
| `nif` | `VARCHAR(20)` | no | Tal como lo escribe el `ADMIN`. |
| `nif_normalizado` | `VARCHAR(20)` | no | Mayúsculas, sin espacios, guiones ni prefijo `ES`. Válido según D-012. |
| `actualizada_por` | `UUID` | no | |
| `actualizada_en` | `TIMESTAMPTZ` | no | |

### `trimestres`

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `anio` | `SMALLINT` | no | PK compuesta. `CHECK (anio BETWEEN 2000 AND 2100)`. |
| `trimestre` | `SMALLINT` | no | PK compuesta. `CHECK (trimestre BETWEEN 1 AND 4)`. |
| `cerrado` | `BOOLEAN` | no | `DEFAULT FALSE`. |

La fila se crea la primera vez que hace falta, con `INSERT … ON CONFLICT DO
NOTHING`, y después se bloquea con `SELECT … FOR UPDATE` (D-016).

### `trimestre_eventos` — solo inserción

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `id` | `UUID` | no | PK. |
| `anio`, `trimestre` | | no | FK a `trimestres`. |
| `accion` | `VARCHAR(10)` | no | `CHECK (accion IN ('CIERRE', 'REAPERTURA'))`. |
| `motivo` | `VARCHAR(500)` | sí | `CHECK ((accion = 'REAPERTURA') = (motivo IS NOT NULL))`: la reapertura exige motivo (FR-032). |
| `totales` | `JSONB` | sí | Instantánea del reporte en el cierre; nula en la reapertura. `CHECK ((accion = 'CIERRE') = (totales IS NOT NULL))`. |
| `autor_id` | `UUID` | no | |
| `ocurrido_en` | `TIMESTAMPTZ` | no | |

---

## V18 — facturas

### `facturas`

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `id` | `UUID` | no | PK. |
| `estado` | `VARCHAR(20)` | no | `PENDIENTE_RECONOCER`, `BORRADOR`, `CONFIRMADA`, `DESCARTADA`. |
| `tipo` | `VARCHAR(10)` | sí | `EMITIDA` o `RECIBIDA`. Nulo hasta que se clasifica. |
| `emisor_nombre` | `VARCHAR(200)` | sí | |
| `emisor_nif` | `VARCHAR(20)` | sí | Tal como aparece en el documento. |
| `emisor_nif_normalizado` | `VARCHAR(20)` | sí | Para comparar y para la unicidad. |
| `destinatario_nombre` | `VARCHAR(200)` | sí | Nulo en una factura simplificada. |
| `destinatario_nif` | `VARCHAR(20)` | sí | |
| `numero` | `VARCHAR(60)` | sí | |
| `fecha_emision` | `DATE` | sí | Decide el periodo (FR-018). |
| `concepto` | `VARCHAR(500)` | sí | |
| `moneda` | `CHAR(3)` | no | `DEFAULT 'EUR'`. Solo `EUR` se confirma (caso límite). |
| `retenciones` | `NUMERIC(12,2)` | no | `DEFAULT 0`. |
| `total` | `NUMERIC(12,2)` | sí | |
| `rectificativa` | `BOOLEAN` | no | `DEFAULT FALSE`. Solo una rectificativa admite importes negativos. |
| `documento_sha256` | `VARCHAR(64)` | no | Huella del original, para los duplicados exactos. |
| `subida_por`, `subida_en` | | no | |
| `confirmada_por`, `confirmada_en` | | sí | Solo con `estado = CONFIRMADA`. |
| `descartada_por`, `descartada_en` | | sí | Solo con `estado = DESCARTADA`. |
| `version` | `INTEGER` | no | Bloqueo optimista: dos revisiones a la vez no se pisan. |

**Restricciones**:

- `CHECK (estado <> 'CONFIRMADA' OR (tipo IS NOT NULL AND emisor_nif IS NOT NULL
  AND numero IS NOT NULL AND fecha_emision IS NOT NULL AND total IS NOT NULL
  AND moneda = 'EUR'))`: la base de datos tampoco admite una confirmada
  incompleta (FR-013).
- `CHECK ((estado = 'CONFIRMADA') = (confirmada_en IS NOT NULL))` y lo mismo
  para `DESCARTADA`.

**Índices**:

- `UNIQUE (documento_sha256) WHERE estado <> 'DESCARTADA'`: mismo fichero (D-015).
- `UNIQUE (emisor_nif_normalizado, numero, fecha_emision) WHERE estado =
  'CONFIRMADA'`: misma factura (D-015).
- `(fecha_emision)`, `(estado)`, `(emisor_nif_normalizado)`: consulta y reportes.

### `factura_lineas_iva`

Una o varias por factura (FR-003, D-020).

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `id` | `UUID` | no | PK. |
| `factura_id` | `UUID` | no | FK a `facturas`. |
| `orden` | `SMALLINT` | no | Orden en el documento. |
| `tipo_iva` | `NUMERIC(5,2)` | no | `CHECK (tipo_iva BETWEEN 0 AND 100)`. |
| `base` | `NUMERIC(12,2)` | no | |
| `cuota` | `NUMERIC(12,2)` | no | |
| `recargo` | `NUMERIC(12,2)` | no | `DEFAULT 0`: recargo de equivalencia. |
| `causa_sin_cuota` | `VARCHAR(30)` | sí | `EXENTA`, `INVERSION_SUJETO_PASIVO` o `INTRACOMUNITARIA`; obligatoria si la cuota es 0 en una confirmada (lo valida el servicio). |

### `factura_documentos`

El original, intacto (FR-002, FR-025, D-008).

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `factura_id` | `UUID` | no | PK y FK a `facturas`: uno por factura. |
| `contenido` | `BYTEA` | no | Los bytes tal como llegaron. |
| `media_type` | `VARCHAR(40)` | no | `image/jpeg`, `image/png`, `image/webp` o `application/pdf`, deducido de la firma del fichero (D-007). |
| `tamano` | `INTEGER` | no | `CHECK (tamano BETWEEN 1 AND 10485760)`. |
| `nombre_original` | `VARCHAR(255)` | sí | Para la descarga. Puede contener un nombre: nunca se registra en los logs. |

---

## V19 — trazabilidad

### `factura_reconocimientos` — solo inserción

Cada intento de reconocimiento (FR-005, D-009).

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `id` | `UUID` | no | PK. |
| `factura_id` | `UUID` | no | FK. |
| `resultado` | `VARCHAR(20)` | no | `RECONOCIDA`, `NO_ES_FACTURA`, `VARIAS_FACTURAS`, `RECHAZADA`, `ERROR`. |
| `modelo` | `VARCHAR(60)` | no | El modelo que contestó, no solo el configurado. |
| `propuesta` | `JSONB` | sí | Lo que propuso, tal cual. |
| `campos_dudosos` | `JSONB` | sí | Los campos que no se leyeron con seguridad. |
| `tokens_entrada`, `tokens_salida` | `INTEGER` | sí | Coste real (D-002). |
| `error` | `VARCHAR(200)` | sí | Tipo de error, **sin** contenido del documento. |
| `creado_en` | `TIMESTAMPTZ` | no | |

### `factura_cambios` — solo inserción

Cada cambio de una factura ya confirmada (FR-016, FR-017).

| Columna | Tipo | Nulo | Regla |
|---|---|---|---|
| `id` | `UUID` | no | PK. |
| `factura_id` | `UUID` | no | FK. |
| `accion` | `VARCHAR(20)` | no | `CORRECCION`, `DESCARTE` o `RECLASIFICACION`. |
| `valores_anteriores` | `JSONB` | no | La factura completa, con su desglose, antes del cambio. |
| `autor_id` | `UUID` | no | |
| `ocurrido_en` | `TIMESTAMPTZ` | no | |

---

## Estados de una factura

```
                    reconocida / rellenada a mano
 PENDIENTE_RECONOCER ───────────────────────────► BORRADOR ──confirmar──► CONFIRMADA
          │  ▲                                        │                      │  ▲
          │  └──── reintentar (sigue fallando) ────┘  │                      │  │ corregir
          │                                           │                      │  │ (trimestre
          └───────────── descartar ───────────────────┴──── descartar ───────┤──┘  abierto)
                                                                             ▼
                                                                        DESCARTADA (final)
```

- **Confirmar** exige las validaciones de FR-011 a FR-014 y que el trimestre de
  su fecha esté abierto.
- **Corregir una confirmada** y **descartar una confirmada** exigen el trimestre
  abierto y dejan una fila en `factura_cambios` (FR-016, FR-031).
- **Descartar** no borra: la factura sigue consultable con su original (FR-015).
- **Reintentar el reconocimiento** solo desde `PENDIENTE_RECONOCER` o `BORRADOR`.

## Lo que no se borra

Ninguna de estas tablas tiene borrado: sus repositorios extienden
`Repository<T, ID>` sin `delete`, como en `timetracking` y `auth`, y un test por
reflexión lo comprueba. La conservación mínima es de seis años, y la depuración,
si llega, será una feature aparte.

## Modelo de dominio (sin JPA, sin HTTP)

```
Factura(id, estado, tipo?, emisor: Parte?, destinatario: Parte?, numero?, fechaEmision?,
        concepto?, moneda, lineas: List<LineaIva>, retenciones, total?, rectificativa)
Parte(nombre?, nif?)
LineaIva(tipoIva, base, cuota, recargo, causaSinCuota?)
PropuestaReconocida(esFactura, variasFacturas, ...los campos de Factura..., camposDudosos)
Periodo = Mensual(anio, mes) | Trimestral(anio, trimestre) | Anual(anio)
Reporte(periodo, emitidas: TotalesGrupo, recibidas: TotalesGrupo, pendientes, trimestresCerrados)
TotalesGrupo(numero, base, cuotaPorTipo: Map<tipo, importe>, recargo, retenciones, total,
             sinCuota: Map<causa, base>)
```

`ValidadorFactura` (FR-011 a FR-013) y `CalculadoraReporte` (FR-019 a FR-023) son
funciones puras, con tests unitarios.
