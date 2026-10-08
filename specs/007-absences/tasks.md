# Tasks: Ausencias y vacaciones

**Input**: Design documents from `/specs/007-absences/`

**Tests**: TDD. Cada test antes de su implementación.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [X] T001 Crear el módulo `features/absences` (`build.gradle.kts` como `features/timetracking`, solo `projects.common`), incluirlo en `settings.gradle.kts` y en las dependencias de `app/build.gradle.kts`
- [X] T002 [P] `AbsencesTestApplication`, `application.yml` de test y un doble de `DirectorioEmpleados` en `features/absences/src/test/kotlin/com/granatum/core/`

## Phase 2: Fundación

- [X] T003 Migración `features/absences/src/main/resources/db/migration/V22__create_ausencias_tables.sql` exactamente como data-model.md: `tipo VARCHAR(12)` en (`VACACIONES`,`PERMISO`,`BAJA_MEDICA`), `causa VARCHAR(24)` obligatoria solo en `PERMISO`, `hasta` nula solo en `BAJA_MEDICA`, `hasta >= desde`, `hasta - desde <= 365`, `comentario VARCHAR(500)` nunca en `BAJA_MEDICA`, `motivo_rechazo VARCHAR(500)` si y solo si `RECHAZADA`, `resuelta_*` con `APROBADA`/`RECHAZADA`, `cancelada_en` si y solo si `CANCELADA`, `version`; `derechos_vacaciones` con `dias BETWEEN 0 AND 366` y `anio BETWEEN 2000 AND 2100`; índices; RLS en las dos
- [X] T004 [P] `RowLevelSecurityIT` y `SinBorradoAusenciasTest` del módulo
- [X] T005 Modelo de dominio (`TipoAusencia`, `CausaPermiso`, `EstadoAusencia`, `Ausencia`, `SaldoVacaciones`), entidades y repositorios sin `delete` en `features/absences/src/main/kotlin/com/granatum/core/`
- [X] T006 Excepciones con nombres únicos en todo el producto y `AusenciasExceptionHandler` con los códigos del contrato
- [X] T007 Reglas en `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt`: `/api/ausencias/derechos/**` `ADMIN`; `/api/ausencias/registro`, `/api/ausencias/*/aprobar`, `/rechazar`, `/alta` `ENCARGADO`/`ADMIN`; resto de `/api/ausencias/**` `ADMIN`/`ENCARGADO`/`EMPLEADO`
- [X] T008 Configuración `absences.vacaciones.dias-anuales: ${ABSENCES_DIAS_VACACIONES:30}` en `app` y en el test del módulo

## Phase 3: US1 - Vacaciones y resolución (P1) 🎯 MVP

- [X] T009 [P] [US1] Test unitario `CalculadoraSaldoTest`: días naturales dentro del año, cruce de año, pendientes y aprobadas por separado, canceladas y rechazadas no cuentan, solo `VACACIONES` cuenta, baja abierta no cuenta
- [X] T010 [P] [US1] Implementar `CalculadoraSaldo` (pura) en `domain/service/CalculadoraSaldo.kt`
- [X] T011 [US1] Test `SolicitudVacacionesIT`: solicitud `PENDIENTE` y saldo; aprobar y rechazar (motivo obligatorio) por `ENCARGADO`; resolución propia `RESOLUCION_PROPIA`; resolver dos veces `AUSENCIA_NO_MODIFICABLE`; saldo insuficiente; vacaciones propias en el pasado `RANGO_INVALIDO`; persona inexistente o inactiva
- [X] T012 [US1] `AusenciaService` (solicitar, aprobar, rechazar) con `pg_advisory_xact_lock(7007, hashtext(empleado_id))` y `SaldoVacacionesService`
- [X] T013 [US1] `AusenciaController` y DTOs (`toString` sin comentario ni motivo)

## Phase 4: US2 - Sin solapamientos (P1)

- [X] T014 [US2] Test `SolapamientoAusenciasIT`: solapa con aprobada o pendiente → `409`; con rechazada o cancelada no; con baja abierta sí; 20 peticiones simultáneas solapadas de la misma persona → una sola admitida; dos personas distintas no se bloquean
- [X] T015 [US2] Comprobación de solapamiento en `AusenciaService` bajo el bloqueo (mutación: quitar el bloqueo debe poner el test en rojo)

## Phase 5: US3 - Bajas y permisos (P2)

- [X] T016 [US3] Test `BajasYPermisosIT`: registro de baja por `ENCARGADO` → `APROBADA` sin fin, no consume vacaciones; con comentario → `422`; para sí mismo → `403`; alta fija `hasta` una vez; permiso con causa `PENDIENTE` sin consumir; permiso sin causa y vacaciones con causa → `422`; la base de datos rechaza una baja con comentario
- [X] T017 [US3] `registrar` y `darAlta` en `AusenciaService`; rutas `POST /registro` y `POST /{id}/alta`

## Phase 6: US4 - Cancelar (P2)

- [X] T018 [US4] Test `CancelacionAusenciasIT`: pendiente propia, aprobada futura → `CANCELADA`; empezada → `409`; ajena → `404`
- [X] T019 [US4] `cancelar` en el servicio y la ruta

## Phase 7: US5 - Calendario y saldo (P2)

- [X] T020 [US5] Test `ConsultaAusenciasIT`: rango de toda la plantilla para `ENCARGADO`; `EMPLEADO` solo las suyas aunque pida otra; detalle ajeno `404`; derecho ajustado por `ADMIN` cambia el saldo; saldo de otra persona solo para `ENCARGADO`/`ADMIN`
- [X] T021 [US5] Consultas, saldo y `PUT /derechos/{empleadoId}/{anio}`

## Phase 8: Pulido

- [X] T022 [P] `app`: `AutorizacionAusenciasIT` (`REPRESENTANTE` 403 en todo; `EMPLEADO` 403 en aprobar/registrar/derechos; sin token 401) y `SinColisionDeClasesIT` con el módulo nuevo
- [X] T023 [P] `EsquemaCompletoRlsIT`: `ausencias`, `derechos_vacaciones` (veintidós migraciones)
- [X] T024 [P] Logs en `DEBUG` sin comentario ni motivo (`SinDatosPersonalesEnAusenciasIT`)
- [X] T025 [P] README (estado, rutas, variable), ARCHITECTURE (módulo, bloqueo por persona, numeración V22) y constitución (principio IV, MINOR)
- [ ] T026 `./gradlew build --rerun-tasks` en verde
