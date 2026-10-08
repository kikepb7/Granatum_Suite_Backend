# Feature Specification: Registro del personal

**Feature Branch**: `backlog-feature`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Registro de personal con correo y contraseña para Granatum, empresa privada. Cada trabajador (incluido el jefe, que es ADMIN) se registra él mismo con su correo, una contraseña que elige, su nombre y su DNI/NIE. El registro no da acceso por sí solo: queda pendiente hasta que un ADMIN lo aprueba, eligiendo el rol (ADMIN, ENCARGADO, EMPLEADO, REPRESENTANTE) y vinculándolo con la ficha de personal existente que tenga ese DNI o creando la ficha (puesto, tipo de contrato, fecha de alta). Para que el ADMIN sepa que aprueba a quien dice ser, al registrarse la persona recibe un código de verificación corto que debe decirle al ADMIN en persona, y aprobar exige ese código. El primer ADMIN de una instalación nueva se registra con un código de arranque secreto configurado por variable de entorno, que solo sirve mientras no exista ningún ADMIN, y entra directamente. El registro no revela si un correo ya tiene cuenta o solicitud (misma respuesta y mismo tiempo). Las solicitudes pendientes caducan; las rechazadas, caducadas o aprobadas no conservan datos personales que ya no hacen falta. El ADMIN puede listar las pendientes y rechazarlas. Sigue existiendo el alta por un ADMIN con contraseña temporal. Fuera de alcance: verificación por correo electrónico, recuperación por correo, doble factor, registro con proveedores externos. Revierte la decisión de la feature 002 de no tener registro, a petición del responsable del producto."

> **Sustituida en parte por la feature 009** ([`specs/009-owner-onboarding/`](../009-owner-onboarding/spec.md)):
> solo el propietario se registra (US1 sigue vigente); el registro de cada
> persona con aprobación (US2 a US5) se retiró, y el personal lo da de alta un
> `ADMIN` con credenciales provisionales.

## Contexto

La feature 002 decidió que nadie se diera de alta por su cuenta: un `ADMIN`
creaba cada acceso con una contraseña temporal. El responsable del producto ha
pedido lo contrario —que cada persona, incluido el propio jefe, se registre con
su correo y la contraseña que elija— y esta feature lo hace **sin convertir el
alta en una puerta abierta**: Granatum es la aplicación interna de una empresa
privada, así que registrarse no da acceso a nada hasta que un `ADMIN` lo
aprueba.

También resuelve el bloqueo que dejó la 002: **hoy no hay forma de crear el
primer `ADMIN` en producción**.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El primer administrador de una instalación nueva (Priority: P1)

Quien pone en marcha Granatum por primera vez —el jefe— se registra con su
correo, su contraseña, su nombre, su DNI y el código de arranque que se fijó al
desplegar. Entra directamente como `ADMIN`, con su ficha de personal creada.

**Why this priority**: Sin esto no se puede usar la aplicación en producción:
todas las demás altas dependen de que exista un `ADMIN`. Es el bloqueo que dejó
abierto la feature 002.

**Independent Test**: Con una base de datos sin cuentas y el código de arranque
configurado, registrarse con ese código e iniciar sesión con la contraseña
elegida; el token lleva el rol `ADMIN`.

**Acceptance Scenarios**:

1. **Given** una instalación sin ningún `ADMIN` y un código de arranque configurado, **When** alguien se registra aportando ese código, **Then** queda creada su cuenta con rol `ADMIN` y su ficha de personal, y puede iniciar sesión con la contraseña que eligió sin tener que cambiarla.
2. **Given** que ya existe un `ADMIN`, **When** alguien se registra aportando el código de arranque, aunque sea el correcto, **Then** no obtiene ningún acceso: el código ha dejado de servir.
3. **Given** una instalación sin `ADMIN`, **When** alguien aporta un código de arranque incorrecto, **Then** se rechaza indicando que el código no es válido, y no se crea nada.
4. **Given** una instalación sin código de arranque configurado, **When** alguien intenta registrarse aportando un código, **Then** se rechaza igual que con un código incorrecto.
5. **Given** una persona de la ficha de personal que ya existe con ese DNI, **When** el primer `ADMIN` se registra, **Then** su cuenta se vincula a esa ficha en vez de crear otra.

