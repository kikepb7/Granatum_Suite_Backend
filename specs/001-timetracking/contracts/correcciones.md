# Contrato: correcciones de fichaje

Convenciones transversales en [`README.md`](README.md).

Este es el **único** camino por el que un fichaje finalizado puede cambiar de
valor (FR-018, principio III de la constitución). No existe ningún `PUT` ni
`PATCH` sobre `/api/fichajes/{id}`, y no debe añadirse: sería la forma de
incumplir el principio sin darse cuenta.

---

## `POST /api/fichajes/{id}/correcciones`

Solicita corregir un fichaje finalizado. **FR-013, FR-020, FR-020a, FR-020b.**

**Roles**: el titular del fichaje (`EMPLEADO`), o `ENCARGADO` / `ADMIN` sobre
cualquiera.

**Petición**

```json
{
  "motivo": "Olvidé fichar la pausa de comida",
  "valoresPropuestos": {
    "entrada": "2026-10-05T07:00:00Z",
    "salida": "2026-10-05T16:00:00Z",
    "pausas": [
      { "tipo": "COMIDA", "inicio": "2026-10-05T11:00:00Z", "fin": "2026-10-05T11:45:00Z" }
    ]
  }
}
```

- `motivo` es **obligatorio**, 10–500 caracteres (FR-013).
- `valoresPropuestos` describe el **estado final completo** de la jornada, no un
  parche: la lista `pausas` reemplaza la existente, lo que permite añadir una no
  registrada, eliminar una sobrante y mover las horas de una existente con un
  solo modelo.
- **No admite `ubicacion`**. Incluirla devuelve `422 UBICACION_NO_CORREGIBLE`
  (FR-020a): la ubicación es evidencia de dónde se fichó y editarla la
  invalidaría como prueba.

**`201 Created`**

```json
{
  "id": "c4f1...",
  "fichajeId": "a81b...",
  "solicitanteId": "7c2e...",
  "motivo": "Olvidé fichar la pausa de comida",
  "estado": "PENDIENTE",
  "valoresPropuestos": { "...": "..." },
  "creadaEn": "2026-10-06T08:12:00Z"
}
```

**Errores propios**

| Error | Cuándo |
|-------|--------|
| `409 FICHAJE_NO_FINALIZADO` | El fichaje está `EN_CURSO`. Una jornada abierta se arregla continuándola, no corrigiéndola (FR-013) |
| `422 UBICACION_NO_CORREGIBLE` | La propuesta trae `ubicacion` |
| `422 VALORES_INCOHERENTES` | Salida antes de la entrada, pausas solapadas, pausas fuera del intervalo de la jornada, u horas resultantes negativas (FR-020b) |
| `403 FORBIDDEN` | Un `EMPLEADO` sobre un fichaje que no es suyo |

> Un fichaje `INCOMPLETO` **sí** es corregible: es justamente su vía de arreglo
> (FR-012b). La corrección aporta la hora de salida que faltó.

---

## `POST /api/correcciones/{id}/aprobar`

Aprueba y aplica. **FR-015, FR-016, FR-017, FR-019.**

**Roles**: **solo `ENCARGADO` y `ADMIN`.** Además, quien creó la solicitud no
puede resolverla si su rol es `EMPLEADO` (FR-015).

**Petición**: cuerpo vacío.

**`200 OK`**

```json
{
  "id": "c4f1...",
  "fichajeId": "a81b...",
  "estado": "APROBADA",
  "resueltaPorId": "11aa...",
  "resueltaEn": "2026-10-06T09:30:00Z",
  "valoresOriginales": {
    "entrada": "2026-10-05T07:00:00Z",
    "salida": "2026-10-05T16:00:00Z",
    "pausas": []
  },
  "valoresPropuestos": { "...": "..." }
}
```

Efectos, todos en la misma transacción:

1. Se guarda en `valoresOriginales` el estado del fichaje **justo antes** de
   aplicar (FR-017). Es lo que hace recuperable el original.
2. Se aplican los valores propuestos al fichaje y se recalculan sus minutos
   trabajados.
3. Si el fichaje estaba `INCOMPLETO`, pasa a `CERRADO` — pero `fueIncompleto`
   **sigue siendo `true`** (FR-012c, SC-010), de modo que un informe para la
   Inspección puede señalar qué jornadas se reconstruyeron.
4. La solicitud queda `APROBADA` con autoría e instante.

**Errores propios**

| Error | Cuándo |
|-------|--------|
| `409 SOLICITUD_YA_RESUELTA` | Ya estaba `APROBADA` o `RECHAZADA` (FR-019) |
| `422 VALORES_INCOHERENTES` | La propuesta dejaría datos imposibles contra el estado actual del fichaje. Se revalida **al aprobar**, no solo al solicitar: el fichaje puede haber cambiado por otra corrección entremedias |
| `403 FORBIDDEN` | Rol insuficiente, o es quien la solicitó siendo `EMPLEADO` |

---

## `POST /api/correcciones/{id}/rechazar`

Rechaza sin tocar el fichaje. **FR-014, FR-016, FR-019.**

**Roles**: `ENCARGADO`, `ADMIN`.

**Petición**

```json
{ "motivoResolucion": "Las horas no coinciden con el parte de obra" }
```

`motivoResolucion` es **obligatorio al rechazar**: una denegación sin motivo es
inútil para quien la recibe y deja al responsable sin rastro de su criterio.

**`200 OK`**: la solicitud en `RECHAZADA`, con autoría, instante y motivo. El
fichaje **no cambia en nada**, y `valoresOriginales` queda `null` porque nunca se
aplicó nada.

**Errores propios**: `409 SOLICITUD_YA_RESUELTA`, `403 FORBIDDEN`,
`400 VALIDATION_ERROR` (sin `motivoResolucion`).

---

## Consulta de solicitudes

Para que el flujo sea utilizable hace falta poder listarlas. **No estaba en el
listado de endpoints del encargo**, así que se señala aquí como hueco a
confirmar antes de implementar:

- `GET /api/correcciones?estado=PENDIENTE` — bandeja del responsable.
- `GET /api/fichajes/{id}/correcciones` — histórico de un fichaje, necesario
  para SC-003 (que el original siga siendo recuperable) y para la columna
  "correcciones aplicadas" del CSV de la feature 003.

Sin el segundo, los valores originales quedan almacenados pero sin ninguna vía
de lectura, y SC-003 no sería verificable desde la API.
