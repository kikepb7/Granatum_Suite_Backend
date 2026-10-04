# Contrato: fichajes

Convenciones transversales (formato de error, autorización, idempotencia,
tiempo) en [`README.md`](README.md).

---

## `POST /api/fichajes/entrada`

Abre una jornada. **FR-001, FR-002, FR-010.**

**Roles**: `EMPLEADO`, `ENCARGADO`, `ADMIN` — siempre para sí mismo. El empleado
se toma del sujeto del JWT.

**Petición**

```json
{
  "clientEventId": "3f0c...",
  "occurredAt": "2026-10-05T07:00:00Z",
  "ubicacion": { "latitud": 37.3891, "longitud": -5.9845, "precisionMetros": 12 }
}
```

`ubicacion` es **opcional y puede omitirse o ser `null`** (FR-009). Que la
persona deniegue el permiso de geolocalización no impide fichar: bloquearlo
incumpliría la obligación legal de registrar la jornada por un motivo
accesorio.

**`201 Created`**

```json
{
  "id": "a81b...",
  "empleadoId": "7c2e...",
  "entrada": "2026-10-05T07:00:00Z",
  "salida": null,
  "estado": "EN_CURSO",
  "minutosTrabajados": null,
  "fueIncompleto": false,
  "pausas": []
}
```

**Errores propios**: `409 FICHAJE_YA_EN_CURSO`, `409 EMPLEADO_INACTIVO`,
`422 DESVIACION_RELOJ`.

> Un fichaje en estado `INCOMPLETO` **no** cuenta como jornada en curso, así que
> no provoca `FICHAJE_YA_EN_CURSO` (FR-012a).

---

## `POST /api/fichajes/{id}/pausa/inicio`

Abre una pausa. **FR-003, FR-004.**

**Roles**: el titular del fichaje.

**Petición**

```json
{
  "clientEventId": "9a1d...",
  "occurredAt": "2026-10-05T11:00:00Z",
  "tipo": "COMIDA"
}
```

`tipo`: `COMIDA` | `DESCANSO` | `OTRO`.

**`200 OK`** — el fichaje completo, con la pausa abierta (`fin: null`) en
`pausas`.

**Errores propios**: `409 FICHAJE_NO_EN_CURSO`, `409 PAUSA_YA_ABIERTA`,
`422 DESVIACION_RELOJ`.

---

## `POST /api/fichajes/{id}/pausa/fin`

Cierra la pausa abierta. **FR-003.**

**Petición**: `clientEventId` y `occurredAt`. No lleva identificador de pausa:
por FR-004 solo puede haber una abierta, así que es inequívoca.

**`200 OK`** — el fichaje con la pausa cerrada.

**Errores propios**: `409 FICHAJE_NO_EN_CURSO`, `409 PAUSA_NO_ABIERTA`,
`422 VALORES_INCOHERENTES` (fin anterior al inicio), `422 DESVIACION_RELOJ`.

---

## `POST /api/fichajes/{id}/salida`

Cierra la jornada y calcula las horas. **FR-005, FR-006, FR-007, FR-011.**

**Petición**: `clientEventId`, `occurredAt` y `ubicacion` opcional.

**`200 OK`**

```json
{
  "id": "a81b...",
  "entrada": "2026-10-05T07:00:00Z",
  "salida": "2026-10-05T16:00:00Z",
  "estado": "CERRADO",
  "minutosTrabajados": 480,
  "fueIncompleto": false,
  "pausas": [
    { "id": "...", "tipo": "DESCANSO", "inicio": "2026-10-05T09:00:00Z", "fin": "2026-10-05T09:15:00Z" },
    { "id": "...", "tipo": "COMIDA",   "inicio": "2026-10-05T11:00:00Z", "fin": "2026-10-05T11:45:00Z" }
  ]
}
```

`minutosTrabajados` = (salida − entrada) − Σ pausas. En el ejemplo:
540 − 60 = **480**.

Se expone en **minutos enteros**, no en horas decimales: la jornada se registra
al minuto y un entero elimina los errores de redondeo al sumar en el CSV de la
feature 003.

**Errores propios**: `409 FICHAJE_NO_EN_CURSO`, `409 PAUSA_ABIERTA_AL_CERRAR`,
`422 VALORES_INCOHERENTES` (salida antes de la entrada, o pausas que suman más
que la jornada), `422 DESVIACION_RELOJ`.

---

## `GET /api/fichajes/empleado/{empleadoId}?desde=&hasta=`

Consulta por empleado y rango. **FR-021, FR-022, FR-023.**

**Roles**: `ENCARGADO` y `ADMIN`, cualquier `empleadoId`. `EMPLEADO`, solo si
`{empleadoId}` coincide con el sujeto de su token; en caso contrario `403`,
**con independencia del identificador que indique**.

**Parámetros**

| Parámetro | Tipo | Obligatorio | Nota |
|-----------|------|-------------|------|
| `desde` | fecha `YYYY-MM-DD` | sí | Inclusive, inicio del día en `Europe/Madrid` |
| `hasta` | fecha `YYYY-MM-DD` | sí | Inclusive, fin del día en `Europe/Madrid` |

El filtro se aplica sobre la **fecha de entrada**, así que una jornada que cruza
la medianoche aparece en el día en que empezó, no en los dos.

**`200 OK`**: lista de fichajes, cada uno con sus pausas, ordenada por entrada
descendente. **Un rango sin fichajes devuelve `[]`, no un error.**

**Errores propios**: `403 FORBIDDEN`, `404 EMPLEADO_NOT_FOUND`,
`400 VALIDATION_ERROR` (`hasta` anterior a `desde`).

> Este listado se sirve con un fetch join sobre las pausas para evitar el N+1
> (data-model.md). No está paginado: el rango de fechas acota el resultado. Si
> en el futuro se pagina, hay que cambiar la estrategia de carga, porque un
> fetch join de colección no se pagina en base de datos.