---

### User Story 2 - Una persona se registra y un ADMIN la aprueba (Priority: P1)

Una persona de la plantilla se registra con su correo, la contraseña que elige,
su nombre y su DNI. Recibe un código de verificación de pocos caracteres. Se lo
dice al `ADMIN` en persona; el `ADMIN` ve su solicitud, la aprueba con ese
código, le asigna un rol y la vincula a su ficha de personal o crea la ficha. A
partir de ahí la persona entra con su contraseña.

**Why this priority**: Es el encargo: que cada trabajador se dé de alta él
mismo. El código evita que el `ADMIN` apruebe a un impostor que se registró con
el nombre y el correo de otra persona: solo quien hizo el registro lo conoce.

**Independent Test**: Registrarse, comprobar que con esa contraseña aún no se
puede entrar, aprobar como `ADMIN` con el código e iniciar sesión.

**Acceptance Scenarios**:

1. **Given** una persona sin cuenta, **When** se registra con datos válidos, **Then** recibe la confirmación de que su solicitud está pendiente de aprobación y un código de verificación, y **no** puede iniciar sesión todavía.
2. **Given** una solicitud pendiente, **When** el `ADMIN` la aprueba con el código correcto y un rol, y existe una ficha de personal con ese DNI, **Then** se crea la cuenta vinculada a esa ficha, con la contraseña que eligió la persona y sin cambio obligatorio.
3. **Given** una solicitud pendiente cuyo DNI no está en la ficha de personal, **When** el `ADMIN` la aprueba aportando puesto, tipo de contrato y fecha de alta, **Then** se crean la ficha y la cuenta, en una sola operación.
4. **Given** una solicitud pendiente cuyo DNI no está en la ficha de personal, **When** el `ADMIN` la aprueba sin esos datos, **Then** se rechaza indicando que faltan, y la solicitud sigue pendiente.
5. **Given** una solicitud pendiente, **When** el `ADMIN` la aprueba con un código incorrecto, **Then** se rechaza y no se crea nada; tras 5 códigos incorrectos la solicitud queda anulada.
6. **Given** una solicitud aprobada, **When** la persona inicia sesión con su correo y su contraseña, **Then** entra con el rol asignado.
7. **Given** una ficha de personal que ya tiene cuenta, **When** se aprueba otra solicitud con ese DNI, **Then** se rechaza: una persona no puede tener dos cuentas.
8. **Given** una persona que no es `ADMIN`, **When** intenta listar, aprobar o rechazar solicitudes, **Then** se le deniega.

---

### User Story 3 - El registro no delata a nadie (Priority: P1)

Quien prueba correos en el formulario de registro no puede saber si una
dirección ya tiene cuenta o una solicitud pendiente.

**Why this priority**: El registro es la única ruta pública que recibe un correo
y responde algo distinto de "credenciales inválidas". Si delatara qué correos
existen, regalaría la lista de la plantilla y medio ataque de fuerza bruta.

**Independent Test**: Registrar un correo nuevo y uno que ya tiene cuenta, y
comparar respuestas y tiempos.

**Acceptance Scenarios**:

1. **Given** un correo que ya tiene cuenta, **When** alguien se registra con él, **Then** recibe exactamente la misma respuesta que con un correo nuevo —incluido un código de verificación de la misma forma— y no se crea ninguna solicitud que pueda aprobarse.
2. **Given** un correo con una solicitud pendiente, **When** alguien se registra otra vez con él, **Then** recibe la misma respuesta y se crea otra solicitud independiente; el `ADMIN` ve ambas y solo puede aprobar la de quien le dé su código.
3. **Given** las dos situaciones anteriores, **When** se mide el tiempo de respuesta, **Then** no se distingue del de un registro nuevo.

