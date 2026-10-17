# Contrato: eventos (011)

Errores `{ "code", "message" }`; `STOCK_INSUFICIENTE` añade `disponible`. El contrato definitivo es
`docs/openapi.json`, regenerado.

## Autorización

| Rutas | Roles |
|---|---|
| `GET /api/eventos/**` | `ADMIN`, `ENCARGADO`, `EMPLEADO`, `REPRESENTANTE` |
| resto de `/api/eventos/**` | `ADMIN`, `ENCARGADO`, `EMPLEADO` |

## EventoDto

```json
{
  "id": "…",
  "nombre": "Evento 1",
  "descripcion": "Boda de Juan y María",
  "fecha": "2027-06-13",
  "estado": "PROGRAMADO",
  "materiales": [
    { "materialId": "…", "categoria": "Jarrones", "descripcion": "Jarrón cilíndrico", "cantidad": 5,
      "localizacion": { "almacen": "A", "seccion": 2 }, "imagenUrl": "https://…?token=…" }
  ],
  "creadoPor": "…",
  "finalizadoEn": null,
  "fechaAlta": "2026-10-17T09:00:00Z",
  "fechaUltimaModificacion": "2026-10-17T09:05:00Z"
}
```

En cada material, `localizacion` e `imagenUrl` son los actuales del inventario y son `null` si el
material ya no existe; `categoria` y `descripcion` son las del momento de asignarlo.

## MaterialDisponibleDto (selector)

```json
{ "id": "…", "categoria": "Jarrones", "descripcion": "Jarrón cilíndrico", "cantidad": 3,
  "localizacion": { "almacen": "A", "seccion": 2 }, "imagenUrl": null }
```

## Rutas

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| `GET` | `/api/eventos?estado=PROGRAMADO` | — | `200 [EventoDto]`; sin `estado`, todos. Programados por fecha ascendente, finalizados descendente |
| `GET` | `/api/eventos/{id}` | — | `200 EventoDto` |
| `POST` | `/api/eventos` | `{ nombre, descripcion?, fecha }` | `201 EventoDto` |
| `PUT` | `/api/eventos/{id}` | `{ nombre, descripcion?, fecha }` | `200 EventoDto` |
| `DELETE` | `/api/eventos/{id}` | — | `204` (devuelve los materiales si estaba programado) |
| `POST` | `/api/eventos/{id}/finalizar` | — | `200 EventoDto` (idempotente) |
| `PUT` | `/api/eventos/{id}/materiales/{materialId}` | `{ cantidad }` (≥ 1) | `200 EventoDto` |
| `DELETE` | `/api/eventos/{id}/materiales/{materialId}` | — | `200 EventoDto` (idempotente) |
| `GET` | `/api/eventos/materiales` | — | `200 [MaterialDisponibleDto]` |

## Errores

| Situación | HTTP | `code` |
|---|---|---|
| Evento inexistente | 404 | `EVENTO_NOT_FOUND` |
| Material inexistente al asignar | 404 | `MATERIAL_NOT_FOUND` |
| No hay unidades suficientes | 409 | `STOCK_INSUFICIENTE` + `disponible` |
| Modificar un evento finalizado | 409 | `EVENTO_FINALIZADO` |
| Fecha anterior a hoy | 400 | `FECHA_PASADA` |
| Validación del cuerpo | 400 | `VALIDACION` |

### Cambio en inventario

`DELETE /api/materiales/{id}` de un material asignado a un evento programado → `409 MATERIAL_EN_USO`.
