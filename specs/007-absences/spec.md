# Feature Specification: Ausencias y vacaciones

**Feature Branch**: `backlog-feature`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Ausencias y vacaciones para Granatum, aplazadas desde la feature 001. Una persona de la plantilla solicita vacaciones o un permiso para un rango de días; un ENCARGADO o un ADMIN lo aprueba o lo rechaza con motivo. Las bajas médicas las registra un ENCARGADO o un ADMIN, sin datos de salud. Cada persona ve sus ausencias y su saldo de vacaciones del año; ENCARGADO y ADMIN ven las de todos en un calendario. Dos ausencias de una misma persona no pueden solaparse. El saldo anual por defecto son 30 días naturales (art. 38 ET) y el ADMIN puede ajustarlo por persona y año. Nada se borra: una solicitud se cancela o se rechaza. Módulo nuevo features/absences que solo depende de common. Fuera de alcance: cuadrantes y turnos, cálculo de nómina, festivos locales, notificaciones (feature aparte)."

## Contexto

La feature 001 dejó fuera ausencias, vacaciones y permisos. Hoy una persona que
está de vacaciones simplemente no ficha, y nada en el sistema distingue eso de
un olvido. Esta feature lo registra: quién falta, cuándo, por qué tipo de motivo
y quién lo autorizó.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Pedir vacaciones y que me las aprueben (Priority: P1)

Una persona pide vacaciones del 3 al 14 de agosto. Ve que le quedan días
suficientes, la solicitud queda pendiente, y un `ENCARGADO` la aprueba. A partir
de ahí cuenta en su saldo y aparece en el calendario.

**Why this priority**: Es el uso más frecuente y la razón de la feature.

**Independent Test**: Solicitar, aprobar como `ENCARGADO` y comprobar el saldo.

**Acceptance Scenarios**:

1. **Given** una persona con 30 días de derecho y ninguno usado, **When** pide 12 días de vacaciones, **Then** queda una solicitud pendiente y su saldo muestra 12 días pendientes y 18 disponibles.
2. **Given** una solicitud pendiente, **When** un `ENCARGADO` la aprueba, **Then** pasa a aprobada, consta quién y cuándo, y los 12 días pasan a disfrutados o programados.
3. **Given** una solicitud pendiente, **When** se rechaza con un motivo, **Then** pasa a rechazada con ese motivo y los días vuelven al saldo.
4. **Given** una persona con 5 días disponibles, **When** pide 6, **Then** se rechaza por saldo insuficiente.
5. **Given** una solicitud aprobada, **When** la persona intenta aprobarla, rechazarla o modificarla, **Then** no puede: solo `ENCARGADO` o `ADMIN` resuelven.

---

### User Story 2 - Sin solapamientos (Priority: P1)

Una persona no puede tener dos ausencias que se pisen, ni siquiera si una está
pendiente.

**Why this priority**: Un solapamiento cuenta dos veces los mismos días y deja
el calendario sin sentido.

**Independent Test**: Pedir dos rangos que se solapan.

**Acceptance Scenarios**:

1. **Given** unas vacaciones aprobadas del 3 al 14, **When** la misma persona pide un permiso del 10 al 11, **Then** se rechaza por solapamiento.
2. **Given** una solicitud rechazada o cancelada del 3 al 14, **When** pide de nuevo esos días, **Then** se admite.
3. **Given** dos peticiones simultáneas que se solapan, **When** llegan a la vez, **Then** solo una se admite.

---

### User Story 3 - Bajas y permisos (Priority: P2)

Un `ENCARGADO` registra la baja médica de una persona, sin ningún dato de salud,
y queda aprobada directamente. Una persona pide un permiso retribuido indicando
su causa (matrimonio, nacimiento, fallecimiento de un familiar, mudanza, deber
inexcusable, otro).

**Why this priority**: Son menos frecuentes, pero sin ellos el calendario miente.

**Independent Test**: Registrar una baja y comprobar que no admite comentario ni
consume vacaciones.

**Acceptance Scenarios**:

