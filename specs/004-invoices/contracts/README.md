# Contrato de la API: facturación

**Spec**: [../spec.md](../spec.md) | **Decisiones**: [../research.md](../research.md) | **Datos**: [../data-model.md](../data-model.md)

Todas las rutas cuelgan de `/api/facturacion` y son **solo `ADMIN`**, con una
única regla en `SecurityConfig` (D-017). Cualquier otro rol recibe
`403 FORBIDDEN`, y sin token `401 NO_AUTENTICADO`. Los errores usan el formato
único `{ "code", "message" }` (principio VIII), emitido por el
`InvoicesExceptionHandler` del módulo con `@Order(HIGHEST_PRECEDENCE)`.

Importes: siempre **texto decimal con punto y dos decimales** (`"1234.56"`),
nunca número JSON. Fechas: `yyyy-MM-dd`. Instantes: ISO 8601 en UTC.

| Método | Ruta | Para qué | Requisitos |
|---|---|---|---|
| `GET` / `PUT` | `/api/facturacion/empresa` | Datos de la empresa | FR-029 |
| `POST` | `/api/facturacion/facturas` | Subir una o varias | FR-001 a FR-008 |
| `GET` | `/api/facturacion/facturas` | Listar con filtros | FR-024 |
| `GET` | `/api/facturacion/facturas/{id}` | Ver una, con avisos | FR-009 a FR-014 |
| `PUT` | `/api/facturacion/facturas/{id}` | Corregir | FR-010, FR-016 |
| `POST` | `/api/facturacion/facturas/{id}/confirmar` | Confirmar | FR-011 a FR-014 |
| `POST` | `/api/facturacion/facturas/{id}/descartar` | Descartar | FR-015 |
| `POST` | `/api/facturacion/facturas/{id}/reconocer` | Reintentar el reconocimiento | FR-006 |
| `GET` | `/api/facturacion/facturas/{id}/original` | Descargar el original | FR-025 |
| `GET` | `/api/facturacion/facturas/{id}/historial` | Reconocimientos y cambios | FR-005, FR-017 |
| `GET` | `/api/facturacion/reportes` | Reporte de un periodo | FR-018 a FR-023 |
| `GET` | `/api/facturacion/trimestres?anio=` | Estado de los trimestres | FR-033 |
| `POST` | `/api/facturacion/trimestres/{anio}/{trimestre}/cerrar` | Cerrar | FR-030 |
| `POST` | `/api/facturacion/trimestres/{anio}/{trimestre}/reabrir` | Reabrir con motivo | FR-032 |

---

## `GET` / `PUT /api/facturacion/empresa`

```json
{ "razonSocial": "Floristería Granatum S.L.", "nif": "B12345674", "reconocimientoActivo": true }
```

`PUT` recibe `razonSocial` y `nif`. `reconocimientoActivo` es de solo lectura: dice
si hay clave de la API configurada (D-006). `GET` sin configurar → `404
EMPRESA_SIN_CONFIGURAR`. NIF con control inválido → `422 NIF_INVALIDO`.

---

## `POST /api/facturacion/facturas`

`multipart/form-data` con uno o varios ficheros en la parte `ficheros`. Cada
fichero es una factura.

**`202 Accepted`**: un resultado por fichero, en el orden en que llegaron. Los
aceptados quedan `PENDIENTE_RECONOCER` y se reconocen en segundo plano (D-005).

```json
[
  { "fichero": 1, "resultado": "ACEPTADA", "facturaId": "…" },
  { "fichero": 2, "resultado": "DUPLICADA", "facturaId": "…id de la existente…" },
  { "fichero": 3, "resultado": "FORMATO_NO_ADMITIDO", "facturaId": null }
]
```

`resultado`: `ACEPTADA`, `DUPLICADA` (mismo fichero exacto que una no descartada),
`FORMATO_NO_ADMITIDO` (ni JPEG, ni PNG, ni WebP, ni PDF, según su firma),
`DEMASIADO_GRANDE` (más de 10 MB), `VACIO`, `PDF_NO_LEGIBLE` (cifrado o con más
de 20 páginas).

Se identifica cada fichero por su **posición** y no por su nombre, para que el
nombre, que puede ser personal, no viaje en la respuesta ni acabe en un log.

| Estado | `code` | Cuándo |
|---|---|---|
| `400` | `VALIDACION` | Ningún fichero en la petición. |
| `413` | `PETICION_DEMASIADO_GRANDE` | La petición entera supera el máximo configurado (50 MB por defecto). |

El límite de **10 MB es por fichero** y lo aplica el servicio, de modo que un
fichero grande sale como `DEMASIADO_GRANDE` sin hacer fallar a los demás. El
límite de multipart de Spring es el de la petición entera.

---

## `GET /api/facturacion/facturas`

Filtros opcionales y combinables: `desde`, `hasta` (fecha de emisión), `parte`
(texto que se busca en el nombre o el NIF del emisor o del destinatario), `tipo`
(`EMITIDA`/`RECIBIDA`), `estado`. Orden: fecha de emisión descendente; las
facturas aún sin fecha, primero. Paginado (`pagina`, `tamano`, por defecto 50).

