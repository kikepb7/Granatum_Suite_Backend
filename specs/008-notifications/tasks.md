# Tasks: Notificaciones

**Input**: Design documents from `/specs/008-notifications/`

**Tests**: TDD. Cada test antes de su implementación.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup y fundación

- [X] T001 `AvisoDominio(tipo: TipoAviso, referenciaId, titularId?, autorId?)` y `TipoAviso` (los nueve tipos) en `common/src/main/kotlin/com/granatum/core/domain/event/AvisoDominio.kt`; contrato `DirectorioRoles.empleadosConRol(roles): Set<EntityId>` en `common/src/main/kotlin/com/granatum/core/domain/contract/DirectorioRoles.kt`
- [X] T002 Test e implementación de `DirectorioRolesJpa` en `features/auth` (sobre `cuentas_acceso`, una consulta)
- [X] T003 Módulo `features/notifications` (solo `common`), en `settings.gradle.kts` y `app`; aplicación y `application.yml` de test; doble de `DirectorioRoles`
- [X] T004 Migración `V23__create_notificaciones_table.sql` como data-model.md (`tipo VARCHAR(24)` con `CHECK` de los nueve tipos, `UNIQUE (destinatario_id, tipo, referencia_id)`, índices, RLS); `RowLevelSecurityIT` del módulo
- [X] T005 Entidad, repositorio (insertar con `ON CONFLICT DO NOTHING`, bandeja, contar, marcar, limpiar) y regla `/api/notificaciones/**` para los cuatro roles en `SecurityConfig`

## Phase 2: US2 - Lo pendiente llega a quien lo resuelve (P1) 🎯

- [X] T006 [US2] Test `DestinatariosIT`: `AUSENCIA_PENDIENTE` y `CORRECCION_PENDIENTE` a `ENCARGADO`+`ADMIN` menos el autor; `REGISTRO_PENDIENTE` solo a `ADMIN`; sin destinatarios no falla; publicado dentro de una transacción que se deshace → ningún aviso; un fallo al guardar no propaga
- [X] T007 [US2] `OyenteAvisos` (`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`, `REQUIRES_NEW`) y `Destinatarios` en `features/notifications`
- [X] T008 [US2] Publicar: `CorreccionService.solicitar` (`CORRECCION_PENDIENTE`, autor = solicitante), `AusenciaService.solicitar` (`AUSENCIA_PENDIENTE`), `RegistroService` (`REGISTRO_PENDIENTE` al guardar una solicitud), con tests `@RecordApplicationEvents` en cada módulo

## Phase 3: US1 - Salida olvidada (P1)

- [X] T009 [US1] Test `AvisoSalidaOlvidadaJobIT` (timetracking): abierto > 10 h publica `FICHAJE_SIN_SALIDA` con titular; < 10 h no; `MarcadoFichajesIncompletosJob` publica `FICHAJE_INCOMPLETO` por fichaje
- [X] T010 [US1] `AvisoSalidaOlvidadaJob` (`timetracking.aviso-sin-salida.horas` 10, cron cada 30 min) y publicación en `MarcadoFichajesIncompletosJob`
- [X] T011 [US1] Test de deduplicación en `notifications`: el mismo `FICHAJE_SIN_SALIDA` dos veces → un aviso

## Phase 4: US3 - Resoluciones (P2)

- [X] T012 [US3] Publicar `CORRECCION_APROBADA/RECHAZADA` (titular = dueño del fichaje) y `AUSENCIA_APROBADA/RECHAZADA` (titular = persona), con tests de publicación

## Phase 5: US4 - Bandeja (P2)

- [X] T013 [US4] Test `BandejaNotificacionesIT`: orden, solo no leídas, máximo 100, total no leídos, marcar una y todas, ajena `404`, el JSON con mensaje fijo y sin texto libre
- [X] T014 [US4] `NotificacionService` y `NotificacionController`

## Phase 6: US5 - Limpieza (P3)

- [X] T015 [US5] Test y `LimpiezaNotificacionesJob` (leídas > 90 días, todas > 180, configurable)

## Phase 7: Pulido

- [X] T016 [P] `app`: punta a punta (ausencia pedida → aviso al `ENCARGADO` con cuenta → aprobada → aviso a la persona), permisos de la bandeja, `SinColisionDeClasesIT`, `EsquemaCompletoRlsIT`
- [X] T017 [P] README, ARCHITECTURE (eventos de dominio como contrato), `.env.example`
- [X] T018 `./gradlew build --rerun-tasks` en verde *(717 tests, 0 fallos, con el perfil `prod`)*
