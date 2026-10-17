# Quickstart: Refactor de los materiales del inventario

## Requisitos

- Docker (Postgres local y Testcontainers).
- Para imágenes reales: un proyecto Supabase con un bucket **privado** (por
  defecto `materiales`) y, en `.env`:

  ```bash
  SUPABASE_URL=https://<proyecto>.supabase.co
  SUPABASE_SERVICE_ROLE_KEY=<clave service_role>
  # INVENTORY_IMAGENES_BUCKET=materiales
  # INVENTORY_IMAGENES_URL_SEGUNDOS=3600
  ```

  Sin ellas, todo funciona salvo la subida de imágenes (`503`).

## Tests

```bash
./gradlew :features:inventory:test
```

Cubre la migración V26 contra Postgres real, el mapeo JPA, las reglas del
servicio (MockK) y el cliente de Storage contra un servidor HTTP falso.

```bash
./gradlew build
```

Build completo (incluye `EsquemaCompletoRlsIT` y el contrato OpenAPI en `app`).

## Validación manual

Con la app en marcha (`./gradlew :app:bootRun`) y un token `ADMIN` o
`ENCARGADO` en `$TOKEN` (en `dev`, `POST /api/dev/token`):

1. Crear una categoría y un material en `B-4` (contrato: [contracts/README.md](./contracts/README.md)). Esperado: `201`, sin `imagenUrl`, fechas iguales.
2. Repetir con `"almacen": "D"` o `"seccion": 6`. Esperado: `400 VALIDACION` nombrando el campo.
3. Subir una imagen:
   ```bash
   curl -X PUT -H "Authorization: Bearer $TOKEN" -F imagen=@foto.jpg http://localhost:8080/api/materiales/<id>/imagen
   ```
   Esperado: `200` con `imagenUrl`; abrirla en el navegador muestra la foto. En el bucket hay un objeto bajo `materiales/<id>/`.
4. Subir otra imagen. Esperado: el bucket solo conserva la nueva.
5. Subir un PDF renombrado a `.jpg`. Esperado: `415 IMAGEN_NO_VALIDA`.
6. `PATCH …/cantidad` con motivo y consultar `…/historial`. Esperado: una entrada `CANTIDAD`.
7. `GET /api/materiales?almacen=B&seccion=4`. Esperado: solo el material de B-4.
8. `DELETE` del material. Esperado: `204` y el objeto desaparece del bucket.
