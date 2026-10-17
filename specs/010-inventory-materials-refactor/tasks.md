# Tasks: Refactor de los materiales del inventario

**Input**: [spec.md](./spec.md), [plan.md](./plan.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/README.md](./contracts/README.md)

**Tests**: TDD (principio V). Cada test antes de su implementación.

Rutas relativas a `features/inventory/src/` salvo que se indique otra cosa.

## Phase 1: Fundamentos (bloquea todas las historias)

- [X] T001 Mover `TipoDocumento` y `DetectorTipoFichero` de `features/invoices/.../infrastructure/documentos/DetectorTipoFichero.kt` a `common/src/main/kotlin/com/granatum/core/ficheros/DetectorTipoFichero.kt` (paquete `com.granatum.core.ficheros`), con su test a `common/src/test/kotlin/com/granatum/core/ficheros/DetectorTipoFicheroTest.kt`; actualizar los imports de facturas (D-004)
- [X] T002 Migración `main/resources/db/migration/V26__refactor_materiales.sql` (D-007): `DELETE` de `historial_material`, `material_fotos` y `materiales`; `DROP TABLE material_fotos`; quitar `chk_materiales_cantidad` y las columnas `nombre`, `cantidad_total`, `diametro`, `unidad_medida`, `color`, `material_fisico`, `estado`, `precio_unitario`, `proveedor`; renombrar `cantidad_disponible` → `cantidad`; añadir `descripcion VARCHAR(500) NOT NULL`, `almacen VARCHAR(1) NOT NULL`, `seccion SMALLINT NOT NULL`, `imagen_ruta VARCHAR(300)`; `CHECK`s `cantidad >= 0`, `alto > 0 AND ancho > 0`, `almacen IN ('A','B','C')`, `seccion BETWEEN 1 AND 5`; índice `idx_materiales_localizacion (almacen, seccion)`
- [X] T003 Dominio: `Almacen { A, B, C }` en `domain/type/Almacen.kt`; `Localizacion(almacen, seccion)` con `require(seccion in 1..5)` y `DimensionesModel(alto, ancho)` en `domain/model/`; `MaterialModel` con `categoria, descripcion, dimensiones, cantidad, localizacion, imagenRuta, imagenUrl, fechaAlta, fechaUltimaModificacion`. Borrar `EstadoMaterial`, `UnidadMedida`, `TamanoModel`
- [X] T004 Persistencia: `MaterialEntity` con `descripcion`, `@Embedded DimensionesEmbeddable(alto, ancho)` (`precision = 10, scale = 2`), `cantidad`, `@Embedded LocalizacionEmbeddable(almacen: Almacen STRING length 1, seccion: Int)`, `imagenRuta: String?`; sin `fotos`. Borrar `TamanoEmbeddable`. `MaterialRepository.findAllByLocalizacionAlmacen` y `findAllByLocalizacionAlmacenAndLocalizacionSeccion`. Mapper `MaterialMappers.kt` (`imagenUrl = null`)
- [X] T005 Reescribir `test/kotlin/com/granatum/core/MaterialRepositoryIT.kt`: V26 aplica sobre Postgres real, guardar y releer un material con todos los campos, `material_fotos` ya no existe, los `CHECK` rechazan almacén `D`, sección `6`, alto `0` y cantidad `-1` por SQL directo

## Phase 2: US1 + US2 - Alta, edición y localización (P1) 🎯 MVP

- [X] T006 [US1] Test `test/kotlin/com/granatum/core/MaterialServiceTest.kt` (MockK): crear con categoría inexistente → `CategoriaNotFoundException` sin guardar; crear guarda todos los campos; actualizar cambia descripción, dimensiones, categoría y localización sin tocar `cantidad`; borrar
- [X] T007 [US1] `MaterialService.create/update/delete/findAll/findById` con el nuevo modelo (sin imagen todavía)
- [X] T008 [US2] DTO: `DimensionesRequest` (`@NotNull @DecimalMin("0.0", inclusive = false)` alto y ancho), `LocalizacionRequest` (`almacen: String` con `@NotNull @Pattern("[ABC]")`, `seccion: Int` con `@NotNull @Min(1) @Max(5)`), `CreateMaterialRequest` (`categoriaId`, `descripcion` `@NotBlank @Size(max = 500)`, `@Valid dimensiones`, `cantidad` `@Min(0)`, `@Valid localizacion`), `UpdateMaterialRequest` (igual sin `cantidad`), `MaterialDto`, `DimensionesDto`, `LocalizacionDto`; borrar `Tamano*`; mappers en `api/mappers/MaterialDtoMappers.kt`
- [X] T009 [US1] `MaterialController`: `POST`, `PUT`, `GET`, `GET /{id}`, `DELETE` con los nuevos DTO; reescribir `test/kotlin/com/granatum/core/MaterialDtoMapperTest.kt`
- [X] T010 [US2] Test de validación del borde, sobre HTTP real en `test/kotlin/com/granatum/core/MaterialesHttpIT.kt` (como `ContratoHttpAuthIT`: MockMvc no ejercita el cable): almacén `D`, sección `0`/`6`, localización ausente, alto `0`, descripción en blanco → `400 VALIDACION` nombrando el campo y sin el valor

## Phase 3: US4 - Cantidad con motivo (P2)

- [X] T011 [US4] Test en `MaterialServiceTest`: `updateCantidad` 10 → 7 escribe historial `CANTIDAD` con valores `"10"`/`"7"`, motivo y usuario; cantidad negativa → `CantidadInvalidaException` sin historial
- [X] T012 [US4] `MaterialService.updateCantidad`, `UpdateCantidadRequest(cantidad @Min(0), motivo @NotBlank)`, `CantidadInvalidaException` con un solo valor; `PATCH /{id}/cantidad`