1. **Given** un `ENCARGADO`, **When** registra una baja médica de una persona, **Then** queda aprobada a su nombre, sin consumir vacaciones.
2. **Given** una baja médica, **When** se intenta añadir un comentario, **Then** se rechaza: es un dato de salud que no se guarda.
3. **Given** una persona, **When** intenta registrar una baja médica para sí misma, **Then** se le deniega.
4. **Given** una persona, **When** pide un permiso retribuido con su causa, **Then** queda pendiente y no consume vacaciones.
5. **Given** una baja médica abierta (sin fecha de fin), **When** se conoce el alta, **Then** el `ENCARGADO` fija la fecha de fin.

---

### User Story 4 - Cancelar (Priority: P2)

La persona cancela una solicitud pendiente, o unas vacaciones aprobadas que aún
no han empezado. Lo ya disfrutado no se cancela.

**Independent Test**: Cancelar una pendiente y una aprobada futura; intentar
cancelar una pasada.

**Acceptance Scenarios**:

1. **Given** una solicitud pendiente propia, **When** la cancela, **Then** pasa a cancelada y sus días vuelven al saldo.
2. **Given** unas vacaciones aprobadas que empiezan mañana, **When** las cancela, **Then** pasan a canceladas.
3. **Given** unas vacaciones aprobadas que ya han empezado, **When** intenta cancelarlas, **Then** se rechaza.
4. **Given** la solicitud de otra persona, **When** una persona intenta cancelarla, **Then** se le deniega.

---

### User Story 5 - Calendario y saldo (Priority: P2)

Cada persona ve sus ausencias y su saldo del año. `ENCARGADO` y `ADMIN` ven las
de toda la plantilla en un rango de fechas, y el `ADMIN` ajusta el derecho anual
de una persona (por ejemplo, alguien que entró en junio).

**Acceptance Scenarios**:

1. **Given** varias ausencias de varias personas, **When** un `ENCARGADO` consulta del 1 al 31 de agosto, **Then** ve las pendientes y aprobadas que tocan ese rango.
2. **Given** una persona, **When** consulta ausencias, **Then** solo ve las suyas, aunque pida las de otra.
3. **Given** el `ADMIN`, **When** fija 17 días de derecho para una persona en 2026, **Then** su saldo de 2026 parte de 17.
4. **Given** unas vacaciones del 28 de diciembre al 4 de enero, **When** se calcula el saldo, **Then** 4 días cuentan en un año y 4 en el siguiente.
5. **Given** un `REPRESENTANTE`, **When** intenta consultar ausencias, **Then** se le deniega.

### Edge Cases

- **Fecha de fin anterior a la de inicio**: se rechaza.
- **Vacaciones en el pasado** pedidas por la persona: se rechazan; un
  `ENCARGADO` o `ADMIN` sí puede registrar unas pasadas (regularización).
- **Una persona inexistente o dada de baja**: no se le registran ausencias
  nuevas.
- **Rango enorme** (más de un año): se rechaza; una baja larga se registra
  abierta y se cierra con el alta.
- **Ausencias de quien aprueba**: un `ENCARGADO` no aprueba sus propias
  solicitudes; las aprueba otro `ENCARGADO` o un `ADMIN`.

## Requirements *(mandatory)*

### Functional Requirements

**Solicitud y resolución**

- **FR-001**: Una persona `EMPLEADO`, `ENCARGADO` o `ADMIN` MUST poder solicitar para sí misma vacaciones o un permiso retribuido para un rango de fechas (ambos días incluidos), con un comentario opcional.
- **FR-002**: Un permiso retribuido MUST indicar su causa: `MATRIMONIO`, `NACIMIENTO`, `FALLECIMIENTO_FAMILIAR`, `ENFERMEDAD_FAMILIAR`, `MUDANZA`, `DEBER_INEXCUSABLE` u `OTRO`.
- **FR-003**: Solo `ENCARGADO` o `ADMIN` MUST poder aprobar o rechazar, y nunca una solicitud propia.
- **FR-004**: Rechazar MUST exigir un motivo.
- **FR-005**: Una solicitud resuelta MUST NOT cambiar de estado salvo por cancelación según FR-012.

