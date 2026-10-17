# Tasks: Eventos y reserva de materiales

**Input**: [spec.md](./spec.md), [plan.md](./plan.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/README.md](./contracts/README.md)

**Tests**: TDD (principio V).

## Phase 1: Fundamentos

- [X] T001 Enmienda de la constitución v2.5.0 en `.specify/memory/constitution.md`: principio IV amplía `EMPLEADO` (gestiona eventos) y `REPRESENTANTE` (los consulta) (D-008)
- [X] T002 Contratos en `common/src/main/kotlin/com/granatum/core/domain/contract/`: `StockMateriales` (`disponibles()`, `resumen(ids)`, `retirar(materialId, cantidad, usuarioId, motivo)`, `devolver(...)`), `MaterialStock`, `StockInsuficienteException(disponible)`, `MaterialInexistenteException`; `UsoMateriales.enUso(materialId)` (D-001)
- [X] T003 Módulo `features/events` (build, `settings.gradle.kts`, `app/build.gradle.kts`) y migración `V27__create_eventos_tables.sql` con RLS (data-model)

## Phase 2: Inventario implementa el stock

- [X] T004 Test IT `features/inventory/src/test/kotlin/com/granatum/core/StockMaterialesIT.kt`: retirar descuenta y anota historial; sin stock lanza con `disponible` y no cambia nada; retiradas concurrentes nunca dejan negativo; devolver a un material borrado no falla
- [X] T005 `MaterialRepository.retirar` (`UPDATE … WHERE cantidad >= :n`) y `devolver`; `infrastructure/contract/StockMaterialesInventario.kt` (D-003)
- [X] T006 `MaterialService.delete` consulta `UsoMateriales` → `MaterialEnUsoException` → `409 MATERIAL_EN_USO`; doble de `UsoMateriales` en los tests de inventario

## Phase 3: US1 + US2 — Eventos y materiales (P1) 🎯 MVP

- [X] T007 [US1] Test `EventoServiceTest` (MockK): crear, fecha pasada, editar, finalizado no se edita
- [X] T008 [US2] Test `EventoServiceTest`: asignar retira; aumentar retira la diferencia; disminuir devuelve; quitar devuelve todo; sin stock no guarda la línea
- [X] T009 [US1][US2] Dominio, entidades, repositorios (`findByIdForUpdate` con `PESSIMISTIC_WRITE`), `EventoService`
- [X] T010 [US1][US2] DTO, mappers, `EventoController`, `EventosExceptionHandler` (`STOCK_INSUFICIENTE` con `disponible`)
- [X] T011 [US2] `UsoMaterialesEventos` (implementa `UsoMateriales`)
- [X] T012 [US1][US2] IT `EventosIT` con Postgres real y un doble de `StockMateriales`: migración, ciclo completo, uso

## Phase 4: US3 + US4 — Finalizar y borrar (P2/P3)

- [X] T013 [US3] Test y `FinalizacionEventosJob` (00:05 Madrid, `events.finalizacion.cron`), `POST /finalizar` idempotente (D-005)
- [X] T014 [US4] Borrar devuelve los materiales si estaba programado

## Phase 5: Integración y documentación

- [X] T015 `SecurityConfig`: `GET /api/eventos/**` cuatro roles, escritura sin `REPRESENTANTE`; `app/…/AutorizacionEventosIT.kt`
- [X] T016 Regenerar `docs/openapi.json` (`OPENAPI_ACTUALIZAR=true ./gradlew :app:test --tests '*ContratoOpenApiIT'`)
- [X] T017 README (módulo eventos), `docs/ARCHITECTURE.md` (contratos `StockMateriales`/`UsoMateriales`), CHANGELOG
- [X] T018 `./gradlew build` en verde