---

### User Story 4 - El ADMIN gestiona las solicitudes (Priority: P2)

El `ADMIN` ve las solicitudes pendientes —nombre, correo, DNI, cuándo se
hicieron y si ya hay una ficha de personal con ese DNI— y rechaza las que no
reconoce.

**Why this priority**: Sin lista no hay aprobación posible; rechazar es lo que
evita que se acumulen solicitudes ajenas.

**Independent Test**: Crear dos solicitudes, listarlas, rechazar una y comprobar
que desaparece de la lista y que su contraseña no sirve para entrar.

**Acceptance Scenarios**:

1. **Given** varias solicitudes en distintos estados, **When** el `ADMIN` lista las pendientes, **Then** solo ve las pendientes, de la más antigua a la más reciente, y en ninguna aparece la contraseña ni el código.
2. **Given** una solicitud pendiente, **When** el `ADMIN` la rechaza, **Then** pasa a rechazada y deja de contener los datos personales de quien la hizo.
3. **Given** una solicitud ya resuelta, **When** se intenta aprobarla o rechazarla otra vez, **Then** se rechaza indicando que ya no está pendiente.
4. **Given** dos solicitudes pendientes con el mismo correo o el mismo DNI, **When** el `ADMIN` aprueba una, **Then** las demás quedan anuladas automáticamente.

---

### User Story 5 - Lo que no hace falta no se guarda (Priority: P2)

Una solicitud que no llega a nada no deja datos personales a la espera para
siempre.

**Why this priority**: Una solicitud contiene nombre, DNI, correo y la huella de
una contraseña. Conservarlos sin finalidad incumple la limitación del plazo de
conservación del RGPD.

**Independent Test**: Dejar caducar una solicitud y comprobar que ya no contiene
datos personales.

**Acceptance Scenarios**:

1. **Given** una solicitud pendiente durante más del plazo de caducidad, **When** pasa el proceso periódico, **Then** queda caducada y sin datos personales.
2. **Given** una solicitud aprobada, **When** se consulta, **Then** ya no contiene los datos personales de la solicitud: viven en la cuenta y en la ficha.
3. **Given** cualquier solicitud resuelta, **When** se consulta, **Then** sigue constando qué pasó, cuándo y quién la resolvió.

---

### Edge Cases

- **Contraseña débil al registrarse**: se rechaza con los mismos requisitos que
  el cambio de contraseña de la feature 002, y no revela nada sobre el correo.
- **DNI o NIE con letra de control incorrecta**: se rechaza como dato inválido.
- **Avalancha de registros**: hay un máximo de solicitudes pendientes a la vez;
  superado, el registro responde que no se admiten más por ahora, sin crear nada.
  Lo que limita el ritmo desde un mismo origen es la feature de endurecimiento.
- **Dos personas aprueban la misma solicitud a la vez**: una gana, la otra
  recibe que ya no está pendiente; nunca salen dos cuentas.
- **Aprobar una solicitud cuyo correo ya tiene cuenta** (porque un `ADMIN` la
  creó por la vía de la contraseña temporal entretanto): se rechaza y la
  solicitud queda anulada.
- **La ficha de personal con ese DNI está dada de baja**: se puede vincular,
  pero la persona no podrá entrar hasta que se reactive (regla de la 002).
- **El primer `ADMIN` olvida su contraseña**: no hay recuperación por correo. Si
  ya hay otro `ADMIN`, la restablece; si no, el código de arranque no sirve
  porque ya existe un `ADMIN` — el procedimiento de emergencia es operativo y se
  documenta.
- **Registro del jefe sin código de arranque cuando aún no hay `ADMIN`**: queda
  como solicitud pendiente normal, que nadie puede aprobar todavía; registrarse
  después con el código la deja anulada.

