# Feature Specification: Notificaciones

**Feature Branch**: `backlog-feature`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Notificaciones dentro de la aplicación, aplazadas desde la feature 001: avisar a la persona de que parece haber olvidado fichar la salida y de que su fichaje quedó incompleto; avisar a quien gestiona la plantilla de solicitudes de corrección y de ausencia pendientes, y al ADMIN de registros pendientes de aprobar; y avisar a la persona cuando se resuelven sus solicitudes. Cada persona ve solo las suyas, puede marcarlas como leídas, y las antiguas se eliminan solas. Sin correo ni notificaciones push: la aplicación las consulta. Módulo nuevo features/notifications que solo depende de common."

## Contexto

La feature 001 dejó fuera las notificaciones. Hoy nadie se entera de nada si no
lo busca: una persona no sabe que olvidó fichar la salida hasta que su fichaje
aparece incompleto, y un `ENCARGADO` no sabe que hay solicitudes esperándole si
no las consulta. Sin canal de correo ni push, la notificación vive en la propia
aplicación: la app la consulta y la muestra.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - "¿Has olvidado fichar la salida?" (Priority: P1)

Una persona lleva muchas horas con la jornada abierta. Recibe un aviso para que
fiche la salida o, si ya se fue, solicite la corrección. Si el fichaje acaba
marcado como incompleto, recibe otro aviso.

**Why this priority**: Es la notificación que pidió el producto y la que evita
correcciones: cuanto antes se entere, más fácil es fichar bien.

**Independent Test**: Dejar un fichaje abierto más del umbral, ejecutar la
comprobación y ver el aviso en la lista de la persona.

**Acceptance Scenarios**:

1. **Given** un fichaje abierto desde hace más de 10 horas, **When** pasa la comprobación periódica, **Then** su titular tiene un aviso de salida olvidada.
2. **Given** ese aviso ya creado, **When** la comprobación vuelve a pasar, **Then** no se crea otro igual.
3. **Given** un fichaje que se marca incompleto, **When** ocurre, **Then** su titular recibe el aviso de fichaje incompleto.
4. **Given** un fichaje abierto desde hace 2 horas, **When** pasa la comprobación, **Then** no hay aviso.

---

### User Story 2 - Lo pendiente llega a quien lo resuelve (Priority: P1)

Cuando alguien pide una corrección de fichaje o una ausencia, los `ENCARGADO` y
`ADMIN` reciben un aviso; quien la pidió no se avisa a sí mismo. Cuando alguien
se registra, los `ADMIN` reciben un aviso.

**Why this priority**: Sin esto, las solicitudes esperan a que alguien se acuerde
de mirar.

**Independent Test**: Pedir una ausencia y comprobar el aviso en la lista de un
`ENCARGADO`.

**Acceptance Scenarios**:

1. **Given** una persona que pide una ausencia, **When** se crea la solicitud, **Then** cada `ENCARGADO` y `ADMIN` tiene un aviso de ausencia pendiente.
2. **Given** un `ENCARGADO` que pide una ausencia para sí, **When** se crea, **Then** los demás `ENCARGADO` y `ADMIN` reciben aviso y él no.
3. **Given** una solicitud de corrección de fichaje, **When** se crea, **Then** `ENCARGADO` y `ADMIN` reciben aviso.
4. **Given** un registro de una persona nueva, **When** queda pendiente, **Then** cada `ADMIN` recibe aviso; un `ENCARGADO`, no.
5. **Given** cualquier aviso, **When** lo lee su destinatario, **Then** dice de qué se trata y a qué solicitud se refiere, sin nombres, motivos ni comentarios.

---

### User Story 3 - Saber cómo acabó lo que pedí (Priority: P2)

Cuando una solicitud de corrección o de ausencia se aprueba o se rechaza, quien
es su titular recibe el aviso.

**Acceptance Scenarios**:

1. **Given** una ausencia pendiente, **When** se aprueba, **Then** su titular recibe un aviso de ausencia aprobada.
2. **Given** una corrección pendiente, **When** se rechaza, **Then** el titular del fichaje recibe un aviso de corrección rechazada.

---

### User Story 4 - Mi bandeja (Priority: P2)

Cada persona lista sus avisos, de más reciente a más antiguo, sabe cuántos tiene
sin leer, marca uno o todos como leídos, y nunca ve los de otra persona.

**Acceptance Scenarios**:

