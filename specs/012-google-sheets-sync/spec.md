# Feature Specification: Copia del registro de jornada y de las facturas en Google Sheets

**Feature Branch**: `invoice-ml-feature`

**Created**: 2026-10-18

**Status**: Draft

**Input**: User description: "Cualquier dato del fichaje de una persona empleada o de los recibos que se suban, con imagen o con una foto hecha con la cámara del dispositivo, debe quedar registrado automáticamente en una hoja de cálculo de Google Drive. Fichajes: cuando el admin quiere exportar los datos, se rellena automáticamente la hoja (nombre, apellidos, DNI, fecha, fichaje de entrada, fichaje de salida). Recibos: el admin hace fotos a los recibos o sube imágenes que ya tiene en el dispositivo y, mediante Claude, sus datos pasan automáticamente a la hoja."

Decisiones del responsable del producto (2026-10-18):

- La empresa usa **Google Workspace**, no cuentas personales de Gmail.
- Las personas empleadas tienen **nombre y apellidos por separado**.
- **Una pestaña por mes** en cada hoja de cálculo.
- **Las imágenes no se guardan en Drive**: solo los datos.
- El reconocimiento de facturas sigue con Claude, ahora con el modelo Sonnet 5.5
  (cambio ya hecho en la rama; la clave será de otra cuenta de Anthropic y solo
  cambia en el entorno).

La parte de la app (pantallas de configuración, exportación y apellidos) tendrá
su propia spec en el repositorio de la app, a partir del contrato que salga de
esta.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Nombre y apellidos de cada persona (Priority: P1)

Hoy cada persona empleada tiene un único campo de nombre. La empresa necesita el
nombre y los apellidos por separado: para la hoja de fichajes, para la
exportación que se entrega a la Inspección y para identificar sin ambigüedad a
dos personas con el mismo nombre de pila.

**Why this priority**: es la base de la hoja de fichajes (US2), que pide las dos
columnas, y cambia datos que ya existen. Sin ella, la exportación tendría que
partir los nombres actuales a ciegas.

**Independent Test**: dar de alta a una persona con nombre y apellidos, consultarla
y editarla; comprobar que la exportación CSV del registro de jornada muestra las
dos columnas; comprobar que las personas que ya existían siguen funcionando y se
pueden completar.

**Acceptance Scenarios**:

1. **Given** un `ADMIN`, **When** da de alta a una persona (directamente o por el alta
   con acceso), o la propietaria se registra con el código de arranque, con nombre y apellidos, **Then** los
   dos quedan guardados y se devuelven por separado en todas las consultas.
2. **Given** un alta o una edición sin apellidos, **When** se envía, **Then** se
   rechaza indicando que faltan los apellidos.
3. **Given** una persona que existía antes de esta feature, **When** se consulta,
   **Then** conserva su nombre tal cual, sus apellidos aparecen vacíos y se marca
   que están pendientes de completar.
4. **Given** esa persona, **When** el `ADMIN` la edita y añade los apellidos,
   **Then** dejan de estar pendientes.
5. **Given** la exportación CSV del registro de jornada, **When** se genera,
   **Then** incluye nombre y apellidos en columnas separadas.

---

### User Story 2 - Exportar los fichajes de un mes a la hoja de Google (Priority: P1)

El `ADMIN` quiere tener en la hoja de cálculo de la empresa los fichajes de un
mes sin copiarlos a mano. Elige el mes, pulsa "Exportar a Google Drive" y la
pestaña de ese mes queda rellena con una fila por fichaje.

**Why this priority**: es lo que pide la feature y es independiente de la
facturación.

**Independent Test**: con la integración configurada y fichajes en octubre de
2026, exportar octubre y comprobar que la pestaña "2026-10" contiene una fila por
fichaje con nombre, apellidos, DNI, fecha, entrada y salida; volver a exportar y
comprobar que no hay filas duplicadas.

