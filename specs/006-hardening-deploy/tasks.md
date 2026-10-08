# Tasks: Endurecimiento y despliegue

**Input**: Design documents from `/specs/006-hardening-deploy/`

**Prerequisites**: plan.md, spec.md, research.md, contracts/README.md, quickstart.md

**Tests**: TDD. Cada test antes de su implementación.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Fundación

- [X] T001 [P] Test unitario `app/src/test/kotlin/com/granatum/core/CuboFichasTest.kt`: con reloj inyectado, `N` consumos seguidos pasan y el siguiente no, con espera = segundos hasta la próxima ficha (redondeo hacia arriba, mínimo 1); se rellena de forma continua; no supera la capacidad tras mucho tiempo
- [X] T002 [P] Implementar `CuboFichas` (puro) en `app/src/main/kotlin/com/granatum/core/api/security/CuboFichas.kt`
- [X] T003 Test unitario `app/src/test/kotlin/com/granatum/core/LimitadorPorOrigenTest.kt`: direcciones independientes; el mapa no supera el máximo y olvida la menos usada; cupos independientes entre sí
- [X] T004 Implementar `LimitadorPorOrigen` y las propiedades `seguridad.limites.*` (capacidad y periodo por cupo, `max-direcciones`) en `app/src/main/kotlin/com/granatum/core/api/security/LimitadorPorOrigen.kt` y `app/src/main/resources/application.yml` (`SEGURIDAD_LIMITE_LOGIN` 10/1m, `SEGURIDAD_LIMITE_SESION` 30/1m, `SEGURIDAD_LIMITE_REGISTRO` 5/1h, `SEGURIDAD_LIMITE_GENERAL` 300/1m, `SEGURIDAD_LIMITE_MAX_DIRECCIONES` 10000)
- [X] T005 Límites muy altos en `app/src/test/resources/config/application.yml`, para que el resto de tests de `app` (todos desde `127.0.0.1`) no choquen con ellos; las pruebas del limitador los bajan con `@TestPropertySource`

---

## Phase 2: User Story 1 - Límite en rutas públicas (P1) 🎯 MVP

**Independent Test**: superar el cupo de inicio de sesión desde una dirección → `429` con `Retry-After`; otra dirección sigue entrando.

- [X] T006 [US1] Test `app/src/test/kotlin/com/granatum/core/LimitePorOrigenIT.kt`: con cupo de login 3, la cuarta petición `429 DEMASIADAS_PETICIONES` con `Retry-After` y sin evento `LOGIN_*` nuevo (no llegó al servicio); con `forward-headers-strategy=native`, otra dirección en `X-Forwarded-For` no se ve afectada; registro limitado también con código de arranque; renovación y cierre con su propio cupo
- [X] T007 [US1] Test `app/src/test/kotlin/com/granatum/core/LimiteSinProxyIT.kt`: sin estrategia de reenvío, cambiar `X-Forwarded-For` **no** salta el límite (FR-005)
- [X] T008 [US1] Implementar `FiltroLimitePorOrigen` (`OncePerRequestFilter`, sin logs) en `app/src/main/kotlin/com/granatum/core/api/security/FiltroLimitePorOrigen.kt` y registrarlo en `SecurityConfig` antes de `JwtAuthFilter`; `server.forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:none}` en `application.yml`
- [X] T009 [US1] Ampliar un test de logs en `app` (`SinDireccionesEnLogsIT.kt`) con el log en `DEBUG`: peticiones limitadas y no limitadas no escriben la dirección de origen

---

## Phase 3: User Story 2 - Seguro por defecto (P1)

**Independent Test**: arrancar sin perfil → la ruta de desarrollo no existe.