1. **Given** varios avisos de una persona, **When** los lista, **Then** salen del más reciente al más antiguo, y puede pedir solo los no leídos.
2. **Given** un aviso, **When** lo marca como leído, **Then** deja de contar como no leído.
3. **Given** el aviso de otra persona, **When** intenta marcarlo, **Then** no existe para ella.
4. **Given** la cuenta de no leídos, **When** marca todos como leídos, **Then** la cuenta es cero.

---

### User Story 5 - Lo antiguo se va solo (Priority: P3)

Los avisos leídos de hace más de 90 días y cualquiera de hace más de 180 se
eliminan automáticamente.

**Acceptance Scenarios**:

1. **Given** un aviso leído hace 91 días, **When** pasa la limpieza, **Then** desaparece.
2. **Given** un aviso no leído de hace 100 días, **When** pasa la limpieza, **Then** sigue.
3. **Given** un aviso no leído de hace 181 días, **When** pasa la limpieza, **Then** desaparece.

### Edge Cases

- **Fallo al crear el aviso**: la operación que lo originó (pedir la ausencia,
  aprobar la corrección) no se deshace; el aviso se pierde y queda registrado
  que falló, sin datos personales.
- **La operación que lo originó se deshace**: no se crea aviso de algo que no
  llegó a ocurrir.
- **No hay ningún `ENCARGADO` ni `ADMIN`**: no se crea ningún aviso, y no es un
  error.
- **Un `REPRESENTANTE`**: no recibe avisos (no resuelve nada), pero puede
  consultar su bandeja vacía.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema MUST avisar al titular de un fichaje abierto desde hace más de 10 horas (configurable), una sola vez por fichaje.
- **FR-002**: El sistema MUST avisar al titular de un fichaje cuando se marca incompleto.
- **FR-003**: El sistema MUST avisar a cada `ENCARGADO` y `ADMIN`, salvo a quien la pidió, de cada solicitud de corrección de fichaje y de ausencia pendiente.
- **FR-004**: El sistema MUST avisar a cada `ADMIN` de cada registro pendiente de aprobar.
- **FR-005**: El sistema MUST avisar al titular cuando su solicitud de corrección o de ausencia se aprueba o se rechaza.
- **FR-006**: Un aviso MUST contener su tipo, el identificador de aquello a lo que se refiere y un texto fijo por tipo; MUST NOT contener nombres, correos, motivos ni comentarios.
- **FR-007**: El mismo aviso (destinatario, tipo y referencia) MUST NOT crearse dos veces.
- **FR-008**: Un aviso MUST crearse solo si la operación que lo origina se confirma; y un fallo al crearlo MUST NOT deshacer esa operación.
- **FR-009**: Cada persona MUST poder listar sus avisos (más recientes primero, opcionalmente solo no leídos, como máximo 100), saber cuántos no ha leído y marcar uno o todos como leídos.
- **FR-010**: Nadie MUST ver ni marcar los avisos de otra persona.
- **FR-011**: Los avisos leídos de más de 90 días y todos los de más de 180 MUST eliminarse automáticamente (configurable).
- **FR-012**: Los cuatro roles MUST poder consultar su bandeja.

### Key Entities

- **Notificacion**: destinatario, tipo, referencia, cuándo se creó, cuándo se leyó.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100% de las solicitudes pendientes de corrección, ausencia y registro generan aviso a sus destinatarios en menos de 1 minuto.
- **SC-002**: Un fichaje abierto más del umbral genera exactamente un aviso.
- **SC-003**: El 0% de los avisos contiene nombres, correos, motivos o comentarios.
- **SC-004**: El 100% de los intentos de ver o marcar avisos ajenos fracasan.

## Assumptions

- Sin correo ni push (no hay canal): la aplicación consulta la bandeja.
- 10 horas de umbral: más que cualquier jornada ordinaria con pausas.
- Los avisos no son documentos con valor legal: se pueden eliminar.

## Dependencies

- Features 001 (fichajes, correcciones), 002 (roles de las cuentas), 005
  (registros) y 007 (ausencias) publican lo que ocurre; esta feature no depende
  de ninguna, solo de contratos en `common`.

## Out of Scope

- Correo electrónico, push, SMS.
- Preferencias de notificación por persona.
- Avisos de "no has fichado la entrada" (necesitan cuadrantes, fuera de alcance).
