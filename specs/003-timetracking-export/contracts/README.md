# Contrato de la API: exportación del registro de jornada

**Spec**: [../spec.md](../spec.md) | **Decisiones**: [../research.md](../research.md)

Rutas bajo `/api` (principio VIII). Los errores usan el formato único
`{ "code", "message" }`, emitido por `TimetrackingExceptionHandler` con su
`@Order(HIGHEST_PRECEDENCE)`, que ya existe. Las rutas se declaran en
`SecurityConfig` (principio IV).

| Método | Ruta | Autorización | Requisitos |
|--------|------|--------------|------------|
| `GET` | `/api/fichajes/export` | cualquier rol de lectura; propiedad en el servicio | FR-001 a FR-018 |
| `GET` | `/api/fichajes/empleado/{empleadoId}/resumen/descarga` | cualquier rol de lectura; propiedad en el servicio | FR-019 a FR-023 |
| `GET` | `/api/exportaciones` | `ADMIN` | FR-029 |
| `POST` | `/api/exportaciones/verificar` | `ADMIN` | FR-028 |

Las dos primeras quedan cubiertas por la regla existente de `GET /api/fichajes/**`,
que ya admite a los cuatro roles. `/api/exportaciones/**` necesita una regla
**nueva** con `hasRole("ADMIN")` (D-014).

---

## `GET /api/fichajes/export`

| Parámetro | Obligatorio | Valor |
|---|---|---|
| `formato` | no | `csv` (único valor admitido; otro → `422`) |
| `desde` | sí | `yyyy-MM-dd` |
| `hasta` | sí | `yyyy-MM-dd`, ≥ `desde` |
| `empleadoId` | no | `EMPLEADO`: omitido o el suyo. Resto: omitido = toda la plantilla. |

**`200 OK`**

```
Content-Type: text/csv; charset=UTF-8
Content-Disposition: attachment; filename="registro-jornada_<empleadoId|plantilla>_<desde>_<hasta>.csv"
X-Registro-Disponible-Desde: <yyyy-MM-dd>      (solo si el rango se recortó, D-009)
```

El cuerpo se envía en streaming. El nombre del fichero lleva el **rango efectivo**
y el id de la persona, **nunca su nombre** (D-017).

**Cuerpo**: BOM UTF-8, separador `;`, fin de línea CRLF, una cabecera y una fila
por fichaje (formato en D-004):

```
Persona;Documento;Puesto;Fecha;Entrada;Salida;Pausas;Horas trabajadas;Minutos trabajados;Estado;Completado a posteriori;Corregido;Entrada original;Salida original;Pausas originales;Correcciones
Ana Pérez;12345678Z;Florista;2026-10-05;2026-10-05 07:00;2026-10-05 16:00;COMIDA 11:00-11:30;8:30;510;CERRADO;No;No;;;;
Ana Pérez;12345678Z;Florista;2026-10-06;2026-10-06 07:00;2026-10-06 16:00;COMIDA 11:00-11:45;8:15;495;CERRADO;No;Sí;2026-10-06 07:00;2026-10-06 16:00;;2026-10-07 09:12 solicitada por Ana Pérez, aprobada por Luis Gil
Ana Pérez;12345678Z;Florista;2026-10-07;2026-10-07 07:00;;;;;EN_CURSO;No;No;;;;
```

- Con rol `REPRESENTANTE`, la columna `Documento` **no existe** (D-012).
- Un fichaje abierto o incompleto deja vacías las horas y los minutos, nunca `0` (FR-006).
- Ninguna celda empieza por `=`, `+`, `-`, `@`, tabulador ni retorno de carro sin un `'` delante (D-005).

**Errores**

| Estado | `code` | Cuándo |
|---|---|---|
| `400` | `VALIDACION` | Falta `desde` o `hasta`, o el formato no es válido. |
| `422` | `VALORES_INCOHERENTES` | `hasta` anterior a `desde`, o `formato` distinto de `csv`. Mismo estado que en la feature 001, que ya lo publicó como `422`. |
| `403` | `FORBIDDEN` | `EMPLEADO` con un `empleadoId` que no es el suyo. |
| `404` | `EMPLEADO_NOT_FOUND` | `empleadoId` no corresponde a nadie. |
| `503` | `EXPORTACION_SATURADA` | Ya hay tantas exportaciones en curso como permite la configuración; con `Retry-After` (D-002). |

