# Feature Specification: Eventos y reserva de materiales

**Feature Branch**: `events-feature`

**Created**: 2026-10-17

**Status**: Draft

**Input**: User description: "Una feature nueva llamada events, otra pantalla y otra opción del bottom bar (para el admin y para los trabajadores) en la que se puedan crear eventos manualmente: tarjetas con el nombre «Evento 1», una descripción «Boda de Juan y María» y la fecha del evento. En el detalle de cada evento se editan los materiales que se usarán, conectados con los del inventario. Al añadir materiales a un evento se reducen automáticamente en el inventario, y otro evento ya dispone de los actualizados. Si no hay stock, se avisa. Se puede añadir cualquier material, eligiendo cuál y la cantidad. Cuando un evento termina se marca como finalizado y sus materiales vuelven al inventario como disponibles."

Decisiones del responsable del producto (2026-10-17):

- Crean, editan y asignan materiales `ADMIN`, `ENCARGADO` y `EMPLEADO`; `REPRESENTANTE` solo consulta.
- Un evento se finaliza solo al terminar su día (hora de Madrid) y también con un botón «Finalizar ahora».
- Si se pide más cantidad de la que hay, no se asigna nada y se indica cuántas unidades hay disponibles.

## Contexto

Granatum prepara eventos (bodas, celebraciones) con material del inventario:
jarrones, cintas, cestas. Hoy no hay forma de saber qué material está comprometido
con qué evento, ni cuánto queda libre para el siguiente. Esta feature añade el
**evento** y la **reserva** de materiales: asignar material a un evento lo saca
del inventario mientras dura, y finalizar el evento lo devuelve.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Crear y consultar eventos (Priority: P1)

Una persona de la plantilla crea un evento con nombre, descripción y fecha. Los
eventos aparecen como tarjetas, los próximos primero, y se pueden consultar en
detalle.

**Independent Test**: crear «Evento 1 / Boda de Juan y María / 13-06-2027» y
encontrarlo en la lista de próximos con esos datos.

**Acceptance Scenarios**:

1. **Given** una persona con rol `EMPLEADO`, **When** crea un evento con nombre,
   descripción y una fecha de hoy o futura, **Then** queda `PROGRAMADO` y aparece en la lista.
2. **Given** una fecha anterior a hoy, **When** se crea o se cambia el evento a esa fecha,
   **Then** se rechaza con `FECHA_PASADA`.
3. **Given** un `REPRESENTANTE`, **When** consulta los eventos, **Then** los ve, pero
   no puede crear, editar, asignar ni finalizar (`403`).
4. **Given** un evento programado, **When** se edita su nombre, descripción o fecha,
   **Then** se guardan los cambios.

### User Story 2 - Asignar materiales a un evento (Priority: P1)

En el detalle del evento se añade cualquier material del inventario con la
cantidad necesaria. Esa cantidad se descuenta del inventario al momento, y queda
anotada en el historial del material.

**Independent Test**: con 8 jarrones en inventario, asignar 5 al evento deja 3 en
inventario y una entrada de historial que nombra el evento.

**Acceptance Scenarios**:

1. **Given** un material con 8 unidades, **When** se asignan 5 a un evento,
   **Then** el inventario pasa a 3 y el evento muestra 5.
2. **Given** ese mismo material con 3 unidades libres, **When** otro evento pide 4,
   **Then** se rechaza con `STOCK_INSUFICIENTE`, la respuesta dice que hay 3 y no
   cambia nada.
3. **Given** un evento con 5 unidades asignadas, **When** se cambia a 2, **Then**
   vuelven 3 al inventario; **When** se cambia a 7, **Then** se retiran 2 más (si los hay).
4. **Given** un material asignado, **When** se quita del evento, **Then** vuelven
   todas sus unidades al inventario.
5. **Given** un `EMPLEADO`, que no tiene acceso al inventario, **When** abre el
   selector de materiales del evento, **Then** ve cada material con su categoría,
   descripción, localización y unidades disponibles.

### User Story 3 - Finalizar un evento (Priority: P2)

Al terminar el día del evento, el sistema lo marca como `FINALIZADO` y devuelve
todos sus materiales al inventario. Si termina antes, se puede finalizar a mano.
Un evento finalizado ya no se modifica.

**Acceptance Scenarios**:

1. **Given** un evento de ayer todavía programado, **When** pasa la tarea diaria,
   **Then** queda `FINALIZADO` y sus materiales vuelven al inventario.
2. **Given** un evento de hoy, **When** alguien pulsa «Finalizar ahora», **Then**
   queda `FINALIZADO` y los materiales vuelven.