## Requirements *(mandatory)*

### Functional Requirements

**Registro**

- **FR-001**: El sistema MUST permitir a cualquiera, sin sesión, registrarse aportando correo, contraseña, nombre y documento de identidad (DNI o NIE).
- **FR-002**: El sistema MUST validar la contraseña con la misma política que el cambio de contraseña de la feature 002, y el documento con su letra de control.
- **FR-003**: Un registro MUST NOT dar acceso por sí mismo: crea una solicitud pendiente de aprobación.
- **FR-004**: El sistema MUST entregar a quien se registra un código de verificación de 8 caracteres, legible en voz alta (sin caracteres confundibles), que solo se muestra en esa respuesta y no se puede consultar después.
- **FR-005**: El sistema MUST NOT guardar en claro ni la contraseña ni el código de verificación.
- **FR-006**: La respuesta a un registro MUST ser idéntica —en forma, código de estado y tiempo— tanto si el correo es nuevo como si ya tiene cuenta o solicitud pendiente.
- **FR-007**: Si el correo ya tiene cuenta, el sistema MUST NOT crear una solicitud aprobable.
- **FR-008**: El sistema MUST limitar el número de solicitudes pendientes simultáneas (50 por defecto, configurable); superado el límite, MUST rechazar registros nuevos sin crear nada.

**Primer administrador**

- **FR-009**: El sistema MUST admitir un código de arranque secreto, configurado al desplegar y nunca escrito en el repositorio.
- **FR-010**: Un registro con el código de arranque correcto, mientras no exista ninguna cuenta `ADMIN`, MUST crear de inmediato la cuenta `ADMIN` con la contraseña elegida y sin cambio obligatorio, vinculada a la ficha de personal con ese DNI o a una ficha nueva.
- **FR-011**: La ficha creada para el primer `ADMIN` MUST llevar valores por defecto documentados (puesto, tipo de contrato y fecha de alta del día) que el `ADMIN` puede corregir después.
- **FR-012**: El código de arranque MUST dejar de servir en cuanto existe una cuenta `ADMIN`, por cualquier vía.
- **FR-013**: Un código de arranque incorrecto, o aportado cuando no hay ninguno configurado o ya existe un `ADMIN`, MUST rechazarse sin crear nada.
- **FR-014**: Al crearse el primer `ADMIN`, las solicitudes pendientes con su mismo correo MUST quedar anuladas.

**Aprobación y rechazo**

- **FR-015**: Solo un `ADMIN` MUST poder listar, aprobar y rechazar solicitudes.
- **FR-016**: La lista MUST mostrar de cada solicitud pendiente: identificador, nombre, correo, documento, fecha, y si ya existe una ficha de personal con ese documento; MUST NOT mostrar la contraseña ni el código.
- **FR-017**: Aprobar MUST exigir el código de verificación de la solicitud y el rol que se asigna.
- **FR-018**: Tras 5 códigos incorrectos, la solicitud MUST quedar anulada.
- **FR-019**: Si existe una ficha de personal con el documento de la solicitud, aprobar MUST vincular la cuenta a esa ficha; si no existe, MUST exigir puesto, tipo de contrato y fecha de alta, y crear la ficha y la cuenta en una sola operación (o ninguna de las dos).
- **FR-020**: La cuenta creada al aprobar MUST tener la contraseña que eligió la persona al registrarse y MUST NOT requerir cambio.
- **FR-021**: Aprobar MUST rechazarse si la ficha ya tiene cuenta o el correo ya está en uso; en el segundo caso la solicitud queda anulada.
- **FR-022**: Al aprobar una solicitud, las demás pendientes con el mismo correo o el mismo documento MUST quedar anuladas.
- **FR-023**: Una solicitud que no está pendiente MUST NOT poder aprobarse ni rechazarse.
- **FR-024**: Dos aprobaciones simultáneas de la misma solicitud MUST producir una sola cuenta.

**Conservación**