**Acceptance Scenarios**:

1. **Given** fichajes de varias personas en un mes, **When** el `ADMIN` exporta
   ese mes, **Then** la pestaña del mes (nombrada `AAAA-MM`) contiene una fila de
   cabecera y una fila por fichaje: nombre, apellidos, DNI, fecha, hora de
   entrada y hora de salida, ordenadas por fecha y entrada.
2. **Given** un mes ya exportado, **When** se exporta otra vez, **Then** su
   pestaña se reescribe entera con los datos actuales, sin duplicados, y las
   demás pestañas no cambian.
3. **Given** un fichaje con una corrección aprobada, **When** se exporta,
   **Then** la fila muestra los valores vigentes.
4. **Given** una jornada abierta o `INCOMPLETE`, sin salida, **When** se exporta,
   **Then** la salida aparece vacía.
5. **Given** una exportación terminada, **When** se consulta el registro de
   exportaciones, **Then** aparece con su fecha, el periodo cubierto, quién la
   hizo y que su destino fue la hoja de Google.
6. **Given** un usuario que no es `ADMIN`, **When** intenta exportar a la hoja,
   **Then** se le deniega.
7. **Given** la integración sin configurar, **When** el `ADMIN` intenta exportar,
   **Then** se le indica que primero debe configurarla, y la exportación CSV
   sigue disponible como siempre.

---

### User Story 3 - Las facturas confirmadas pasan solas a la hoja de recibos (Priority: P2)

El `ADMIN` fotografía o sube un recibo, Claude lo lee, el `ADMIN` revisa los
datos y lo confirma. Desde ese momento la factura aparece sola en la pestaña de
su mes en la hoja de recibos. Si después la corrige o la descarta, la hoja lo
refleja.

**Why this priority**: aprovecha el reconocimiento que ya existe; depende de la
configuración común (US4) pero no de los fichajes.

**Independent Test**: confirmar una factura con fecha de emisión en octubre de
2026 y comprobar que aparece en la pestaña "2026-10" de la hoja de recibos;
corregirla y comprobar que su fila cambia; descartarla y comprobar que su fila
desaparece.

**Acceptance Scenarios**:

1. **Given** una factura en borrador, **When** el `ADMIN` la confirma, **Then**
   en pocos minutos aparece una fila en la pestaña del mes de su fecha de
   emisión con: fecha de emisión, tipo (emitida o recibida), emisor y su NIF,
   receptor y su NIF, número, concepto, base imponible, IVA, recargo de
   equivalencia, retenciones y total, más una columna con su identificador.
2. **Given** una factura en borrador o pendiente de reconocer, **When** se
   consulta la hoja, **Then** no aparece: nunca se escribe una lectura de la IA
   sin revisar.
3. **Given** una factura confirmada ya en la hoja, **When** el `ADMIN` la
   corrige, **Then** su fila se actualiza; si la corrección cambia el mes de la
   fecha de emisión, la fila pasa a la pestaña del nuevo mes.
4. **Given** una factura confirmada ya en la hoja, **When** el `ADMIN` la
   descarta, **Then** su fila desaparece.
5. **Given** la confirmación de una factura, **When** Google no responde,
   **Then** la confirmación se completa igualmente y la fila se escribe en
   cuanto Google vuelva a responder.
6. **Given** un mes de recibos, **When** el `ADMIN` pide resincronizarlo,
   **Then** su pestaña se reescribe con todas las facturas confirmadas de ese
   mes.

---

### User Story 4 - Configurar las hojas y ver el estado (Priority: P1)

El `ADMIN` crea en la unidad de la empresa las dos hojas de cálculo (fichajes y
recibos), las comparte con la cuenta técnica de Granatum que la app le muestra
e indica sus enlaces en la app. Granatum comprueba que puede escribir en ellas.
Después, el `ADMIN` puede ver si todo se ha copiado bien.