3. **Given** un evento finalizado, **When** se intenta editar o cambiar sus
   materiales, **Then** se rechaza con `EVENTO_FINALIZADO`. Finalizarlo otra vez no hace nada.

### User Story 4 - Borrar un evento (Priority: P3)

Un evento creado por error se puede borrar. Si estaba programado, sus materiales
vuelven al inventario.

### Edge Cases

- Dos personas asignan a la vez el último stock de un material: solo una lo consigue;
  la otra recibe `STOCK_INSUFICIENTE`. El inventario nunca queda en negativo.
- Borrar del inventario un material que está asignado a un evento programado se
  rechaza con `MATERIAL_EN_USO`: sus unidades están fuera, en un evento.
- Un material borrado del inventario después de devolverse no afecta a los eventos
  finalizados: conservan la descripción con la que se asignó.
- La aplicación estaba parada a medianoche: la tarea siguiente finaliza todos los
  eventos de días anteriores, no solo los de ayer.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Un evento MUST tener nombre (1–100), descripción (0–500), fecha (día, sin hora) y estado `PROGRAMADO` o `FINALIZADO`, además de quién lo creó y sus fechas de alta y modificación.
- **FR-002**: La fecha de un evento MUST ser hoy o posterior (hora de Madrid) al crearlo o cambiarla.
- **FR-003**: Un evento MUST poder tener cualquier material del inventario, cada uno una sola vez y con una cantidad entera mayor que cero.
- **FR-004**: Asignar o aumentar la cantidad de un material MUST descontarla del inventario en la misma transacción; disminuirla o quitarlo MUST devolver la diferencia.
- **FR-005**: Si no hay unidades suficientes, la operación MUST rechazarse entera con `409 STOCK_INSUFICIENTE`, indicando las unidades disponibles, sin cambiar nada.
- **FR-006**: Cada retirada y devolución MUST quedar en el historial del material como cambio de `CANTIDAD`, con un motivo que nombre el evento.
- **FR-007**: Una tarea diaria MUST finalizar los eventos programados con fecha anterior a hoy (Madrid) y devolver sus materiales. MUST poder ejecutarse varias veces sin efecto adicional.
- **FR-008**: «Finalizar ahora» MUST hacer lo mismo para un evento concreto; sobre uno ya finalizado no hace nada.
- **FR-009**: Un evento finalizado MUST NOT modificarse (`409 EVENTO_FINALIZADO`), aunque se puede consultar y borrar.
- **FR-010**: Borrar un evento programado MUST devolver sus materiales.
- **FR-011**: Leer eventos: `ADMIN`, `ENCARGADO`, `EMPLEADO`, `REPRESENTANTE`. Escribir: `ADMIN`, `ENCARGADO`, `EMPLEADO`.
- **FR-012**: El selector de materiales MUST ofrecerse desde las rutas de eventos, con los datos justos (categoría, descripción, localización, unidades e imagen), porque `EMPLEADO` no accede a las rutas de inventario.
- **FR-013**: El inventario MUST rechazar borrar un material asignado a un evento programado (`409 MATERIAL_EN_USO`).
- **FR-014**: `events` MUST NOT depender de `inventory` ni al revés (principio I): todo cruce va por contratos de `common`.

### Key Entities

- **Evento**: nombre, descripción, fecha, estado, creador, alta, modificación, finalización.
- **Material del evento**: el material (su id en inventario), la cantidad reservada y la descripción y categoría que tenía al asignarse.

## Success Criteria *(mandatory)*

- **SC-001**: Tras asignar N unidades a un evento, el inventario muestra N menos sin ninguna acción adicional.
- **SC-002**: Ninguna combinación de asignaciones concurrentes deja un material con cantidad negativa.
- **SC-003**: Al día siguiente de un evento, sus materiales vuelven a estar disponibles sin intervención.
- **SC-004**: Un `REPRESENTANTE` no consigue modificar nada de eventos; un `EMPLEADO` sigue sin acceder a `/api/materiales`.

## Assumptions

- No hay solapes por horas: la reserva dura desde que se asigna hasta que el evento finaliza, sin importar la fecha de otros eventos.
- Todo el material vuelve al finalizar (no se contemplan consumibles ni roturas en esta versión; una rotura se registra después en inventario con su motivo).
- Las devoluciones automáticas se anotan en el historial a nombre de quien creó el evento, con un motivo que dice que fueron automáticas: `historial_material.usuario_id` es obligatorio y no hay usuario de sistema.
