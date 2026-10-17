# Phase 0 — Investigación: eventos

## D-001 — Cruce con inventario por contratos de `common`

**Decisión**: dos interfaces nuevas en `common/domain/contract`:

- `StockMateriales` (implementa `inventory`, usa `events`): listar materiales para el selector,
  resumir materiales por id, **retirar** y **devolver** unidades con motivo.
- `UsoMateriales` (implementa `events`, usa `inventory`): saber si un material está asignado a
  algún evento programado, para impedir su borrado.

Las excepciones que viajan por el contrato (`StockInsuficienteException`, `MaterialInexistenteException`)
también viven en `common`, porque las lanza una feature y las traduce a HTTP otra.

**Razón**: el principio I prohíbe la dependencia directa y nombra esta salida. Es el mismo patrón
que `DirectorioEmpleados` (auth ← timetracking). Ambos contratos son dependencias obligatorias:
si nadie los implementa, `app` no arranca, que es el fallo correcto.

**Alternativas**: eventos de dominio (`ApplicationEventPublisher`) — descartado: la retirada
tiene que poder **fallar** (sin stock) y hacerlo en la misma transacción, y un evento no devuelve
nada. Mover materiales a `common` — descartado: arrastraría todo el inventario.

## D-002 — La reserva modifica la cantidad del inventario

**Decisión**: asignar resta de `materiales.cantidad` y devolver suma, con una entrada de
historial `CANTIDAD` por movimiento. El inventario muestra siempre lo que queda libre.

**Razón**: es lo que pide el responsable («se reducen automáticamente en el inventario») y no
cambia el contrato de materiales: los clientes siguen leyendo `cantidad`.

**Alternativa**: calcular libre = cantidad − reservado — descartada: cambiaría el significado de
`cantidad` para todos los clientes y obligaría a inventario a conocer los eventos al leer.

## D-003 — Atomicidad y concurrencia

**Decisión**: la retirada es un `UPDATE materiales SET cantidad = cantidad - :n WHERE id = :id
AND cantidad >= :n`; si no actualiza filas, se lee la cantidad para el mensaje y se lanza
`StockInsuficienteException`. Las operaciones sobre un evento bloquean su fila
(`PESSIMISTIC_WRITE`), así dos cambios del mismo evento no se pisan. Todo ocurre en la transacción
del servicio de eventos: la implementación del contrato se une a ella (propagación por defecto).

**Razón**: la condición en el `WHERE` hace imposible el negativo (SC-002) sin leer-y-escribir;
el `CHECK cantidad >= 0` de V26 es la red final.

## D-004 — Cambiar una cantidad = aplicar la diferencia

`PUT /api/eventos/{id}/materiales/{materialId} {cantidad}` fija la cantidad deseada. El servicio
retira o devuelve solo la diferencia con la actual; `0` no está permitido (para quitar está
`DELETE`). Idempotente: repetir la misma petición no mueve stock.

## D-005 — Finalización

Tarea `FinalizacionEventosJob` diaria a las 00:05 Europe/Madrid (`events.finalizacion.cron`),
que finaliza **todos** los programados con fecha anterior a hoy (cubre días en que la aplicación
estuvo parada). Idempotente por construcción: solo pasa `PROGRAMADO` → `FINALIZADO`. Cada evento
se finaliza en su propia transacción, para que un fallo en uno no deshaga los demás.

«Finalizar ahora» (`POST /api/eventos/{id}/finalizar`) usa el mismo método; sobre uno ya
finalizado devuelve el evento sin cambios (`200`).

## D-006 — Historial de las devoluciones automáticas

`historial_material.usuario_id` es `NOT NULL` y no hay usuario de sistema. La devolución automática
se atribuye a quien creó el evento y su motivo dice «automáticamente». Hacer la columna opcional
cambiaría el contrato de `HistorialMaterialDto` (`usuarioId` pasaría a admitir `null`) para todos
los clientes; queda como mejora si se añade un usuario de sistema.

## D-007 — Material borrado del inventario

`evento_materiales.material_id` no tiene clave foránea a `materiales`: ninguna tabla referencia
las de otra feature, y un FK ataría el ciclo de vida de los dos esquemas. En su lugar:

- Mientras el evento está programado, inventario no deja borrar el material (`UsoMateriales`, FR-013).
- La línea guarda la descripción y la categoría del material al asignarse, para que un evento
  finalizado siga diciendo qué llevó aunque el material ya no exista.
- Devolver a un material que ya no existe no falla: no hay a dónde devolver.

## D-008 — Autorización de `EMPLEADO` y `REPRESENTANTE` (enmienda)

El principio IV limita `EMPLEADO` a «únicamente lo suyo» y `REPRESENTANTE` al registro de jornada.
El responsable decide que la plantilla gestione eventos y que el representante los consulte.
Requiere enmendar la constitución (v2.5.0, MINOR: se amplía el alcance de dos roles). `EMPLEADO`
sigue **sin** acceder a `/api/materiales`: el selector va por `/api/eventos/materiales`, que expone
solo lo necesario para elegir (FR-012).

## D-009 — Fecha pasada

Crear o mover un evento a un día anterior a hoy se rechaza (`400 FECHA_PASADA`): la tarea lo
finalizaría en la siguiente pasada y retiraría stock para nada.
