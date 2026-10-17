# Data Model: Refactor de los materiales del inventario

## Material (`materiales`, tras V26)

| Campo | Columna | Tipo | Reglas |
|---|---|---|---|
| id | `id` | `UUID` PK | Generado |
| categoría | `categoria_id` | `UUID` FK → `categorias` | Obligatoria, debe existir (FR-003) |
| descripción | `descripcion` | `VARCHAR(500)` | Obligatoria, no en blanco (FR-004) |
| alto | `alto` | `NUMERIC(10,2)` | `> 0`, cm (FR-005) |
| ancho | `ancho` | `NUMERIC(10,2)` | `> 0`, cm (FR-005) |
| cantidad | `cantidad` | `INTEGER` | `>= 0` (FR-006); solo cambia por `PATCH …/cantidad` |
| almacén | `almacen` | `VARCHAR(1)` | `IN ('A','B','C')` (FR-008) |
| sección | `seccion` | `SMALLINT` | `BETWEEN 1 AND 5` (FR-008) |
| imagen | `imagen_ruta` | `VARCHAR(300)` NULL | Ruta del objeto en el bucket; nunca sale por HTTP |
| fecha de alta | `fecha_alta` | `TIMESTAMPTZ` | Al crear, `updatable = false` |
| fecha de modificación | `fecha_ultima_modificacion` | `TIMESTAMPTZ` | `@LastModifiedDate`; se toca también al cambiar imagen o cantidad |

Índices: `idx_materiales_categoria_id` (existente), `idx_materiales_localizacion (almacen, seccion)`.

Restricciones nuevas: `chk_materiales_cantidad (cantidad >= 0)`,
`chk_materiales_dimensiones (alto > 0 AND ancho > 0)`,
`chk_materiales_almacen`, `chk_materiales_seccion`.

Columnas eliminadas: `nombre`, `cantidad_total`, `diametro`, `unidad_medida`,
`color`, `material_fisico`, `estado`, `precio_unitario`, `proveedor`.
Tabla eliminada: `material_fotos`.

RLS: `materiales` ya lo tiene activo (V5) y `ALTER TABLE` no lo desactiva.

## Modelo de dominio

```text
MaterialModel(id, categoria: CategoriaModel, descripcion, dimensiones: DimensionesModel,
              cantidad, localizacion: Localizacion, imagenRuta: String?, imagenUrl: String?,
              fechaAlta, fechaUltimaModificacion)
DimensionesModel(alto: BigDecimal, ancho: BigDecimal)
Localizacion(almacen: Almacen, seccion: Int)   // init { require(seccion in 1..5) }
enum Almacen { A, B, C }
```

`imagenUrl` lo rellena el servicio al leer (D-005); el mapper de entidad lo deja
`null`.

## Imagen (Supabase Storage)

- Bucket privado `INVENTORY_IMAGENES_BUCKET` (por defecto `materiales`).
- Ruta: `materiales/{materialId}/{uuid}.{jpg|png|webp}` (D-009).
- `Content-Type` = el detectado por firma.
- Ciclo de vida: se crea al subir; se borra al sustituir, quitar o borrar el
  material (D-003).

## Historial de material (`historial_material`)

Sin cambios de estructura. `tipoCambio = CANTIDAD`, valores anterior/nuevo de
`cantidad`. V26 elimina sus filas de prueba (D-007).

## Categoría

Sin cambios.