Cada elemento es el resumen: `id`, `estado`, `tipo`, `emisor`, `destinatario`,
`numero`, `fechaEmision`, `total`, `numeroAvisos`.

---

## `GET /api/facturacion/facturas/{id}`

```json
{
  "id": "…", "estado": "BORRADOR", "tipo": "RECIBIDA", "version": 3,
  "emisor": { "nombre": "Flores del Sur S.L.", "nif": "B98765432" },
  "destinatario": { "nombre": "Floristería Granatum S.L.", "nif": "B12345674" },
  "numero": "2026-0815", "fechaEmision": "2026-10-02", "concepto": "Rosas rojas",
  "moneda": "EUR", "rectificativa": false,
  "lineas": [
    { "tipoIva": "21.00", "base": "100.00", "cuota": "21.00", "recargo": "0.00", "causaSinCuota": null },
    { "tipoIva": "10.00", "base": "50.00",  "cuota": "5.00",  "recargo": "0.00", "causaSinCuota": null }
  ],
  "retenciones": "0.00", "total": "176.00",
  "trimestreCerrado": false,
  "reconocimiento": { "resultado": "RECONOCIDA", "camposDudosos": ["numero"] },
  "avisos": [
    { "campo": "total", "codigo": "NO_CUADRA", "mensaje": "Bases + IVA + recargo − retenciones = 176.00; el total es 175.00" },
    { "campo": "numero", "codigo": "DUDOSO", "mensaje": "El reconocimiento no lo leyó con seguridad" }
  ]
}
```

**`avisos`** forma parte del recurso, no es un error: es lo que impide confirmar,
o lo que conviene revisar. Códigos: `OBLIGATORIO`, `NO_CUADRA`, `NIF_INVALIDO`,
`DUDOSO`, `DUPLICADA` (misma factura confirmada con otro fichero), `MONEDA`,
`FECHA_FUTURA`, `FECHA_ANTIGUA`, `NO_ES_DE_LA_EMPRESA`, `SIN_CAUSA_CUOTA_CERO`,
`NO_ES_FACTURA`, `VARIAS_FACTURAS`, `TRIMESTRE_CERRADO`. Unos **bloquean** la
confirmación (`OBLIGATORIO`, `NO_CUADRA`, `NIF_INVALIDO`, `DUPLICADA`, `MONEDA`,
`SIN_CAUSA_CUOTA_CERO`, `TRIMESTRE_CERRADO`) y otros solo avisan (`bloquea: true`
/ `false` en cada aviso).

`404 FACTURA_NOT_FOUND` si no existe.

---

## `PUT /api/facturacion/facturas/{id}`

Cuerpo: los campos editables completos (los del `GET` salvo `id`, `estado`,
`reconocimiento`, `avisos` y `trimestreCerrado`) más la `version` que se leyó.
Sustituye los valores; el desglose se sustituye entero. Devuelve el recurso con
sus avisos recalculados.

| Estado | `code` | Cuándo |
|---|---|---|
| `409` | `VERSION_DESACTUALIZADA` | Otra revisión la cambió desde que se leyó. |
| `409` | `TRIMESTRE_CERRADO` | Es una confirmada de un trimestre cerrado, o la nueva fecha cae en uno. |
| `409` | `ESTADO_NO_PERMITIDO` | Está descartada. |

En una **confirmada** deja una fila en el historial con los valores anteriores
(FR-016), y no se aplica si los nuevos valores tienen avisos bloqueantes: una
confirmada nunca deja de estar completa.

---

## `POST /api/facturacion/facturas/{id}/confirmar`

Cuerpo: `{ "version": 3 }`. Devuelve el recurso confirmado.

| Estado | `code` | Cuándo |
|---|---|---|
| `422` | `FACTURA_INCOHERENTE` | Tiene avisos bloqueantes. El mensaje los resume; el detalle está en `GET …/{id}`. |
| `409` | `FACTURA_DUPLICADA` | Ya hay una confirmada con el mismo emisor, número y fecha (D-015). |
| `409` | `TRIMESTRE_CERRADO` | Su fecha cae en un trimestre cerrado (FR-031). |
| `409` | `EMPRESA_SIN_CONFIGURAR` | Sin NIF propio no se sabe si es emitida o recibida. |
| `409` | `ESTADO_NO_PERMITIDO` / `VERSION_DESACTUALIZADA` | Como arriba. |

## `POST /api/facturacion/facturas/{id}/descartar`

Cuerpo: `{ "version": 3 }`. Desde `PENDIENTE_RECONOCER` o `BORRADOR` siempre; desde
`CONFIRMADA` solo con el trimestre abierto, y queda en el historial.

## `POST /api/facturacion/facturas/{id}/reconocer`

