# Implementation Plan: Refactor de los materiales del inventario

**Branch**: `inventory-refactor-feature` | **Date**: 2026-10-17 | **Spec**: [spec.md](./spec.md)

## Summary

Se reduce `Material` a categoría, descripción, alto y ancho (cm), cantidad,
localización (almacén `A`/`B`/`C` + sección `1`–`5`), una imagen opcional y las
fechas de alta y modificación. Una migración nueva (V26) vacía los datos de
prueba, elimina `material_fotos` y las columnas retiradas, y añade las nuevas
con sus `CHECK`. La imagen se sube por `PUT /api/materiales/{id}/imagen`
(multipart) a un bucket privado de Supabase Storage mediante su API REST, detrás
del puerto `AlmacenImagenes`; el material guarda solo la ruta del objeto y al
leerlo se devuelve una URL firmada temporal. Sin `SUPABASE_URL` y clave, la
aplicación arranca y solo las subidas se rechazan (`503`). El detector de tipo
de fichero de facturas sube a `common` para que inventario lo reutilice.

## Technical Context

**Language/Version**: Kotlin 2.2 / JVM 21, Spring Boot 4.0.8.

**Primary Dependencies**: las existentes. El cliente de Supabase Storage usa
`RestClient` de Spring (ya en `spring-boot-starter-web`): no hay SDK Java
oficial de Supabase y la API necesaria son tres llamadas REST (research D-001).
Tests: `okhttp-mockwebserver`, ya en el catálogo de versiones (facturas).

**Storage**: PostgreSQL (Flyway) para el material; Supabase Storage, bucket
privado, para la imagen.

**Testing**: JUnit 5 + MockK (servicio, detector, cliente de Storage contra
`MockWebServer`); Testcontainers Postgres (migración V26, mapeo JPA, RLS);
`app` para el esquema completo y el contrato OpenAPI.

**Target Platform**: servicio Linux (Docker), igual que el resto.

**Project Type**: web-service, módulo `:features:inventory`.

**Performance Goals**: listado con imágenes en una sola llamada de firmado por
petición (firmado por lotes, D-005), no una por material.

**Constraints**: imagen ≤ 10 MB, JPEG/PNG/WebP por firma de bytes; la consulta
no falla si Storage falla (FR-020); la app arranca sin Storage (FR-019).

**Scale/Scope**: cientos de materiales; 15 localizaciones fijas.

## Constitution Check

| Principio | Cumplimiento |
|---|---|
| I | Inventario solo depende de `common`. El detector de tipo de fichero, que necesitan facturas e inventario, **sube a `common`** en lugar de copiarse (D-004). |
| II | V26 nueva en el módulo `inventory`; V1–V5 no se editan. `ddl-auto: validate` sin cambios. |
| III | `historial_material` sigue append-only y su repositorio sin mutaciones. V26 vacía sus filas **de prueba** porque referencian materiales que desaparecen (D-007); la aplicación sigue sin ejecutar `UPDATE`/`DELETE` sobre ella. |
| IV | `/api/materiales/**` ya es `ADMIN`/`ENCARGADO` en `SecurityConfig`; las rutas de imagen caen bajo esa regla sin cambios. La persona del historial sale del JWT. |
| V | MockK para servicio, detector y cliente; Testcontainers para V26 y el mapeo; RLS genérico intacto. |
| VI | `SUPABASE_URL` y `SUPABASE_SERVICE_ROLE_KEY` solo por entorno, vacías por defecto; la clave nunca en logs ni `toString()`. Las rutas de objeto solo contienen UUID. |
| VII | Sin tablas nuevas; `materiales` conserva su RLS; `material_fotos` desaparece. El bucket es privado (las políticas de `storage.objects` deniegan por defecto; el backend usa la clave de servicio). |
| VIII | Rutas bajo `/api`; errores `{code, message}` desde `InventoryExceptionHandler`; DTO separados; validación `@Valid`; OpenAPI regenerado. |
| IX | Spec, plan y tareas en `specs/010-…`; README, ARCHITECTURE, DESPLIEGUE y `.env.example` actualizados. |

**Infraestructura nueva** ("Simplicidad deliberada"): Supabase Storage responde
a FR-013 de esta spec, y el producto ya despliega sobre Supabase, así que no
añade proveedor, solo un servicio del mismo. Sin violaciones que justificar.

**Re-check tras el diseño**: sin cambios; ningún principio queda en
incumplimiento.

## Project Structure

### Documentation (this feature)

```text
specs/010-inventory-materials-refactor/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/README.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code

```text
common/src/main/kotlin/com/granatum/core/ficheros/
└── DetectorTipoFichero.kt            # movido desde invoices (TipoDocumento + detector)

features/invoices/…                   # solo cambian imports al nuevo paquete

features/inventory/src/main/
├── resources/db/migration/V26__refactor_materiales.sql
└── kotlin/com/granatum/core/
    ├── api/
    │   ├── controllers/MaterialController.kt         # rutas de imagen + filtros
    │   ├── dto/{CreateMaterialRequest,UpdateMaterialRequest,MaterialDto,
    │   │        DimensionesDto,LocalizacionDto,UpdateCantidadRequest}.kt
    │   ├── mappers/MaterialDtoMappers.kt
    │   └── exception_handling/InventoryExceptionHandler.kt
    ├── domain/
    │   ├── model/{MaterialModel,DimensionesModel,Localizacion}.kt
    │   ├── type/Almacen.kt
    │   ├── port/AlmacenImagenes.kt
    │   └── exception/{ImagenNoValidaException,ImagenDemasiadoGrandeException,
    │                  AlmacenImagenesNoDisponibleException}.kt
    ├── infrastructure/
    │   ├── database/entities/{MaterialEntity,DimensionesEmbeddable,LocalizacionEmbeddable}.kt
    │   ├── database/mappers/MaterialMappers.kt
    │   └── storage/{ConfiguracionAlmacenImagenes,AlmacenImagenesSupabase,
    │                AlmacenImagenesDeshabilitado,AlmacenImagenesConfig}.kt
    └── service/MaterialService.kt

Se eliminan: EstadoMaterial, UnidadMedida, TamanoModel/Dto/Request/Embeddable.

app/src/main/resources/application.yml   # bloque inventory.imagenes
app/src/test/…/EsquemaCompletoRlsIT.kt    # sin material_fotos
docs/openapi.json                          # regenerado
```

**Structure Decision**: todo en el módulo existente `:features:inventory`, con
el layout de la constitución más un paquete `infrastructure/storage` (como
`infrastructure/claude` en facturas) y un puerto en `domain/port`.

## Complexity Tracking

Sin violaciones de la constitución.
