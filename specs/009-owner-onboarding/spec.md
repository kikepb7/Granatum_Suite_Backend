# Feature Specification: Alta del personal por el propietario

**Feature Branch**: `feature/009-alta-por-propietario`

**Created**: 2026-10-09

**Status**: Draft

**Input**: User description: "Que el registro sea únicamente capaz el propietario del negocio. Cualquier persona empleada que quiera acceder a la aplicación será dada de alta por el propio propietario (ADMIN). Una vez dado de alta, le pasará las credenciales para que así pueda acceder a la app."

## Contexto

La feature 005 permitió que cada persona de la plantilla se registrara sola y
quedara pendiente de que un `ADMIN` la aprobara con un código de verificación.
El responsable del producto lo reduce: **solo el propietario se registra**, una
vez, al poner en marcha la instalación. A cualquier otra persona la da de alta
el propietario (o otro `ADMIN`) y le entrega unas credenciales provisionales.

Esto **sustituye** el registro con solicitud de la 005 (US2 a US5): las
solicitudes, sus códigos, su aprobación y su caducidad desaparecen. Se mantiene
el registro del primer `ADMIN` con el código de arranque (US1 de la 005).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Solo el propietario se registra (Priority: P1)

El propietario se registra con su correo, su contraseña, su nombre, su DNI y el
código de arranque fijado al desplegar, y entra como `ADMIN`. Nadie más puede
registrarse: sin código, o cuando ya hay un `ADMIN`, el registro se rechaza.

**Independent Test**: Registrarse sin código de arranque y comprobar que se
rechaza sin crear nada; registrarse con él en una instalación sin `ADMIN` y
entrar.

**Acceptance Scenarios**:

1. **Given** una instalación sin `ADMIN`, **When** el propietario se registra con el código de arranque, **Then** queda creada su cuenta `ADMIN` y su ficha, y puede iniciar sesión (como en la 005, US1).
2. **Given** cualquier instalación, **When** alguien intenta registrarse sin código de arranque, **Then** se rechaza indicando que falta el código, y no se crea ni guarda nada.
3. **Given** una instalación que ya tiene `ADMIN`, **When** alguien se registra, aunque traiga el código correcto, **Then** se rechaza.

---

### User Story 2 - El propietario da de alta a una persona (Priority: P1)

El `ADMIN` da de alta a una persona de una vez: su ficha de personal (nombre,
DNI, puesto, contrato, fecha de alta) y su acceso (correo y rol). Recibe una
contraseña provisional, que solo ve en ese momento, y se la entrega a la
persona. Al entrar por primera vez, la persona tiene que cambiarla.

**Independent Test**: Dar de alta a una persona, iniciar sesión con la
contraseña provisional y comprobar que solo puede cambiarla; cambiarla y fichar.

**Acceptance Scenarios**:

1. **Given** un `ADMIN`, **When** da de alta a una persona nueva, **Then** se crean su ficha y su cuenta en una sola operación y recibe una contraseña provisional.
2. **Given** esa contraseña provisional, **When** la persona inicia sesión, **Then** entra, pero solo puede cambiar la contraseña hasta que lo haga.
3. **Given** que ya existe una ficha con ese DNI y sin cuenta, **When** el `ADMIN` da de alta a esa persona, **Then** la cuenta se vincula a la ficha existente en lugar de crear otra.
4. **Given** que la ficha con ese DNI ya tiene cuenta, **When** se intenta el alta, **Then** se rechaza y no se crea nada.
5. **Given** un correo que ya tiene cuenta, **When** se intenta el alta, **Then** se rechaza y no se crea nada, tampoco la ficha.
6. **Given** un DNI con la letra de control incorrecta, **When** se intenta el alta, **Then** se rechaza.
7. **Given** cualquier rol que no sea `ADMIN`, **When** intenta dar de alta, **Then** se le deniega.

---

### User Story 3 - Lo anterior desaparece sin dejar datos (Priority: P2)