- [X] T010 [US2] Test `app/src/test/kotlin/com/granatum/core/PerfilPorDefectoTest.kt`: sin `SPRING_PROFILES_ACTIVE`, el valor efectivo de `spring.profiles.active` en `application.yml` resuelve a `prod` y el contexto no tiene `DevAuthController`
- [X] T011 [US2] `application.yml`: `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:prod}`; `build-logic/src/main/kotlin/granatum.spring-boot-app.gradle.kts`: `bootRun` fija `SPRING_PROFILES_ACTIVE=dev` si no viene del entorno ni de `.env`
- [X] T012 [US2] Test `app/src/test/kotlin/com/granatum/core/ErroresSinTrazaIT.kt`: ruta inexistente `404 RECURSO_NO_ENCONTRADO`, método no admitido `405 METODO_NO_PERMITIDO`, y ninguna respuesta contiene `trace`, `exception` ni `com.granatum`
- [X] T013 [US2] `server.error.*` en `application.yml` y `ErroresJson` (`DefaultErrorAttributes`) en `app/src/main/kotlin/com/granatum/core/api/errors/ErroresJson.kt`
- [X] T014 [US2] Test `features/auth/src/test/kotlin/com/granatum/core/ComprobacionCodigoArranqueTest.kt`: vacío arranca; 23 caracteres falla nombrando `AUTH_CODIGO_ARRANQUE` sin el valor; 24 arranca
- [X] T015 [US2] Implementar `ComprobacionCodigoArranque` en `features/auth/src/main/kotlin/com/granatum/core/infrastructure/crypto/ComprobacionCodigoArranque.kt`

---

## Phase 4: User Story 3 - Cabeceras y CORS (P2)

**Independent Test**: inspeccionar cabeceras; comprobación previa con origen admitido y no admitido.

- [ ] T016 [US3] Test `app/src/test/kotlin/com/granatum/core/CabecerasSeguridadIT.kt`: las cabeceras del contrato en una respuesta `200`, una `401` y una `404`
- [ ] T017 [US3] Test `app/src/test/kotlin/com/granatum/core/CorsIT.kt` con `seguridad.cors.origenes=https://app.granatum.es`: comprobación previa admitida con métodos y cabeceras; origen ajeno `403`; respuesta real expone `Content-Disposition` y `Retry-After`; y `CorsSinOrigenesIT` con la lista vacía: todo origen `403`
- [ ] T018 [US3] Cabeceras en `SecurityConfig` y `ConfiguracionCors` (`CORS_ALLOWED_ORIGINS`) en `app/src/main/kotlin/com/granatum/core/api/security/ConfiguracionCors.kt`

---

## Phase 5: User Story 4 - Imagen y CI (P2)

**Independent Test**: construir la imagen, arrancarla, comprobar usuario y salud.

- [ ] T019 [P] [US4] `Dockerfile` multietapa (research.md D-009) y `.dockerignore`
- [ ] T020 [P] [US4] Servicio `app` en `docker-compose.yml` con `profiles: ["app"]`, `env_file: .env`, `DB_HOST=postgres`, dependiente de la salud de `postgres` (que gana `healthcheck`)
- [ ] T021 [US4] `.github/workflows/ci.yml`: `push` en todas las ramas y `pull_request`; trabajo `imagen` que construye la imagen y comprueba `id -u` ≠ 0 y que `/app/.env` no existe
- [ ] T022 [US4] Construir la imagen localmente, arrancarla contra el Postgres temporal y comprobar salud, usuario y perfil

---

## Phase 6: User Story 5 - Límite general (P3)

- [ ] T023 [US5] Test `app/src/test/kotlin/com/granatum/core/LimiteGeneralIT.kt`: con cupo general 5, la sexta petición autenticada a cualquier ruta `/api` → `429`; `/actuator/health` nunca se limita
- [ ] T024 [US5] Aplicar el cupo `general` a `/api/**` en `FiltroLimitePorOrigen` (si no quedó ya hecho en T008)

---

## Phase 7: Pulido

- [ ] T025 [P] README: sección "Despliegue" (imagen, perfil, variables `SEGURIDAD_LIMITE_*`, `CORS_ALLOWED_ORIGINS`, `SERVER_FORWARD_HEADERS_STRATEGY`, varias instancias), estado del proyecto
- [ ] T026 [P] `docs/ARCHITECTURE.md`: limitador (por qué propio, por qué cubeta, por qué `remoteAddr`), perfil por defecto, errores; constitución: nota de deuda cerrada sobre el perfil por defecto (PATCH)
- [ ] T027 Recorrer `specs/006-hardening-deploy/quickstart.md` y corregirlo
- [ ] T028 `./gradlew build --rerun-tasks` en verde; `git status` limpio

## Dependencies & Execution Order

Fundación → US1 → US2 → US3 → US4 → US5 → Pulido. US3 y US4 son independientes
entre sí. T008 (filtro) es prerequisito de T023.
