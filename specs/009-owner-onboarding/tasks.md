# Tasks: Alta del personal por el propietario

**Tests**: TDD. Cada test antes de su implementación.

## Phase 1: US1 - Solo el propietario se registra (P1)

- [ ] T001 [US1] Ajustar `features/auth/src/test/kotlin/com/granatum/core/PrimerAdminIT.kt` (sin la anulación de solicitudes) y probar en `app` que `POST /api/auth/registro` sin código responde `400 VALIDACION` y no crea nada
- [ ] T002 [US1] `RegistroService` solo con el camino de arranque; `codigoArranque` obligatorio en `RegistroRequest`

## Phase 2: US2 - Alta por el `ADMIN` (P1)

- [ ] T003 [US2] Test `features/auth/src/test/kotlin/com/granatum/core/AltaPersonaIT.kt`: ficha y cuenta nuevas con contraseña provisional que cumple la política y exige cambio; ficha existente sin cuenta se vincula; ficha con cuenta `CUENTA_YA_EXISTE` sin crear nada; correo usado `EMAIL_YA_REGISTRADO` sin crear ficha; DNI inválido `DOCUMENTO_INVALIDO`; evento `CUENTA_CREADA`
- [ ] T004 [US2] `CuentaAccesoService.darDeAlta` (D-002), `AltaPersonaRequest/Response`, `POST /api/auth/altas` y su regla `ADMIN` en `SecurityConfig`
- [ ] T005 [US2] `app`: permisos (`ENCARGADO`, `EMPLEADO`, `REPRESENTANTE` → `403`; sin token `401`) y punta a punta con la implementación real de `timetracking`: alta → inicio de sesión con la provisional → solo cambio de contraseña → cambio → fichar

## Phase 3: US3 - Retirar el registro con solicitud (P2)

- [ ] T006 [US3] Borrar el flujo de la 005: solicitudes, códigos de verificación, aprobación, caducidad, configuración `auth.registro.max-pendientes/caducidad-*`, rutas `/api/auth/registros`, tests asociados
- [ ] T007 [US3] Migración `features/auth/src/main/resources/db/migration/V24__drop_solicitudes_registro.sql` y ajustar `RowLevelSecurityIT` de `auth` y `EsquemaCompletoRlsIT`
- [ ] T008 [US3] Quitar `REGISTRO_PENDIENTE` de `TipoAviso`, `Destinatarios`, mensajes y tests; migración `features/notifications/src/main/resources/db/migration/V25__remove_registro_pendiente.sql` (borra esos avisos y estrecha el `CHECK`)

## Phase 4: Pulido

- [ ] T009 [P] `PasswordEncoderTest`: un hash en el formato de la herramienta `argon2` (vector de referencia) se verifica; procedimiento de recuperación en README y `docs/DESPLIEGUE.md`
- [ ] T010 [P] README (estado, rutas, primer administrador, alta del personal), `docs/DESPLIEGUE.md`, `CHANGELOG.md` (sin publicar), nota en la spec 005, `.env.example`
- [ ] T011 Regenerar `docs/openapi.json` y build completo en verde con el perfil `prod`