**Why this priority**: sin ella no funcionan ni US2 ni US3.

**Independent Test**: indicar dos hojas compartidas y comprobar que se aceptan;
indicar una hoja no compartida y comprobar que se rechaza explicando qué hacer;
provocar un fallo de escritura y comprobar que el estado lo muestra.

**Acceptance Scenarios**:

1. **Given** la integración disponible en el servidor, **When** el `ADMIN`
   consulta la configuración, **Then** ve el correo de la cuenta técnica con la
   que debe compartir las hojas y si ya hay hojas configuradas.
2. **Given** dos hojas compartidas con esa cuenta como editora, **When** el
   `ADMIN` indica sus enlaces o identificadores, **Then** se comprueba que se
   puede escribir en ambas y se guardan.
3. **Given** una hoja no compartida, inexistente o sin permiso de edición,
   **When** se indica, **Then** se rechaza explicando que hay que compartirla
   con la cuenta técnica como editora.
4. **Given** la misma hoja para fichajes y para recibos, **When** se indica,
   **Then** se rechaza: las dos usan pestañas con el mismo nombre de mes.
5. **Given** escrituras hechas o pendientes, **When** el `ADMIN` consulta el
   estado, **Then** ve la última sincronización correcta, cuántas escrituras hay
   pendientes y los errores recientes con su motivo, y puede reintentar las que
   fallaron definitivamente.
6. **Given** el servidor sin credenciales de Google, **When** cualquiera usa la
   aplicación, **Then** todo funciona como antes y la configuración indica que la
   integración no está disponible.

---

### Edge Cases

- **Google caído, cuota superada o credenciales revocadas**: fichar, confirmar
  facturas y exportar CSV no se ven afectados. Las escrituras quedan pendientes y
  se reintentan con esperas crecientes; tras un número máximo de intentos quedan
  como fallidas, visibles en el estado y reintentables a mano.
- **El `ADMIN` cambia una hoja por otra**: las escrituras nuevas van a la nueva;
  la nueva no se rellena con lo anterior salvo que se exporte o resincronice.
- **Alguien edita a mano una pestaña**: la siguiente exportación de ese mes de
  fichajes la reescribe entera; en recibos, solo se tocan las filas de las
  facturas que cambian. La hoja es una copia de consulta: lo editado allí no
  vuelve a Granatum.
- **Alguien borra la pestaña o la hoja**: la pestaña se vuelve a crear en la
  siguiente escritura; si la hoja ya no existe o no hay permiso, la escritura
  falla y el estado lo muestra.
- **Dos exportaciones del mismo mes a la vez**: se ejecutan una detrás de otra;
  el resultado es el de la última.
- **Fichaje que cruza la medianoche**: va en la fila y el mes de su entrada, con
  la fecha de entrada.
- **Persona sin apellidos todavía**: su fila sale con los apellidos vacíos y el
  resultado de la exportación avisa de cuántas personas están pendientes de
  completar.
- **Persona dada de baja**: sus fichajes del mes se exportan igual.
- **Mes sin fichajes**: la pestaña queda solo con la cabecera.
- **Factura confirmada en un trimestre que luego se cierra**: su fila se queda
  como está. Si se reabre y se corrige, se actualiza.
- **Facturas confirmadas antes de configurar la integración**: no se copian
  solas; el `ADMIN` puede resincronizar sus meses.

## Requirements *(mandatory)*

### Functional Requirements

**Nombre y apellidos**

- **FR-001**: La persona empleada **DEBE** tener nombre y apellidos como datos
  separados, en el alta directa, en el alta con acceso, en el registro de la
  propietaria con el código de arranque, en la edición y en todas las consultas que hoy devuelven el nombre.
- **FR-002**: Las altas y ediciones **DEBEN** exigir apellidos no vacíos, con el
  mismo límite de longitud que el nombre.