- **FR-025**: Una solicitud pendiente MUST caducar a los 7 días (configurable), mediante un proceso periódico.
- **FR-026**: Al pasar a aprobada, rechazada, caducada o anulada, la solicitud MUST perder correo, nombre, documento, contraseña y código, y conservar solo su estado, fechas y quién la resolvió.
- **FR-027**: Ninguna solicitud MUST borrarse: la fila queda como constancia de lo ocurrido.

**Trazabilidad y convivencia**

- **FR-028**: El sistema MUST registrar como evento de seguridad cada registro (nuevo o duplicado, sin distinguirlo en la respuesta), la creación del primer `ADMIN`, cada aprobación, rechazo, anulación por códigos incorrectos y caducidad, sin datos personales.
- **FR-029**: Los registros de la aplicación MUST NOT contener correo, nombre, documento, contraseña ni código.
- **FR-030**: El alta por un `ADMIN` con contraseña temporal de la feature 002 MUST seguir funcionando igual.
- **FR-031**: Iniciar sesión con las credenciales de una solicitud no aprobada MUST responder igual que unas credenciales inválidas.

### Key Entities

- **SolicitudRegistro**: lo que deja una persona al registrarse. Estado
  (`PENDIENTE`, `APROBADA`, `RECHAZADA`, `CADUCADA`, `ANULADA`), correo, nombre,
  documento, huella de la contraseña y del código, intentos de código fallidos,
  cuándo se creó y se resolvió, quién la resolvió y qué cuenta salió de ella.
  Los datos personales solo existen mientras está pendiente.
- **CuentaAcceso** (feature 002): la que nace de una solicitud aprobada o del
  primer `ADMIN`.
- **Empleado** (feature 001): la ficha de personal a la que se vincula, o que se
  crea al aprobar.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: En una instalación nueva, el jefe pasa de no tener nada a tener sesión de `ADMIN` en menos de 2 minutos, sin acceso a la base de datos.
- **SC-002**: Una persona de la plantilla se registra en menos de 1 minuto, y desde que el `ADMIN` tiene su código la aprobación lleva menos de 1 minuto.
- **SC-003**: El 0% de las solicitudes no aprobadas da acceso a nada.
- **SC-004**: Comparando 50 registros con correos existentes y 50 con correos nuevos, las respuestas son idénticas y la diferencia de tiempo medio es inferior al 10%.
- **SC-005**: El 100% de las solicitudes resueltas o caducadas carece de datos personales.
- **SC-006**: Con 100 intentos de código de verificación al azar, no se aprueba ninguna solicitud.
- **SC-007**: Ninguna operación de solicitudes es posible para un rol distinto de `ADMIN` (100% denegadas).

## Assumptions

- El canal de confianza entre la persona y el `ADMIN` es el trato en persona
  dentro de la empresa: no hay correo electrónico saliente.
- Quien conoce el código de arranque es quien despliega, y se lo da al jefe; se
  puede retirar de la configuración tras crear el primer `ADMIN`.
- Valores por defecto de la ficha del primer `ADMIN`: puesto "Dirección",
  jornada completa, alta en la fecha del registro.
- 7 días de caducidad bastan para que una persona se lo comunique al `ADMIN`.
- El límite de ritmo por origen pertenece a la feature de endurecimiento (006).

## Dependencies

- Feature 001: ficha de personal (`empleados`). Esta feature necesita buscar una
  ficha por documento y crear una nueva, a través de un contrato explícito, sin
  depender del módulo de fichaje (principio I).
- Feature 002: cuentas, política de contraseñas, verificación acotada del hash y
  eventos de seguridad.

## Out of Scope

- Verificación del correo electrónico y cualquier envío de correo.
- Recuperación de contraseña por correo.
- Doble factor de autenticación.
- Registro con proveedores externos.
- Que una persona elimine su solicitud o su cuenta por sí misma.
- Limitación por dirección de origen (feature 006).
