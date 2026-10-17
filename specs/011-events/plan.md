# Implementation Plan: Eventos y reserva de materiales

**Branch**: `events-feature` | **Date**: 2026-10-17 | **Spec**: [spec.md](./spec.md)

## Summary

Módulo nuevo `:features:events` con eventos (`eventos`) y sus materiales (`evento_materiales`).
Asignar material retira unidades del inventario y finalizar o borrar el evento las devuelve, en la
misma transacción, a través de dos contratos nuevos en `common` (`StockMateriales`, que implementa
inventario, y `UsoMateriales`, que implementa eventos). Una tarea diaria finaliza los eventos de
días anteriores. La plantilla (`ADMIN`, `ENCARGADO`, `EMPLEADO`) escribe; `REPRESENTANTE` lee.

## Technical Context

**Language/Version**: Kotlin 2.2 / JVM 21, Spring Boot 4.0.8. **Dependencias**: las existentes.
**Storage**: PostgreSQL, migración `V27`. **Testing**: JUnit 5 + MockK (servicio), Testcontainers
(repositorios, concurrencia, tarea), `app` (autorización, contrato HTTP y OpenAPI).

## Constitution Check

| Principio | Cumplimiento |
|---|---|
| I. Features independientes | `events` depende solo de `common`; cruce por `StockMateriales`/`UsoMateriales` (D-001). `inventory` implementa uno y consume otro sin conocer `events`. |
| II. Flyway | `V27__create_eventos_tables.sql` en el módulo; `ddl-auto: validate`. |
| III. Registro horario | No aplica. |
| IV. Roles | **Enmienda v2.5.0** (D-008): `EMPLEADO` gestiona eventos, `REPRESENTANTE` los lee. `EMPLEADO` sigue sin `/api/materiales`. Test de autorización en `app`. |
| V. Tests | Unitarios del servicio con MockK; ITs con Postgres real (migración, retirada concurrente, tarea, uso). |
| VI. Secretos/logs | Sin secretos nuevos. Los logs de la tarea solo cuentan eventos, sin nombres ni ids de persona. |
| VII. RLS | Ambas tablas activan RLS en V27; `RowLevelSecurityIT` del módulo y `EsquemaCompletoRlsIT`. |
| VIII. Contrato | Rutas bajo `/api/eventos`, DTO separados, errores desde `EventosExceptionHandler`, `@Valid`. |
| IX. Documentación | Esta carpeta, README, `docs/ARCHITECTURE.md` (contratos nuevos), `docs/openapi.json`. |

## Project Structure

```
common/src/main/kotlin/com/granatum/core/domain/contract/
├── StockMateriales.kt            # + MaterialStock, StockInsuficienteException, MaterialInexistenteException
└── UsoMateriales.kt
features/inventory/…/infrastructure/contract/StockMaterialesInventario.kt
features/inventory/…/service/MaterialService.kt          # delete consulta UsoMateriales
features/events/
├── build.gradle.kts
└── src/main/kotlin/com/granatum/core/
    ├── api/{controllers,dto,mappers,exception_handling}/
    ├── domain/{model,exception,type}/
    ├── infrastructure/{database/{entities,mappers,repositories},contract/UsoMaterialesEventos.kt}
    ├── scheduling/FinalizacionEventosJob.kt
    └── service/EventoService.kt
app/…/SecurityConfig.kt                                   # reglas /api/eventos
```
