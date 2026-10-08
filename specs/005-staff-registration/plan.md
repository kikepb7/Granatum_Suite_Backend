# Implementation Plan: Registro del personal

**Branch**: `backlog-feature` | **Date**: 2026-10-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-staff-registration/spec.md`

## Summary

Cada persona se registra con correo, contraseña, nombre y DNI/NIE. El registro
crea una **solicitud pendiente** y devuelve un código de verificación de 8
caracteres que la persona le dice al `ADMIN`; el `ADMIN` aprueba con ese código,
elige el rol y la cuenta queda vinculada a la ficha de personal con ese DNI o a
una ficha nueva. El primer `ADMIN` entra directamente con un código de arranque
secreto que solo sirve mientras no haya ningún `ADMIN`.

**Enfoque técnico**: todo en `features/auth`, que sigue dependiendo solo de
`common`. La ficha de personal es de `timetracking`, así que `auth` la busca y la
crea a través de un **contrato nuevo en `common`**, `FichasPersonal`, que
implementa `timetracking` (principio I, el mismo patrón que `DirectorioEmpleados`).
Una tabla nueva, `solicitudes_registro` (V20, con RLS), y una migración que amplía
los tipos de evento de seguridad (V21). La contraseña se cifra con el
`VerificadorAcotado` de la 002 en **todos** los caminos del registro, también
cuando el correo ya existe, para que el tiempo no delate nada (D-003).

## Technical Context

**Language/Version**: Kotlin 2.2 sobre JVM 21

**Primary Dependencies**: Spring Boot 4 (Web, Data JPA, Validation, Security),
Argon2 a través del `PasswordEncoder` de la 002. **Ninguna dependencia nueva.**

**Storage**: PostgreSQL. Una tabla nueva (`solicitudes_registro`, V20) y la
ampliación del `CHECK` de `eventos_seguridad` (V21).

**Testing**: JUnit 5, MockK para las reglas puras, Testcontainers con Postgres
real. En `auth`, el doble `DirectorioEmpleadosDoble` se amplía para implementar
también `FichasPersonal`; en `app`, los tests de punta a punta usan la
implementación real de `timetracking`.

**Target Platform**: servidor Linux (contenedor), igual que el resto.

**Project Type**: servicio web (API REST).

**Performance Goals**: el registro cuesta lo que un hash Argon2 (~110 ms en la
máquina de referencia) más una escritura; el resto es despreciable.

**Constraints**: mismo tiempo de respuesta con correo nuevo o existente (SC-004,
diferencia < 10%); sin datos personales en logs; el código de arranque, sin valor
por defecto.

**Scale/Scope**: una plantilla de decenas de personas; un máximo de 50
solicitudes pendientes a la vez.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Cumplimiento |
|---|---|
| I. Features independientes | `auth` no importa nada de `timetracking`. Necesita dos cosas de la ficha de personal (buscar por documento y crear) y las obtiene de un contrato nuevo en `common`, `FichasPersonal`, que implementa `timetracking`. Igual que `DirectorioEmpleados`: el compilador sostiene la frontera. |
| II. Flyway | V20 y V21, nuevas y numeradas; ninguna migración aplicada se edita. V21 sustituye un `CHECK` con `DROP`/`ADD`, no edita V14. |
| III. Registro horario | No se toca. Crear una ficha al aprobar usa el mismo servicio que el alta manual de la 001. |
| IV. Roles | Solo `ADMIN` lista, aprueba y rechaza (una regla en `SecurityConfig`). El registro es público y se declara allí mismo. Los cuatro roles no cambian. |
| V. Tests | Unitarios de las reglas puras (código, caducidad), integración con Testcontainers, y tests de las invariantes: no acceso sin aprobación, solo `ADMIN`, sin datos personales en logs, RLS. |
| VI. Secretos y datos personales | `AUTH_CODIGO_ARRANQUE` es un secreto: variable de entorno **sin valor por defecto**; vacío desactiva el arranque. Contraseña y código solo como huella. Ningún log con correo, nombre, DNI, contraseña ni código; un test con el log en `DEBUG` lo comprueba. |
| VII. RLS | `solicitudes_registro` activa RLS en V20. `RowLevelSecurityIT` de `auth` y `EsquemaCompletoRlsIT` la añaden por nombre. |
| VIII. Contrato API | Todo bajo `/api/auth`, DTO separados, errores `{code, message}` desde el `AuthExceptionHandler`, validación con `@Valid`. |
| IX. Documentación | Spec, plan y tareas aquí; README (registro, primer `ADMIN`, variables) y ARCHITECTURE (contrato nuevo). |

**Resultado**: pasa sin excepciones. La única tensión es con una **decisión** de
la 002 (no tener registro), no con un principio; la spec la revierte de forma
explícita y la 002 queda como historial.

## Project Structure

### Documentation (this feature)

```text
specs/005-staff-registration/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/README.md
└── tasks.md
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/granatum/core/domain/contract/
└── FichasPersonal.kt                 # contrato nuevo: buscar por documento, crear

features/timetracking/src/main/kotlin/com/granatum/core/infrastructure/directorio/
└── FichasPersonalJpa.kt              # implementación del contrato

features/auth/src/main/
├── kotlin/com/granatum/core/
│   ├── api/controllers/RegistroController.kt          # POST /api/auth/registro
│   ├── api/controllers/SolicitudRegistroController.kt # /api/auth/registros (ADMIN)
│   ├── api/dto/RegistroDtos.kt
│   ├── domain/model/SolicitudRegistro.kt
│   ├── domain/type/EstadoSolicitudRegistro.kt
│   ├── domain/service/CodigoVerificacion.kt          # generar y comprobar, puro
│   ├── domain/exception/RegistroExceptions.kt
│   ├── infrastructure/database/entities/SolicitudRegistroEntity.kt
│   ├── infrastructure/database/repositories/SolicitudRegistroRepository.kt
│   ├── service/RegistroService.kt                    # registro y primer ADMIN
│   ├── service/AprobacionRegistroService.kt          # listar, aprobar, rechazar
│   └── scheduling/CaducidadSolicitudesJob.kt
└── resources/db/migration/
    ├── V20__create_solicitudes_registro_table.sql
    └── V21__add_registro_eventos_seguridad.sql

app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt  # dos reglas nuevas
```

**Structure Decision**: la feature vive en `features/auth`, porque lo que crea es
una credencial. No es un módulo nuevo: registrarse y entrar son dos caras de lo
mismo, y separarlos obligaría a compartir la tabla de cuentas entre módulos.

## Fases

1. **Fundación**: contrato `FichasPersonal` en `common` y su implementación en
   `timetracking`; V20 y V21; entidad, repositorio sin `delete`; doble ampliado;
   reglas de `SecurityConfig`; configuración (`auth.registro.*`).
2. **US1 – Primer `ADMIN`** (P1).
3. **US2 – Registro y aprobación** (P1).
4. **US3 – Sin enumeración** (P1): respuesta y tiempo iguales.
5. **US4 – Gestión de solicitudes** (P2): listar, rechazar, anulación de
   duplicadas.
6. **US5 – Conservación** (P2): caducidad y borrado de datos personales al
   resolver.
7. **Pulido**: tests de permisos en `app`, logs, RLS global, README,
   ARCHITECTURE, quickstart.

## Complexity Tracking

Sin violaciones de la constitución que justificar.