`202`. Vuelve a encolarla. Solo desde `PENDIENTE_RECONOCER` o `BORRADOR`
(`409 ESTADO_NO_PERMITIDO` si no). Sin clave de API: `409
RECONOCIMIENTO_NO_DISPONIBLE`.

## `GET /api/facturacion/facturas/{id}/original`

Los bytes exactos que se subieron (FR-025), con su `Content-Type`, y
`Content-Disposition: attachment; filename="factura-<id>.<ext>"`. El nombre lleva
el id, **no** el nombre original, por la misma razón que en la feature 003.

## `GET /api/facturacion/facturas/{id}/historial`

```json
{
  "reconocimientos": [ { "resultado": "RECONOCIDA", "modelo": "claude-opus-5-5", "creadoEn": "…", "propuesta": { … } } ],
  "cambios": [ { "accion": "CORRECCION", "autorId": "…", "ocurridoEn": "…", "valoresAnteriores": { … } } ]
}
```

---

## `GET /api/facturacion/reportes`

| Parámetro | Valor |
|---|---|
| `periodo` | `MENSUAL`, `TRIMESTRAL` o `ANUAL` |
| `anio` | p. ej. `2026` |
| `mes` | `1`–`12`, solo con `MENSUAL` |
| `trimestre` | `1`–`4`, solo con `TRIMESTRAL` |
| `formato` | `json` (por defecto), `csv` o `pdf` |

**`json`**:

```json
{
  "periodo": { "tipo": "TRIMESTRAL", "anio": 2026, "trimestre": 3, "desde": "2026-07-01", "hasta": "2026-09-30" },
  "emitidas":  { "facturas": 12, "base": "8400.00", "ivaPorTipo": { "21.00": "1764.00" }, "recargo": "0.00", "retenciones": "0.00", "total": "10164.00", "sinCuota": {} },
  "recibidas": { "facturas": 31, "base": "5210.40", "ivaPorTipo": { "21.00": "903.00", "10.00": "90.00" }, "recargo": "0.00", "retenciones": "150.00", "total": "6053.40", "sinCuota": { "INTRACOMUNITARIA": "320.00" } },
  "pendientes": 0,
  "trimestresCerrados": [ { "anio": 2026, "trimestre": 3, "desde": "2026-10-15T09:12:00Z" } ]
}
```

**`csv`**: UTF-8 con BOM, separador `;`, CRLF y protección contra fórmulas, con el
mismo `FormatoCsv` que la exportación de jornada (D-011). Una fila por concepto y
grupo; los importes con **coma decimal** (`1764,00`), que es lo que una hoja de
cálculo en español lee como número. Los importes van en **celdas numéricas**: un
total negativo por rectificativas sale `-150,00` y no `'-150,00`, que Excel
leería como texto. La protección contra fórmulas se aplica a las celdas de
texto. Nombre: `reporte-facturacion_<periodo>.csv`,
por ejemplo `reporte-facturacion_2026-T3.csv`.

**`pdf`**: las mismas cifras en una tabla, con el periodo, la fecha del cálculo,
el aviso de pendientes y el estado de cierre. Nombre:
`reporte-facturacion_<periodo>.pdf`.

| Estado | `code` | Cuándo |
|---|---|---|
| `400` | `VALIDACION` | Falta `anio`, o `mes`/`trimestre` según el periodo. |
| `422` | `PERIODO_INVALIDO` | `mes` fuera de 1–12, `trimestre` fuera de 1–4, o un parámetro que no corresponde al periodo. |

---

## Trimestres

`GET /api/facturacion/trimestres?anio=2026`:

```json
[ { "trimestre": 1, "cerrado": true, "eventos": [ { "accion": "CIERRE", "autorId": "…", "ocurridoEn": "…" } ] }, … ]
```

`POST …/cerrar` (sin cuerpo): `409 TRIMESTRE_CON_PENDIENTES` si quedan facturas
pendientes de reconocer o borradores con fecha en el trimestre, y `409
TRIMESTRE_CERRADO` si ya lo está. Guarda la instantánea de los totales (D-016).

`POST …/reabrir` con `{ "motivo": "…" }` (de 10 a 500 caracteres; si no, `400
VALIDACION`): `409 TRIMESTRE_ABIERTO` si no estaba cerrado.

---

## Códigos nuevos

`FACTURA_NOT_FOUND`, `EMPRESA_SIN_CONFIGURAR`, `NIF_INVALIDO`,
`FACTURA_INCOHERENTE`, `FACTURA_DUPLICADA`, `TRIMESTRE_CERRADO`,
`TRIMESTRE_ABIERTO`, `TRIMESTRE_CON_PENDIENTES`, `ESTADO_NO_PERMITIDO`,
`VERSION_DESACTUALIZADA`, `RECONOCIMIENTO_NO_DISPONIBLE`, `PERIODO_INVALIDO`,
`PETICION_DEMASIADO_GRANDE`. Las clases de excepción de este módulo llevan
nombres que no existen en ningún otro (lección de la feature 002, protegida por
`SinColisionDeClasesIT`).