`VALORES_INCOHERENTES`, `EMPLEADO_NOT_FOUND` y `FORBIDDEN` ya existen.
`EXPORTACION_SATURADA` es nuevo.

**`VALIDACION` no existía en ninguna parte del producto**, aunque el contrato de
`auth` ya lo prometía. Los `400` de validación salían con el cuerpo por defecto de
Spring, con traza y valor rechazado en `dev`. Esta feature lo añade en
`CommonExceptionHandler` para toda la API (D-019).

---

## `GET /api/fichajes/empleado/{empleadoId}/resumen/descarga`

| Parámetro | Obligatorio | Valor |
|---|---|---|
| `anio` | sí | p. ej. `2026` |
| `mes` | sí | `1`–`12` |

**`200 OK`**: igual que la exportación, con nombre
`registro-mensual_<empleadoId>_<anio>-<mes>.csv`, las mismas columnas y filas, y al
final:

```
<línea vacía>
Total del mes;…;<H:MM>;<minutos>
Tipo de contrato;PARCIAL
Mes cerrado;No
```

`Total del mes` va en la primera columna, y las horas y los minutos en las columnas
**"Horas trabajadas"** y **"Minutos trabajados"**, sea cual sea su posición. Con rol
`REPRESENTANTE` no existe la columna `Documento` y todo se desplaza una posición, así
que el número de separadores no puede ser fijo.

El total sale de `CalculadoraResumenMensual`, la misma función que el resumen en
pantalla, así que coincide siempre (FR-020, D-011).

**Errores**: los mismos que el resumen en pantalla (`403` si un `EMPLEADO` pide otro
mes que no es suyo, `404` si la persona no existe, `400` si el mes está fuera de
1–12) y `503` por saturación.

---

## `GET /api/exportaciones`

`ADMIN`. Filtros opcionales, combinables:

| Parámetro | Filtra por |
|---|---|
| `empleadoId` | persona exportada. **Incluye las exportaciones de toda la plantilla**, que también contienen sus datos: la pregunta de una auditoría es quién obtuvo los datos de esa persona, y dejarlas fuera ocultaría justo las más amplias |
| `generadaDesde`, `generadaHasta` | fecha de generación: "¿qué se exportó esta semana?" |
| `cubreDesde`, `cubreHasta` | periodo cubierto, por solapamiento (`desde <= cubreHasta` y `hasta >= cubreDesde`): "¿quién obtuvo los datos de marzo?" |

Ordenado por `generadaEn` descendente.

```json
[
  {
    "id": "…",
    "solicitanteId": "…",
    "rolSolicitante": "REPRESENTANTE",
    "alcance": "PLANTILLA",
    "empleadoId": null,
    "desde": "2026-01-01",
    "hasta": "2026-09-30",
    "generadaEn": "2026-10-06T09:14:02Z",
    "completada": true,
    "filas": 4210,
    "huella": "9f2c…"
  }
]
```

Sin nombres ni documentos: solo identificadores (FR-026).

---

## `POST /api/exportaciones/verificar`

`ADMIN`. El cuerpo es el fichero tal cual (`Content-Type: text/csv` o
`application/octet-stream`), **no** `multipart` (D-007). Tope de 100 MB.

**`200 OK`**

```json
{ "coincide": true, "huella": "9f2c…", "exportaciones": [ { "id": "…", "generadaEn": "…", "solicitanteId": "…", "alcance": "PLANTILLA", "desde": "…", "hasta": "…" } ] }
```

o, si no coincide con ninguna:

```json
{ "coincide": false, "huella": "a71b…", "exportaciones": [] }
```

`200` en ambos casos: que no coincida es una respuesta, no un error.

| Estado | `code` | Cuándo |
|---|---|---|
| `413` | `FICHERO_DEMASIADO_GRANDE` | Más de 100 MB. |

El contenido nunca se guarda ni se registra (D-007, D-017).
