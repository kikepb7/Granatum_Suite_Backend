# Quickstart: eventos

Con la aplicación en local (`./gradlew :app:bootRun`) y un token de `EMPLEADO`:

1. `GET /api/eventos/materiales` → materiales con sus unidades.
2. `POST /api/eventos {"nombre":"Evento 1","descripcion":"Boda de Juan y María","fecha":"<mañana>"}` → `201`.
3. `PUT /api/eventos/{id}/materiales/{materialId} {"cantidad":5}` → el material baja 5 en `GET /api/eventos/materiales`.
4. Pedir más de lo que queda → `409 STOCK_INSUFICIENTE` con `disponible`.
5. `POST /api/eventos/{id}/finalizar` → `FINALIZADO`; las unidades vuelven.
6. Con un token de `REPRESENTANTE`: `GET` funciona, `POST` → `403`.
7. `DELETE /api/materiales/{materialId}` (como `ADMIN`) de un material asignado a un evento programado → `409 MATERIAL_EN_USO`.