- **FR-003**: Las personas existentes **DEBEN** conservar su nombre actual sin
  cambios y quedar con los apellidos vacíos, marcadas como pendientes de
  completar. El sistema **NO DEBE** intentar partir los nombres existentes.
- **FR-004**: La exportación CSV del registro de jornada **DEBE** mostrar nombre y
  apellidos en columnas separadas, respetando que `REPRESENTATIVE` sigue sin ver
  el documento de identidad.

**Configuración**

- **FR-005**: La integración **DEBE** estar disponible solo si el servidor tiene
  credenciales de una cuenta técnica de Google configuradas por el entorno; sin
  ellas el resto de la aplicación **DEBE** funcionar exactamente igual.
- **FR-006**: Solo el `ADMIN` **DEBE** poder ver y cambiar la configuración, que
  muestra el correo de la cuenta técnica y las dos hojas: fichajes y recibos.
- **FR-007**: Al guardar una hoja, el sistema **DEBE** comprobar que la cuenta
  técnica puede escribir en ella y rechazarla con un motivo comprensible si no.
  Fichajes y recibos **DEBEN** ser hojas distintas.
- **FR-008**: Las credenciales de Google **NO DEBEN** exponerse nunca por la API,
  los registros de log ni las respuestas de error.

**Fichajes**

- **FR-009**: El `ADMIN` **DEBE** poder exportar a la hoja de fichajes un mes
  completo, de toda la plantilla, incluido el mes en curso.
- **FR-010**: La exportación **DEBE** reescribir por completo la pestaña del mes
  (`AAAA-MM`), creándola si no existe, con una cabecera y una fila por fichaje:
  nombre, apellidos, DNI, fecha, entrada y salida, en la zona horaria de la
  empresa. Las demás pestañas **NO DEBEN** cambiar.
- **FR-011**: Cada fila **DEBE** reflejar los valores vigentes del fichaje,
  incluidas las correcciones aprobadas, y la salida **DEBE** quedar vacía si no
  la hay.
- **FR-012**: Cada exportación a la hoja **DEBE** quedar en el registro de
  exportaciones existente, con su destino, quién la hizo, el periodo cubierto y
  una huella de las filas escritas.
- **FR-013**: La exportación **NO DEBE** modificar ningún dato del registro
  horario.
- **FR-014**: El resultado de la exportación **DEBE** indicar las filas escritas
  y cuántas personas tienen los apellidos pendientes.

**Facturas**

- **FR-015**: Al confirmar una factura, el sistema **DEBE** programar la
  escritura de su fila en la pestaña del mes de su fecha de emisión de la hoja
  de recibos, con las columnas de US3 y su identificador.
- **FR-016**: Al corregir una factura confirmada, el sistema **DEBE** actualizar
  su fila y moverla de pestaña si cambia el mes. Al descartarla, **DEBE** quitar
  su fila.
- **FR-017**: El sistema **NO DEBE** escribir nunca facturas que no estén
  confirmadas.
- **FR-018**: El `ADMIN` **DEBE** poder resincronizar un mes de recibos, que
  reescribe su pestaña con todas las facturas confirmadas de ese mes.
- **FR-019**: Las imágenes y los documentos originales **NO DEBEN** enviarse a
  Google.

**Fiabilidad y estado**

- **FR-020**: Ninguna escritura en Google **DEBE** formar parte de la operación
  que la origina: fichar, confirmar, corregir o descartar una factura, y
  exportar a CSV **DEBEN** completarse aunque Google no responda.
- **FR-021**: Las escrituras pendientes **DEBEN** guardarse de forma persistente,
  sobrevivir a reinicios del servidor y aplicarse en orden por factura, de modo
  que el estado final de cada fila corresponda al último cambio.
- **FR-022**: Las escrituras fallidas **DEBEN** reintentarse automáticamente con
  esperas crecientes hasta un máximo de intentos, y después quedar como fallidas.
