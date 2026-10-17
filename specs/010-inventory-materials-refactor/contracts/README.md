# Contrato: materiales del inventario (010)

Todas las rutas exigen `ADMIN` o `ENCARGADO` (regla `/api/materiales/**` en
`SecurityConfig`, sin cambios). Errores `{ "code", "message" }` (research D-011).
El contrato definitivo es `docs/openapi.json`, regenerado.

## MaterialDto (respuesta)

```json
{
  "id": "6f1c…",
  "categoria": { "id": "…", "nombre": "Cables", "descripcion": null, "createdAt": "…", "updatedAt": "…" },
  "descripcion": "Bobina de cable de 2,5 mm²",
  "dimensiones": { "alto": 30.00, "ancho": 45.50 },
  "cantidad": 12,
  "localizacion": { "almacen": "B", "seccion": 4 },
  "imagenUrl": "https://<proyecto>.supabase.co/storage/v1/object/sign/materiales/…?token=…",
  "fechaAlta": "2026-10-17T09:00:00Z",
  "fechaUltimaModificacion": "2026-10-17T09:05:00Z"
}
```

`imagenUrl` es `null` si no hay imagen o si no se pudo firmar. Es temporal.

## Rutas

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| `GET` | `/api/materiales?almacen=A&seccion=3` | — | `200 [MaterialDto]`. Ambos filtros opcionales; `seccion` exige `almacen` |
| `GET` | `/api/materiales/{id}` | — | `200 MaterialDto` |
| `POST` | `/api/materiales` | `CreateMaterialRequest` | `201 MaterialDto` |
| `PUT` | `/api/materiales/{id}` | `UpdateMaterialRequest` | `200 MaterialDto` |
| `DELETE` | `/api/materiales/{id}` | — | `204` (borra también la imagen) |
| `PATCH` | `/api/materiales/{id}/cantidad` | `{ "cantidad": 7, "motivo": "Rotura" }` | `200 MaterialDto` |
| `GET` | `/api/materiales/{id}/historial` | — | `200 [HistorialMaterialDto]` (sin cambios) |
| `PUT` | `/api/materiales/{id}/imagen` | multipart, parte `imagen` | `200 MaterialDto` |
| `DELETE` | `/api/materiales/{id}/imagen` | — | `200 MaterialDto` (idempotente) |

## CreateMaterialRequest

```json
{
  "categoriaId": "…",
  "descripcion": "Bobina de cable de 2,5 mm²",
  "dimensiones": { "alto": 30, "ancho": 45.5 },
  "cantidad": 12,
  "localizacion": { "almacen": "B", "seccion": 4 }
}
```

- `descripcion`: no en blanco, ≤ 500.
- `dimensiones.alto`, `dimensiones.ancho`: obligatorios, `> 0`, cm.
- `cantidad`: `>= 0`.
- `localizacion.almacen`: `A`, `B` o `C`; `localizacion.seccion`: 1–5.

## UpdateMaterialRequest

Igual que el de alta **sin `cantidad`**: la cantidad solo cambia por `PATCH
…/cantidad` (research D-008). Un campo `cantidad` en el cuerpo se ignora.

## Errores específicos

| Situación | HTTP | `code` |
|---|---|---|
| Localización, dimensiones, descripción o cantidad inválidas | 400 | `VALIDACION` (nombra el campo) |
| Filtro `seccion` sin `almacen`, o valores inválidos | 400 | `VALIDACION` |
| Fichero no JPEG/PNG/WebP o vacío | 415 | `IMAGEN_NO_VALIDA` |
| Fichero > 10 MB | 413 | `IMAGEN_DEMASIADO_GRANDE` |
| Storage sin configurar o caído al subir | 503 | `ALMACEN_IMAGENES_NO_DISPONIBLE` |
| Falta la parte `imagen` | 400 | `VALIDACION` |
