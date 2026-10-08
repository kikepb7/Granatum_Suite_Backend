# Tasks: Registro del personal

**Input**: Design documents from `/specs/005-staff-registration/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/README.md, quickstart.md

**Tests**: TDD. Cada test se escribe antes de su implementación y debe fallar primero.

**Organization**: por historia de usuario. Rutas relativas a la raíz del repo; `auth` = `features/auth/src`.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Fundación (bloquea todas las historias)

- [ ] T001 [P] Crear el contrato `FichasPersonal` y `AltaFichaPersonal(nombre, documento, puesto, tipoContrato: String, fechaAlta: LocalDate)` en `common/src/main/kotlin/com/granatum/core/domain/contract/FichasPersonal.kt`, con KDoc del porqué (D-005: separado de `DirectorioEmpleados`, que no expone datos personales)
- [ ] T002 Test de la implementación en `features/timetracking/src/test/kotlin/com/granatum/core/FichasPersonalJpaIT.kt`: `buscarPorDocumento` normaliza (`12345678-z` encuentra `12345678Z`) y devuelve `null` si no hay ficha; `crear` crea la ficha activa con `tipoContrato` del enum y devuelve su id; un tipo desconocido falla
- [ ] T003 Implementar `FichasPersonalJpa` en `features/timetracking/src/main/kotlin/com/granatum/core/infrastructure/directorio/FichasPersonalJpa.kt` sobre `EmpleadoService.crear` y `EmpleadoRepository` (añadir `findByDocumentoIdentidad` si falta), `@Transactional` con propagación por defecto
- [ ] T004 [P] Migración `auth/main/resources/db/migration/V20__create_solicitudes_registro_table.sql` exactamente como data-model.md: `estado VARCHAR(12)` con `CHECK` de los cinco estados, `email VARCHAR(254)`, `nombre VARCHAR(150)`, `documento_identidad VARCHAR(20)`, `password_hash VARCHAR(255)`, `codigo_hash CHAR(64)` (todos nulos), `intentos_codigo SMALLINT NOT NULL DEFAULT 0 CHECK (intentos_codigo BETWEEN 0 AND 5)`, `creada_en`, `resuelta_en`, `resuelta_por`, `cuenta_id REFERENCES cuentas_acceso(id)`; los cuatro `CHECK` de data-model; índices `(estado, creada_en)`, parciales no únicos en `email` y `documento_identidad` `WHERE estado = 'PENDIENTE'`; `ENABLE ROW LEVEL SECURITY` (nunca `FORCE`)
- [ ] T005 [P] Migración `auth/main/resources/db/migration/V21__add_registro_eventos_seguridad.sql`: `DROP CONSTRAINT ck_eventos_seguridad_tipo` y `ADD` con los 13 tipos de V14 más `REGISTRO_SOLICITADO`, `REGISTRO_DUPLICADO`, `ADMIN_INICIAL_CREADO`, `ARRANQUE_RECHAZADO`, `REGISTRO_APROBADO`, `REGISTRO_RECHAZADO`, `REGISTRO_ANULADO`, `REGISTRO_CADUCADO`; y añadirlos a `TipoEventoSeguridad`
- [ ] T006 Ampliar `auth/test/kotlin/com/granatum/core/RowLevelSecurityIT.kt` con `solicitudes_registro` en las tablas esperadas
- [ ] T007 [P] `EstadoSolicitudRegistro`, `SolicitudRegistro` (dominio) y `SolicitudRegistroEntity` (sin `data class`, `toString` sin datos personales) en `auth/main/kotlin/com/granatum/core/domain/type/`, `domain/model/`, `infrastructure/database/entities/`
- [ ] T008 `SolicitudRegistroRepository` extendiendo `Repository<SolicitudRegistroEntity, UUID>` **sin `delete`**: `save`, `findById`, `bloquear(id)` con `@Lock(PESSIMISTIC_WRITE)`, `countByEstado`, `pendientesOrdenadas()`, `anularPendientesConEmail/ConDocumento(…, excepto)` y `caducarAnterioresA(corte, ahora)` como `@Modifying` que también ponen a `NULL` los datos personales, en `auth/main/kotlin/com/granatum/core/infrastructure/database/repositories/SolicitudRegistroRepository.kt`; y ampliar el test por reflexión de "sin delete" de `auth` si existe (o crear `SinBorradoSolicitudesIT`)
- [ ] T009 Ampliar el doble `auth/test/kotlin/com/granatum/core/DirectorioEmpleadosDoble.kt` para que implemente también `FichasPersonal` (fichas en memoria por documento; `crear` registra además la persona como activa)
- [ ] T010 Configuración `auth.registro.*` en `app/src/main/resources/application.yml`: `codigo-arranque: ${AUTH_CODIGO_ARRANQUE:}` (secreto, sin valor), `max-pendientes: ${AUTH_REGISTRO_MAX_PENDIENTES:50}`, `caducidad-dias: ${AUTH_REGISTRO_CADUCIDAD_DIAS:7}`, `caducidad-cron`; y en el `application.yml` de test de `auth`; `.env.example` con `AUTH_CODIGO_ARRANQUE=` vacío
- [ ] T011 Reglas en `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt`: `POST /api/auth/registro` pública; `/api/auth/registros`, `/api/auth/registros/**` solo `ADMIN`
- [ ] T012 [P] Excepciones y códigos en `auth/main/kotlin/com/granatum/core/domain/exception/RegistroExceptions.kt` y `AuthExceptionHandler`: `CODIGO_ARRANQUE_INVALIDO` 403, `REGISTRO_NO_DISPONIBLE` 503 con `Retry-After: 3600`, `DOCUMENTO_INVALIDO` 422, `SOLICITUD_NO_ENCONTRADA` 404, `SOLICITUD_NO_PENDIENTE` 409, `CODIGO_INCORRECTO` 422, `DATOS_FICHA_REQUERIDOS` 422

**Checkpoint**: build en verde; la tabla existe con RLS y el doble compila.

---

## Phase 2: User Story 1 - Primer administrador (P1) 🎯 MVP

**Goal**: el jefe crea la primera cuenta `ADMIN` con el código de arranque.

**Independent Test**: base vacía + código configurado → registro `201` → login con rol `ADMIN`.

- [ ] T013 [P] [US1] Test unitario `auth/test/kotlin/com/granatum/core/CodigoVerificacionTest.kt`: 8 caracteres del alfabeto `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`; la huella depende del id; `coincide` en tiempo constante, insensible a minúsculas y espacios
- [ ] T014 [P] [US1] Implementar `CodigoVerificacion` (puro) en `auth/main/kotlin/com/granatum/core/domain/service/CodigoVerificacion.kt` con `SecureRandom`, `SHA-256(id ‖ código)` y `MessageDigest.isEqual`
- [ ] T015 [US1] Test `auth/test/kotlin/com/granatum/core/PrimerAdminIT.kt`: con código correcto y sin `ADMIN` → cuenta `ADMIN` sin cambio obligatorio y ficha nueva con `Dirección`/`JORNADA_COMPLETA`/hoy; con ficha existente por documento → se vincula; código incorrecto, no configurado o ya hay `ADMIN` → `403` y nada creado; correo con cuenta → `409`; pendientes con el mismo correo → `ANULADA`; dos arranques simultáneos con correos distintos → un solo `ADMIN`; eventos `ADMIN_INICIAL_CREADO`/`ARRANQUE_RECHAZADO`
- [ ] T016 [US1] Implementar el camino de arranque de `RegistroService` en `auth/main/kotlin/com/granatum/core/service/RegistroService.kt`: validar, cifrar con `VerificadorAcotado` fuera de la transacción, y en una `TransactionTemplate` con `pg_advisory_xact_lock` comprobar código (tiempo constante) y ausencia de `ADMIN`, ficha por contrato, cuenta, anulación de pendientes
- [ ] T017 [US1] `RegistroController` `POST /api/auth/registro` y `RegistroDtos` (`RegistroRequest` con `@Valid`, `toString` sin contraseña ni código; `RegistroResponse`) en `auth/main/kotlin/com/granatum/core/api/`; `201` en el arranque

**Checkpoint**: US1 se valida sola.

---

## Phase 3: User Story 2 - Registro y aprobación (P1)

**Goal**: una persona se registra y un `ADMIN` la aprueba con su código.

**Independent Test**: registrar → login `401` → aprobar con código → login correcto.

- [ ] T018 [US2] Test `auth/test/kotlin/com/granatum/core/RegistroIT.kt`: `202` con código de 8; solicitud `PENDIENTE` con huellas y sin claro; login `401 CREDENCIALES_INVALIDAS`; contraseña débil `422 PASSWORD_DEBIL`; documento con letra mala `422 DOCUMENTO_INVALIDO`; con 50 pendientes `503 REGISTRO_NO_DISPONIBLE` sin crear nada
- [ ] T019 [US2] Implementar el camino normal de `RegistroService`: límite de pendientes, validación de documento con `NifValidator` de `common`, código, hash, guardado y evento `REGISTRO_SOLICITADO`
- [ ] T020 [US2] Test `auth/test/kotlin/com/granatum/core/AprobacionRegistroIT.kt`: aprobar con ficha existente vincula; sin ficha y con datos crea ficha y cuenta; sin datos `422 DATOS_FICHA_REQUERIDOS` y sigue pendiente; código incorrecto `422` y cuenta el intento aunque falle; quinto incorrecto anula (`409`); la cuenta tiene la contraseña elegida y no requiere cambio; ficha ya con cuenta `409 CUENTA_YA_EXISTE`; correo ya con cuenta `409 EMAIL_YA_REGISTRADO` y anula; dos aprobaciones simultáneas → una cuenta; login correcto tras aprobar
- [ ] T021 [US2] Implementar `AprobacionRegistroService.aprobar` en `auth/main/kotlin/com/granatum/core/service/AprobacionRegistroService.kt` (D-006: intento fallido confirmado antes de lanzar; ficha y cuenta en una transacción; anular otras pendientes con el mismo correo o documento; vaciar datos personales; evento `REGISTRO_APROBADO`)
- [ ] T022 [US2] `SolicitudRegistroController` `POST /api/auth/registros/{id}/aprobar` con `AprobarRegistroRequest` (`rol` obligatorio, `tipoContrato` con patrón `JORNADA_COMPLETA|PARCIAL|POR_HORAS`, `toString` sin código) en `auth/main/kotlin/com/granatum/core/api/`

---

## Phase 4: User Story 3 - Sin enumeración (P1)

**Goal**: el registro no delata correos existentes.

**Independent Test**: comparar respuestas y tiempos con correos nuevos y existentes.

- [ ] T023 [US3] Test `auth/test/kotlin/com/granatum/core/IndistinguibilidadRegistroIT.kt`: correo con cuenta → misma forma de respuesta, sin solicitud aprobable, evento `REGISTRO_DUPLICADO`; correo con pendiente → otra solicitud independiente; 50+50 registros con diferencia de medias < 10% (SC-004), con calentamiento previo
- [ ] T024 [US3] Ajustar `RegistroService` para que el camino del duplicado haga el mismo trabajo caro (validación, código, hash) y devuelva la misma respuesta

---

## Phase 5: User Story 4 - Gestión de solicitudes (P2)

**Goal**: el `ADMIN` lista y rechaza.

**Independent Test**: crear dos, listar, rechazar una.

- [ ] T025 [US4] Test `auth/test/kotlin/com/granatum/core/GestionSolicitudesIT.kt`: la lista solo trae pendientes, de la más antigua, con `empleadoExistenteId` cuando hay ficha y sin contraseña ni código en el JSON; rechazar `204`, estado `RECHAZADA` y datos a `NULL`; resolver dos veces `409`; id inexistente `404`; aprobar una anula las demás con el mismo correo o documento
- [ ] T026 [US4] Implementar `listarPendientes` y `rechazar` en `AprobacionRegistroService` y sus rutas `GET /api/auth/registros` y `POST /api/auth/registros/{id}/rechazar` en `SolicitudRegistroController`

---

## Phase 6: User Story 5 - Conservación (P2)

**Goal**: nada pendiente para siempre, nada personal en lo resuelto.

**Independent Test**: dejar caducar una solicitud.

- [ ] T027 [US5] Test `auth/test/kotlin/com/granatum/core/CaducidadSolicitudesIT.kt`: con un reloj fijo, una pendiente de más de 7 días pasa a `CADUCADA` sin datos personales y una de 6 sigue; evento `REGISTRO_CADUCADO`; y la base rechaza una fila resuelta con datos personales (los `CHECK` de V20)
- [ ] T028 [US5] Implementar `CaducidadSolicitudesJob` en `auth/main/kotlin/com/granatum/core/scheduling/CaducidadSolicitudesJob.kt` con `@Scheduled(cron = "${auth.registro.caducidad-cron}", zone = "Europe/Madrid")`

---

## Phase 7: Pulido

- [ ] T029 [P] Test `app/src/test/kotlin/com/granatum/core/AutorizacionRegistrosIT.kt`: rutas de `/api/auth/registros` con `ENCARGADO`, `EMPLEADO`, `REPRESENTANTE` → `403`, sin token → `401`; `POST /api/auth/registro` sin token responde (no `401`)
- [ ] T030 [P] Test `app/src/test/kotlin/com/granatum/core/RegistroDePuntaAPuntaIT.kt` con la implementación real de `timetracking`: arranque, registro, aprobación creando ficha, login, y la ficha visible en `GET /api/empleados`
- [ ] T031 [P] Ampliar `auth/test/kotlin/com/granatum/core/SinDatosPersonalesEnLogsIT.kt` (log en `DEBUG`): registrar, aprobar, rechazar y arrancar no escriben correo, nombre, documento, contraseña ni código
- [ ] T032 [P] Añadir `solicitudes_registro` a `app/src/test/kotlin/com/granatum/core/EsquemaCompletoRlsIT.kt` (veintiuna migraciones)
- [ ] T033 [P] README: estado del proyecto, rutas de registro, sección "El primer administrador" reescrita (código de arranque, retirarlo después, segundo `ADMIN`, procedimiento D-010), variables `AUTH_CODIGO_ARRANQUE`, `AUTH_REGISTRO_*`
- [ ] T034 [P] `docs/ARCHITECTURE.md`: contrato `FichasPersonal`, V20–V21, por qué el registro vive en `auth`; y en `specs/002-auth/spec.md` una nota de que la 005 revierte "sin registro público"
- [ ] T035 Recorrer `specs/005-staff-registration/quickstart.md` contra la app y corregirlo donde no coincida
- [ ] T036 `./gradlew build --rerun-tasks` en verde; `git status` sin `.env` ni artefactos

---

## Dependencies & Execution Order

- Fundación (T001–T012) bloquea todo. T002→T003; T004→T006–T008.
- US1 (T013–T017) primero: crea el `ADMIN` que necesitan US2 y US4 en los tests de `app`.
- US2 (T018–T022) después de US1 (comparte `RegistroService` y el controller).
- US3 (T023–T024) después de US2 (modifica el mismo servicio).
- US4 (T025–T026) y US5 (T027–T028) después de US2; entre sí, independientes.
- Pulido al final.

### Parallel Opportunities

- T001, T004, T005, T007, T012 en paralelo (ficheros distintos).
- T013 y T014 en paralelo con T015.
- US4 y US5 en paralelo.
- T029–T034 en paralelo.

## Implementation Strategy

MVP = Fundación + US1: desbloquea el primer despliegue. Después US2+US3 juntas
(el registro no se publica sin la protección contra enumeración), y US4/US5.
Commit al final de cada fase con `./gradlew build` en verde.