## Phase 4: US3 - Imagen en Supabase Storage (P2)

- [X] T013 [US3] Puerto `domain/port/AlmacenImagenes.kt` (`guardar(ruta, bytes, mediaType)`, `borrar(rutas)`, `firmar(rutas): Map<String, String>`, `disponible`); excepciones `ImagenNoValidaException`, `ImagenDemasiadoGrandeException`, `AlmacenImagenesNoDisponibleException` y sus códigos en `InventoryExceptionHandler` (`415 IMAGEN_NO_VALIDA`, `413 IMAGEN_DEMASIADO_GRANDE`, `503 ALMACEN_IMAGENES_NO_DISPONIBLE`) (D-011)
- [X] T014 [US3] Test `test/kotlin/com/granatum/core/AlmacenImagenesSupabaseTest.kt` contra `MockWebServer`: subida (`POST /storage/v1/object/{bucket}/{ruta}`, cabeceras `Authorization`/`apikey`, `Content-Type`, `x-upsert: false`, cuerpo intacto), borrado (`DELETE /storage/v1/object/{bucket}` con `prefixes`), firmado por lotes (`POST /storage/v1/object/sign/{bucket}`, URL absoluta resultante), error HTTP → `AlmacenImagenesNoDisponibleException`, `toString()` sin la clave. Añadir `testImplementation(libs.okhttp.mockwebserver)` a `features/inventory/build.gradle.kts`
- [X] T015 [US3] `infrastructure/storage/`: `ConfiguracionAlmacenImagenes` (`inventory.imagenes.supabase-url`, `service-key`, `bucket`, `url-segundos`, `max-bytes`; `activo` si URL y clave no vacías), `AlmacenImagenesSupabase` (`RestClient`, D-001), `AlmacenImagenesDeshabilitado`, `AlmacenImagenesConfig` que elige uno y avisa al arrancar sin Storage (D-010)
- [X] T016 [US3] Test en `MaterialServiceTest`: subir valida tipo por firma (PDF → `IMAGEN_NO_VALIDA`, vacío, > `max-bytes`) sin llamar a Storage; material inexistente sin subir nada; sustituir sube la nueva con ruta `materiales/{id}/{uuid}.{ext}`, apunta el material y borra la anterior; fallo al guardar el material borra la nueva; fallo al borrar la anterior no falla; quitar imagen (con y sin imagen); borrar material borra su imagen; sin Storage subir → `503` y quitar/borrar funcionan; lectura firma por lotes y un fallo de firmado deja `imagenUrl = null`
- [X] T017 [US3] `MaterialService.subirImagen/quitarImagen` y lecturas con firmado (D-003, D-005), con `TransactionTemplate` para que la subida quede fuera de la transacción; `delete` borra la imagen tras el commit
- [X] T018 [US3] `PUT /{id}/imagen` (multipart, parte `imagen`) y `DELETE /{id}/imagen` en `MaterialController`; `imagenUrl` en `MaterialDto`; flujo completo (subir, sustituir, quitar, borrar, tipo y tamaño) en `MaterialesHttpIT` contra un Storage local
- [X] T019 [US3] Configuración: bloque `inventory.imagenes` en `app/src/main/resources/application.yml` (`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `INVENTORY_IMAGENES_BUCKET:materiales`, `INVENTORY_IMAGENES_URL_SEGUNDOS:3600`, `INVENTORY_IMAGENES_MAX_BYTES:10485760`) y en `.env.example` (comentadas, sin valores)

## Phase 5: US5 - Filtrar por almacén y sección (P3)

- [X] T020 [US5] Test en `MaterialServiceTest`, `MaterialRepositoryIT` y `MaterialesHttpIT`: filtro por almacén, por almacén y sección; `seccion` sin `almacen` → `400 VALIDACION`; `almacen=D` → `400 VALIDACION`
- [X] T021 [US5] `GET /api/materiales?almacen=&seccion=` con `@Pattern`/`@Min`/`@Max` en los parámetros y `MaterialService.findAll(almacen, seccion)`

## Phase 6: Pulido

- [X] T022 `app/src/test/kotlin/com/granatum/core/EsquemaCompletoRlsIT.kt` sin `material_fotos`; revisar `features/inventory/src/test/kotlin/com/granatum/core/RowLevelSecurityIT.kt` y `HistorialAppendOnlyTest.kt`
- [X] T023 [P] `README.md` (inventario: modelo, rutas, imágenes, variables), `docs/ARCHITECTURE.md` (puerto `AlmacenImagenes`, detector en `common`), `docs/DESPLIEGUE.md` (crear el bucket privado, variables), `CHANGELOG.md` (sin publicar)
- [X] T024 Regenerar `docs/openapi.json` y build completo en verde con el perfil `prod` *(742 tests, 0 fallos)*

## Dependencies

- Phase 1 bloquea todo. T001 es independiente de T002–T005.
- US1/US2 (Phase 2) es el MVP; US4, US3 y US5 dependen solo de Phase 2 y son independientes entre sí.
- Phase 6 al final.

## Parallel

- T001 ‖ T002 (common/invoices frente a migración).
- Tras Phase 2: US4 (T011–T012), US3 (T013–T019) y US5 (T020–T021) tocan métodos distintos del mismo servicio y controlador, así que se hacen en serie en esta rama; T014/T015 (cliente de Storage) sí son paralelizables con US4.

## Implementation Strategy

MVP = Phase 1 + Phase 2: inventario con el nuevo modelo y localización
validada, sin imagen. Después US4 (conserva la trazabilidad), US3 (imagen) y
US5 (filtros). Build completo al final de cada fase.