- **FR-023**: El `ADMIN` **DEBE** poder consultar el estado: última sincronización
  correcta, escrituras pendientes y fallidas con su motivo; y reintentar las
  fallidas.

**Acceso y datos personales**

- **FR-024**: Exportar, resincronizar, configurar y consultar el estado **DEBEN**
  estar reservados al `ADMIN`.
- **FR-025**: Las tablas nuevas **DEBEN** tener seguridad a nivel de fila como el
  resto.

### Key Entities

- **Persona empleada**: gana los apellidos, y la marca de "apellidos pendientes"
  para las que existían antes.
- **Configuración de la integración**: las dos hojas (fichajes y recibos), quién
  y cuándo las configuró, y si la última comprobación de acceso fue correcta.
- **Escritura pendiente**: qué hay que escribir (una factura o la pestaña de un
  mes), en qué hoja, intentos, último error, estado (pendiente, hecha, fallida) e
  instantes.
- **Registro de exportaciones** (existente): gana el destino "hoja de Google"
  junto al CSV actual.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Exportar un mes de una plantilla de 4 personas, unos 100 fichajes,
  deja la pestaña rellena en menos de 1 minuto desde que el `ADMIN` lo pide.
- **SC-002**: Exportar el mismo mes dos veces seguidas deja exactamente las
  mismas filas: cero duplicados.
- **SC-003**: Con Google disponible, una factura confirmada aparece en la hoja en
  menos de 5 minutos.
- **SC-004**: Con Google sin responder, el 100 % de los fichajes, confirmaciones
  y exportaciones CSV se completan con normalidad, y las escrituras pendientes se
  aplican en los 30 minutos siguientes a que Google vuelva a responder.
- **SC-005**: Tras corregir o descartar una factura, la hoja la refleja en menos
  de 5 minutos, sin filas huérfanas ni duplicadas.
- **SC-006**: Ningún rol distinto de `ADMIN` puede exportar, resincronizar,
  configurar ni consultar el estado de la integración.
- **SC-007**: Un `ADMIN` deja configuradas las dos hojas en menos de 5 minutos
  siguiendo solo las indicaciones de la app.

## Assumptions

- La empresa tiene Google Workspace y crea las hojas en una unidad compartida o
  en la unidad de una cuenta de la empresa; la cuenta técnica de Google
  (cuenta de servicio) solo edita hojas existentes que se le compartan y no crea
  hojas nuevas.
- Las credenciales de la cuenta técnica las da de alta quien despliega el
  servidor, por variable de entorno, igual que la clave de Claude.
- Cada entorno tiene su propia cuenta técnica y sus propias hojas: PRE usa una
  cuenta de un proyecto de prueba (`granatumprueba@proyecto-prueba-granatum.iam.gserviceaccount.com`,
  ya compartida con las dos hojas de prueba el 2026-10-18) y PRO usará otra del
  Workspace real de la empresa. El correo que muestra la app sale de las
  credenciales del entorno, nunca del código.
- Se usan hojas de cálculo de Google, no ficheros .xlsx; se pueden descargar como
  Excel desde Google cuando haga falta.
- La zona horaria de la empresa es la ya usada por el registro de jornada.
- La pestaña de un recibo es la del mes de su fecha de emisión, como los
  trimestres de facturación.
- "Recibos" y "facturas" son lo mismo para esta feature: los tiques y facturas
  simplificadas se tratan como facturas, como ya hace la facturación.
- La hoja es una copia de consulta: el registro legal es la base de datos de
  Granatum, y lo que se edite en la hoja no vuelve a la aplicación.
- La cabecera de las pestañas está en español.
- El acuerdo de tratamiento de datos con Google lo cubre la suscripción de
  Google Workspace de la empresa.
- Columnas de la hoja de fichajes: las seis pedidas. Pausas y horas trabajadas
  quedan fuera; se pueden añadir más adelante sin cambiar el diseño.