**Bajas médicas**

- **FR-006**: Solo `ENCARGADO` o `ADMIN` MUST poder registrar una baja médica de otra persona; queda aprobada al registrarse.
- **FR-007**: Una baja médica MUST NOT admitir comentario ni causa: el sistema no guarda datos de salud.
- **FR-008**: Una baja médica MAY registrarse sin fecha de fin; `ENCARGADO` o `ADMIN` MUST poder fijarla después, una sola vez.

**Reglas**

- **FR-009**: Dos ausencias no canceladas ni rechazadas de una misma persona MUST NOT solaparse, tampoco bajo peticiones simultáneas.
- **FR-010**: La fecha de fin MUST NOT ser anterior a la de inicio, y un rango MUST NOT superar 366 días.
- **FR-011**: Una persona MUST NOT pedir vacaciones que empiecen antes de hoy; `ENCARGADO` y `ADMIN` MAY registrarlas en nombre de otra persona en cualquier fecha (quedan aprobadas).
- **FR-012**: La persona MUST poder cancelar una solicitud propia pendiente, o aprobada que todavía no ha empezado.
- **FR-013**: No se MUST registrar ausencias nuevas a una persona inexistente o inactiva.

**Saldo**

- **FR-014**: El derecho anual de vacaciones MUST ser 30 días naturales por defecto (configurable), y el `ADMIN` MUST poder fijarlo por persona y año.
- **FR-015**: El saldo de un año MUST mostrar derecho, días aprobados, días pendientes y días disponibles, contando días naturales dentro de ese año.
- **FR-016**: Una solicitud de vacaciones MUST rechazarse si excede los días disponibles de cualquiera de los años que toca.
- **FR-017**: Permisos y bajas MUST NOT consumir vacaciones.

**Consulta y permisos**

- **FR-018**: Una persona MUST ver solo sus ausencias y su saldo; `ENCARGADO` y `ADMIN` MUST ver los de cualquiera, y consultar un rango de fechas de toda la plantilla.
- **FR-019**: `REPRESENTANTE` MUST NOT acceder a ausencias.
- **FR-020**: Ninguna ausencia MUST borrarse.
- **FR-021**: Los logs MUST NOT contener comentarios ni motivos.

### Key Entities

- **Ausencia**: persona, tipo (`VACACIONES`, `PERMISO`, `BAJA_MEDICA`), causa
  (solo permisos), desde, hasta (opcional solo en bajas), estado (`PENDIENTE`,
  `APROBADA`, `RECHAZADA`, `CANCELADA`), comentario, motivo de rechazo, quién la
  pidió o registró, quién la resolvió y cuándo, cuándo se canceló.
- **DerechoVacaciones**: persona, año, días.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Pedir unas vacaciones lleva menos de 1 minuto y aprobarlas menos de 30 segundos.
- **SC-002**: El 0% de las ausencias de una persona se solapan, también con 20 peticiones simultáneas.
- **SC-003**: El saldo coincide al día con el recuento manual en el 100% de los casos de prueba, incluido el cambio de año.
- **SC-004**: El 100% de los intentos de un `EMPLEADO` de ver o resolver ausencias ajenas se deniegan.
- **SC-005**: Ninguna baja médica guarda texto libre.

## Assumptions

- Días naturales, no laborables: es lo que fija el art. 38 ET como mínimo, y no
  hay calendario de festivos (fuera de alcance).
- El derecho de quien entra a mitad de año lo ajusta el `ADMIN` (no hay prorrateo
  automático, porque la fecha de alta pertenece a otra feature).
- Las vacaciones no disfrutadas no se arrastran al año siguiente.

## Dependencies

- Feature 001: la persona debe existir y estar activa (contrato `DirectorioEmpleados`).
- Feature 002: identidad y rol del token.

## Out of Scope

- Cuadrantes, turnos y festivos.
- Nómina y cálculo económico.
- Notificaciones (feature 008).
- Adjuntar justificantes.