Las solicitudes de registro pendientes que hubiera de la 005 desaparecen con sus
datos personales, y también los avisos de "registro pendiente".

**Acceptance Scenarios**:

1. **Given** una base de datos con solicitudes de registro de la 005, **When** se actualiza a esta versión, **Then** las solicitudes y sus datos dejan de existir.
2. **Given** avisos de registro pendiente, **When** se actualiza, **Then** desaparecen.
3. **Given** la API, **When** se consultan las rutas de gestión de solicitudes, **Then** ya no existen.

### Edge Cases

- **El propietario olvida su contraseña y es el único `ADMIN`**: el
  procedimiento de emergencia de la 005 (D-010) generaba el hash registrando
  una solicitud; sin solicitudes, el hash se obtiene dando de alta una persona
  ficticia o, mejor, aprobando pronto un segundo `ADMIN`. Se documenta.
- **Se pierde la contraseña provisional antes de entregarla**: el `ADMIN`
  restablece la contraseña de esa persona (ya existe) y obtiene otra.
- **El alta falla a mitad**: ficha y cuenta se crean juntas o ninguna.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El registro público MUST exigir el código de arranque; sin él, MUST rechazarse sin guardar nada.
- **FR-002**: El registro con el código de arranque MUST seguir funcionando solo mientras no exista ningún `ADMIN` (feature 005, FR-009 a FR-013).
- **FR-003**: El sistema MUST NOT guardar solicitudes de registro ni ofrecer rutas para listarlas, aprobarlas o rechazarlas.
- **FR-004**: Un `ADMIN` MUST poder dar de alta a una persona en una sola operación: ficha de personal (nombre, documento, puesto, tipo de contrato, fecha de alta) y cuenta (correo y rol).
- **FR-005**: El alta MUST devolver una contraseña provisional generada, que cumpla la política de contraseñas y que no se pueda volver a consultar.
- **FR-006**: La cuenta creada MUST exigir el cambio de contraseña en el primer acceso.
- **FR-007**: Si existe una ficha con ese documento y sin cuenta, el alta MUST vincularse a ella; si ya tiene cuenta, MUST rechazarse.
- **FR-008**: Un correo ya en uso o un documento no válido MUST rechazar el alta sin crear nada.
- **FR-009**: Ficha y cuenta MUST crearse en la misma transacción.
- **FR-010**: Solo `ADMIN` MUST poder dar de alta.
- **FR-011**: La actualización MUST eliminar las solicitudes de registro existentes y los avisos de registro pendiente.
- **FR-012**: Las rutas de alta por partes (crear ficha en `/api/empleados`, dar acceso en `/api/auth/cuentas`) MUST seguir funcionando.
- **FR-013**: Ni la contraseña provisional ni los datos de la ficha MUST aparecer en los logs.

### Key Entities

- **CuentaAcceso** y **Empleado** (features 001 y 002), sin cambios.
- **SolicitudRegistro** (feature 005): deja de existir.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100% de los intentos de registro sin código de arranque se rechaza sin guardar nada.
- **SC-002**: Dar de alta a una persona lleva una sola petición y menos de 1 minuto.
- **SC-003**: Tras la actualización no queda ninguna fila de solicitudes de registro ni ningún aviso de registro pendiente.
- **SC-004**: El 100% de los intentos de alta por un rol distinto de `ADMIN` se deniega.

## Assumptions

- El canal para entregar la contraseña provisional es el trato en persona: no hay
  correo saliente.
- Solo hay un propietario; si hace falta otro `ADMIN`, el propietario lo da de
  alta con rol `ADMIN`.

## Dependencies

- Feature 001 (fichas), 002 (cuentas y cambio obligatorio), 005 (registro del
  primer `ADMIN`), 008 (avisos).

## Out of Scope

- Envío de credenciales por correo o SMS.
- Que una persona se registre por su cuenta.
