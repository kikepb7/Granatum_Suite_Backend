# Feature Specification: Exportación del registro de jornada

**Feature Branch**: `export-feature`

**Created**: 2026-10-06

**Status**: Draft

**Input**: User description: "Exportación de fichajes. Exportar los fichajes en CSV auditable por persona empleada y rango de fechas, y de toda la plantilla para ENCARGADO, ADMIN y REPRESENTANTE. Columnas: empleado, DNI, fecha, entrada, salida, pausas, horas trabajadas, estado y correcciones aplicadas (quién y cuándo). Un EMPLEADO solo exporta lo suyo. Además, debe existir la descarga mensual del registro de cada persona, porque es la condición que la constitución (principio III, v2.0.0) exige para poder habilitar la depuración a los 4 años: los registros se conservan 4 años completos y, cumplido ese plazo, los depura únicamente el proceso automático de retención, nunca una operación manual. Fuera de alcance: PDF."

## Clarifications

### Session 2026-10-06

Dos puntos en los que la spec se aparta del texto original del encargo, resueltos
por el responsable del producto:

- Q: ¿La representación legal ve el documento de identidad en sus exportaciones?
  → A: **No.** Se identifica a cada persona por nombre y puesto (FR-012). El
  encargo pedía la columna para todas las exportaciones, pero `REPRESENTANTE` no
  ve el documento en ninguna otra parte de la aplicación y el principio IV limita
  ese rol al mínimo que satisface el art. 34.9.
- Q: En un fichaje corregido, ¿se muestran solo los valores vigentes o también los
  originales? → A: **Ambos** (FR-004, SC-010). El encargo pedía "quién y cuándo";
  se añaden los valores originalmente registrados porque son lo que hace el
  fichero auditable: quien lo lee ve qué se fichó y qué se cambió.
- Q: ¿Por qué fechas se filtra la consulta de exportaciones? → A: **Por las
  dos**: fecha de generación y periodo cubierto (FR-029). Resuelto tras
  `/speckit-analyze`, que encontró la ambigüedad entre la spec y el contrato.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Descargar mi propio registro (Priority: P1)

Una persona empleada quiere tener una copia de su jornada registrada entre dos
fechas: para revisarla, para comprobar una nómina o para presentarla ante quien
corresponda. La descarga y la abre en una hoja de cálculo sin ningún paso
intermedio.

**Why this priority**: El art. 34.9 del Estatuto de los Trabajadores obliga a
tener el registro **a disposición de la persona trabajadora**. Hoy puede
consultarlo en pantalla, pero no llevárselo. Es además el caso más simple y el
que fija el formato que todas las demás historias reutilizan.

**Independent Test**: Con una persona que ha fichado varios días, descargar su
registro de un rango y comprobar que cada fichaje aparece con sus valores
vigentes y que el fichero se abre correctamente en una hoja de cálculo con
configuración regional española.

**Acceptance Scenarios**:

1. **Given** una persona empleada con fichajes en un rango, **When** descarga su registro de ese rango, **Then** obtiene un fichero con una fila por fichaje y todas las columnas del registro.
2. **Given** una persona empleada, **When** intenta descargar el registro de otra persona, **Then** se rechaza.
3. **Given** un fichaje con una corrección aprobada, **When** se descarga, **Then** la fila muestra los valores vigentes tras la corrección e identifica quién la solicitó, quién la aprobó y cuándo.
4. **Given** un fichaje todavía abierto o marcado como incompleto, **When** se descarga, **Then** aparece con su estado y sin horas trabajadas, nunca con un cero.
5. **Given** un rango en el que la persona no fichó, **When** lo descarga, **Then** obtiene un fichero válido sin filas de datos, no un error.
6. **Given** un nombre o un motivo que empiezan por un carácter que una hoja de cálculo interpretaría como fórmula, **When** se abre el fichero, **Then** se muestra como texto y no se ejecuta.

---

### User Story 2 - Descarga mensual del registro de una persona (Priority: P1)

Al cerrar el mes, la persona —o quien gestiona la plantilla, o la
representación legal— descarga el registro mensual de esa persona: cada día con
su entrada, su salida, sus pausas y sus horas, más el total del mes, con los
días reconstruidos o corregidos señalados.

**Why this priority**: Es la condición de FR-031d de la feature de jornada y del
principio III de la constitución: **la depuración de los registros a los cuatro
años no puede habilitarse mientras esta descarga no exista**, porque la base
para destruir un registro es que haya estado a disposición antes. Sin esta
historia el producto incumple la obligación de destruir por el otro lado —
conservar indefinidamente vulnera el art. 5.1.e del RGPD—. Es también la forma
de entregar el resumen mensual que el art. 12.4.c del Estatuto exige para los
contratos a tiempo parcial.

**Independent Test**: Para una persona y un mes con jornadas normales, una
corregida y una reconstruida, descargar el mes y comprobar que el total
coincide con la suma de los días y que las dos jornadas especiales están
señaladas.

**Acceptance Scenarios**:

1. **Given** una persona con jornadas en un mes, **When** se descarga su registro de ese mes, **Then** el fichero contiene cada jornada del mes y el total de horas del mes.
2. **Given** un mes con una jornada corregida y otra reconstruida, **When** se descarga, **Then** ambas aparecen señaladas como tales.
3. **Given** una persona con contrato a tiempo parcial, **When** se descarga su mes, **Then** el fichero indica el tipo de contrato.
4. **Given** el mes en curso, **When** se descarga, **Then** el fichero indica que el mes no está cerrado, para que no se tome por definitivo.
5. **Given** la propia persona, una persona con rol `ENCARGADO`, `ADMIN` o `REPRESENTANTE`, **When** cualquiera de ellas descarga ese mes, **Then** la descarga se permite; a cualquier otra persona con rol `EMPLEADO` se le rechaza.
6. **Given** el total de horas del fichero mensual, **When** se compara con el resumen mensual que ya ofrece la consulta en pantalla, **Then** coinciden exactamente.

---

### User Story 3 - Exportar el registro de toda la plantilla (Priority: P2)

Una persona con rol `ENCARGADO` o `ADMIN` necesita el registro de toda la
plantilla entre dos fechas, por ejemplo para atender un requerimiento de la
Inspección de Trabajo. Lo descarga en un único fichero.

**Why this priority**: Es la entrega que la Inspección pide, pero se apoya en el
mismo formato que la historia 1 y puede servirse, mientras tanto, persona a
persona.

**Independent Test**: Con varias personas que han fichado —una de ellas ya dada
de baja—, exportar la plantilla en un rango y comprobar que aparecen todas, la
dada de baja incluida, ordenadas de forma estable.

**Acceptance Scenarios**:

1. **Given** una persona con rol `ENCARGADO` o `ADMIN`, **When** exporta la plantilla en un rango, **Then** obtiene un único fichero con los fichajes de todas las personas en ese rango.
2. **Given** una persona dada de baja con fichajes dentro del plazo de conservación, **When** se exporta la plantilla, **Then** sus fichajes aparecen.
3. **Given** dos exportaciones del mismo alcance y rango sin cambios entre medias, **When** se comparan los ficheros, **Then** son idénticos.
4. **Given** una persona con rol `EMPLEADO`, **When** intenta exportar la plantilla, **Then** se rechaza.

---

### User Story 4 - Puesta a disposición de la representación legal (Priority: P2)

La representación legal de los trabajadores consulta el registro de la
plantilla, como le reconoce el art. 34.9 del Estatuto, y se lo descarga.

**Why this priority**: Es una obligación legal con destinatario propio, y el rol
`REPRESENTANTE` ya existe para ella. Va después de la historia 3 porque reutiliza
la misma exportación con un alcance de datos más estrecho.

**Independent Test**: Exportar la plantilla con rol `REPRESENTANTE` y comprobar
que contiene la jornada de todas las personas sin ningún dato que no le
corresponda ver.

**Acceptance Scenarios**:

1. **Given** una persona con rol `REPRESENTANTE`, **When** exporta la plantilla o el registro de una persona, **Then** la exportación se permite.
2. **Given** una exportación hecha con rol `REPRESENTANTE`, **When** se inspecciona el fichero, **Then** no contiene ninguna ubicación.
3. **Given** una exportación hecha con rol `REPRESENTANTE`, **When** se inspecciona el fichero, **Then** **no** contiene el documento de identidad: la persona se identifica por su nombre y su puesto (FR-012).
4. **Given** una persona con rol `REPRESENTANTE`, **When** exporta, **Then** nada del registro cambia: la exportación es solo lectura.

---

### User Story 5 - Saber quién exportó qué y verificar un fichero (Priority: P3)

Una persona con rol `ADMIN` necesita saber quién ha descargado datos de quién, y
comprobar si un fichero que alguien presenta —por ejemplo ante la Inspección—
es exactamente el que el sistema generó o ha sido modificado después.

**Why this priority**: Es lo que hace que la exportación sea **auditable** y no
solo un volcado. Va al final porque no impide entregar los datos, pero sin ella
no hay forma de demostrar que un fichero no se ha retocado.

**Independent Test**: Exportar un rango, modificar una cifra del fichero, y
comprobar que la verificación distingue el fichero original del modificado.

**Acceptance Scenarios**:

1. **Given** cualquier exportación o descarga mensual, **When** se completa, **Then** queda anotado quién la hizo, de qué alcance y rango, cuándo y cuántas filas contenía.
2. **Given** un fichero generado por el sistema, **When** una persona con rol `ADMIN` lo verifica, **Then** el sistema confirma que coincide con una exportación anotada e indica cuál.
3. **Given** ese mismo fichero con un solo carácter cambiado, **When** se verifica, **Then** el sistema indica que no coincide con ninguna exportación.
4. **Given** el registro de exportaciones, **When** se inspecciona, **Then** no contiene ningún dato exportado: ni horas, ni documentos, ni nombres.

---

### Edge Cases

- **Cambio de hora**: el día del cambio de horario de verano dura 23 horas y el de
  invierno 25. Las horas exportadas MUST ser las efectivamente trabajadas y las
  horas mostradas, las de reloj en España ese día.
- **Fichaje que cruza la medianoche**: pertenece al día de su entrada, igual que
  en el resumen mensual de la feature de jornada.
- **Jornada partida**: dos fichajes el mismo día son dos filas, no una.
- **Fichaje abierto al exportar**: aparece en estado abierto, sin salida y sin
  horas.
- **Corrección pendiente o rechazada**: no es una "corrección aplicada", así que no
  altera los valores ni aparece como tal; los valores son los vigentes.
- **Rango que se adentra en registros ya depurados**: el fichero MUST dejar claro
  que antes de una fecha ya no hay datos por haberse cumplido el plazo, para que
  un fichero vacío no se lea como "no trabajó".
- **Nombres o motivos con punto y coma, comillas o saltos de línea**: el fichero
  sigue teniendo las mismas columnas en todas las filas.
- **Nombres que empiezan por `=`, `+`, `-` o `@`**: se muestran como texto; nunca se
  ejecutan como fórmula al abrir el fichero.
- **Rango invertido** (fin anterior al inicio): se rechaza.
- **Persona que no existe**: se rechaza, igual que en el resto de la API.
- **Persona dada de baja**: sus registros siguen siendo exportables por quien
  gestiona la plantilla mientras dure el plazo de conservación. Ella misma ya no
  puede entrar en la aplicación (feature de autenticación), así que su acceso a sus
  datos pasa por una persona con rol `ADMIN`.

## Requirements *(mandatory)*

### Functional Requirements

**Contenido del fichero**

- **FR-001**: El sistema MUST producir el registro como un fichero de tabla con una fila por fichaje y estas columnas: persona, documento de identidad (salvo FR-012), puesto, fecha, entrada, salida, pausas, horas trabajadas, estado, tipo de jornada (completada a posteriori, corregida) y correcciones aplicadas.
- **FR-002**: El sistema MUST describir en la columna de pausas cada pausa del fichaje con su tipo, su inicio y su fin.
- **FR-003**: El sistema MUST expresar fechas y horas en la hora oficial peninsular española vigente en cada instante, y MUST asignar cada fichaje a la fecha civil de su entrada.
- **FR-004**: El sistema MUST mostrar en cada fila los valores **vigentes** tras las correcciones aprobadas, igual que el resumen mensual de la feature de jornada (FR-034 de aquella), y para cada fichaje con una corrección aplicada MUST mostrar también los valores **originalmente registrados** antes de corregirse.
- **FR-005**: El sistema MUST identificar, para cada corrección aplicada, quién la solicitó, quién la aprobó y en qué instante. Solo las correcciones **aprobadas** cuentan como aplicadas.
- **FR-006**: El sistema MUST dejar vacías las horas trabajadas de un fichaje abierto o incompleto, nunca con un cero: un cero afirmaría que se trabajó nada, que es otra cosa y es falsa.
- **FR-007**: El sistema MUST NOT incluir la ubicación de los fichajes en ninguna exportación, para ningún rol. No la exige ninguna de las obligaciones que este fichero satisface y es el dato más intrusivo del registro.
- **FR-008**: El sistema MUST producir un fichero que se abra correctamente en una hoja de cálculo con configuración regional española sin ningún paso de importación: columnas separadas y tildes y eñes legibles.
- **FR-009**: El sistema MUST garantizar que ningún valor del fichero se interprete como fórmula al abrirlo en una hoja de cálculo, sea cual sea su primer carácter.
- **FR-010**: El sistema MUST mantener el mismo número de columnas en todas las filas aunque un valor contenga separadores, comillas o saltos de línea.
- **FR-011**: El sistema MUST ordenar las filas de forma estable —por persona y, dentro de cada persona, por fecha y hora de entrada— de modo que dos exportaciones del mismo alcance y rango sin cambios entre medias produzcan ficheros idénticos.

**Alcance y permisos**

- **FR-012**: El sistema MUST excluir el documento de identidad de las exportaciones hechas con rol `REPRESENTANTE`, identificando a cada persona por su nombre y su puesto. El art. 34.9 obliga a poner el registro de jornada a disposición de la representación legal, no los datos identificativos de la plantilla, y el principio IV limita ese rol al mínimo que satisface la obligación; hoy `REPRESENTANTE` no ve el documento en ninguna parte de la aplicación.
- **FR-013**: El sistema MUST permitir a una persona con rol `EMPLEADO` exportar **únicamente** su propio registro, determinado por su identidad autenticada y nunca por un identificador aportado en la petición.
- **FR-014**: El sistema MUST permitir a las personas con rol `ENCARGADO`, `ADMIN` o `REPRESENTANTE` exportar el registro de cualquier persona de la plantilla o de la plantilla entera.
- **FR-015**: El sistema MUST incluir en las exportaciones de la plantilla a las personas dadas de baja cuyos registros sigan dentro del plazo de conservación.
- **FR-016**: El sistema MUST exigir un rango con fecha de inicio y de fin, y MUST rechazar un rango cuyo fin sea anterior a su inicio.
- **FR-017**: El sistema MUST indicar en la exportación cuándo el rango pedido empieza antes del límite del plazo de conservación, señalando a partir de qué fecha hay datos, para que la ausencia de filas no se interprete como ausencia de trabajo.
- **FR-018**: El sistema MUST garantizar que exportar no modifica nada del registro de jornada.

**Descarga mensual**

- **FR-019**: El sistema MUST permitir descargar el registro de una persona para un mes natural, con cada jornada del mes y el total de horas del mes.
- **FR-020**: El sistema MUST calcular el total del fichero mensual exactamente igual que el resumen mensual de la feature de jornada (FR-032 a FR-034 de aquella), de modo que ambos coincidan siempre.
- **FR-021**: El sistema MUST indicar en el fichero mensual el tipo de contrato de la persona, para identificar los resúmenes que el art. 12.4.c del Estatuto obliga a entregar en los contratos a tiempo parcial.
- **FR-022**: El sistema MUST indicar en el fichero mensual si el mes descargado no ha terminado todavía.
- **FR-023**: El sistema MUST permitir la descarga mensual a la propia persona y a las personas con rol `ENCARGADO`, `ADMIN` o `REPRESENTANTE`, aplicando FR-007 y FR-012.
- **FR-024**: El sistema MUST considerar cumplida, con la disponibilidad de esta descarga, la condición de FR-031d de la feature de jornada. Habilitar la depuración MUST seguir siendo una decisión explícita en cada entorno y no una consecuencia de desplegar esta feature: es la única operación del producto que destruye registros con valor legal.

**Auditoría de las exportaciones**

- **FR-025**: El sistema MUST anotar cada exportación y cada descarga mensual: quién la hizo, de quién (una persona o la plantilla), qué rango, cuándo, cuántas filas contenía y una huella del contenido exacto del fichero.
- **FR-026**: El sistema MUST NOT incluir en esa anotación ningún dato exportado —ni horas, ni nombres, ni documentos—, solo identificadores, el rango, el recuento y la huella.
- **FR-027**: El sistema MUST tratar el registro de exportaciones como inalterable: ninguna ruta, rol ni proceso puede modificar o borrar una anotación dentro del plazo de conservación del registro al que se refiere.
- **FR-028**: El sistema MUST permitir a una persona con rol `ADMIN` comprobar si un fichero dado coincide exactamente con una exportación anotada, e indicar con cuál.
- **FR-029**: El sistema MUST permitir a una persona con rol `ADMIN` consultar las exportaciones anotadas, filtrando por persona exportada, por **fecha de generación** y por **periodo cubierto** —las exportaciones cuyo rango se solapa con el pedido—. La primera responde "¿qué se exportó esta semana?"; la segunda, "¿quién obtuvo los datos de marzo?", que es la pregunta de una auditoría.

### Key Entities *(include if feature involves data)*

- **Exportación**: constancia de que alguien obtuvo una copia del registro. Quién la pidió, de quién era (una persona o la plantilla entera), qué rango cubría o qué mes, cuándo se generó, cuántas filas tenía y la huella de su contenido. No contiene ningún dato del registro exportado.
- **Fila del registro exportado**: no se almacena; se produce a partir del fichaje vigente, sus pausas, sus correcciones aprobadas y la persona a la que pertenece.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Una persona empleada obtiene su propio registro de cualquier rango dentro del plazo de conservación, y el 100% de sus intentos de obtener el de otra persona se rechazan.
- **SC-002**: Cada fila exportada coincide con el fichaje vigente correspondiente en entrada, salida, pausas, horas y estado; en una comprobación cruzada de todas las filas de una exportación no hay ninguna discrepancia.
- **SC-003**: El total de horas de la descarga mensual coincide exactamente con el del resumen mensual en pantalla para el 100% de las personas y meses comprobados.
- **SC-004**: Ninguna exportación, de ningún rol, contiene una ubicación; ninguna exportación hecha con rol `REPRESENTANTE` contiene un documento de identidad.
- **SC-005**: El fichero se abre en una hoja de cálculo con configuración regional española con las columnas separadas y los caracteres acentuados correctos, sin ningún paso de importación.
- **SC-006**: Ningún valor se ejecuta como fórmula al abrir el fichero, incluidos nombres y motivos que empiezan por `=`, `+`, `-` o `@`.
- **SC-007**: El 100% de las exportaciones y descargas mensuales quedan anotadas, y ninguna anotación contiene datos del registro exportado.
- **SC-008**: Dos exportaciones del mismo alcance y rango sin cambios entre medias son idénticas, y la verificación distingue el 100% de los ficheros alterados, aunque la alteración sea un solo carácter.
- **SC-009**: Exportar el registro de un año completo de una plantilla de 100 personas termina en menos de 30 segundos.
- **SC-010**: El 100% de los fichajes con una corrección aplicada muestran quién la solicitó, quién la aprobó, cuándo, y los valores originalmente registrados.
- **SC-011**: Las personas dadas de baja aparecen en el 100% de las exportaciones de la plantilla que cubren sus fechas dentro del plazo de conservación.

## Assumptions

- **Una fila por fichaje, no por día.** Una jornada partida son dos fichajes y dos
  filas; los días sin fichaje no generan fila. El registro recoge lo que ocurrió, y
  rellenar días vacíos inventaría información.
- **La exportación por rango no lleva filas de totales**; la descarga mensual sí
  termina con el total del mes. Mezclar filas de datos y de totales en una
  exportación pensada para tratarse como tabla la haría inservible para eso; el
  fichero mensual, en cambio, es un documento para una persona y el total es su
  contenido principal.
- **Los metadatos de la exportación no van dentro de la tabla.** Quién la generó,
  cuándo y de qué rango quedan en el registro de exportaciones y en el nombre del
  fichero; meter cabeceras de texto dentro rompería su lectura como tabla.
- **La ubicación no se exporta para nadie, ni siquiera para `ADMIN`.** Ninguna de
  las obligaciones que este fichero satisface la pide. Si un caso concreto la
  necesitara —una investigación interna, por ejemplo— sería otra feature con su
  propia justificación.
- **Exportar no requiere ningún consentimiento ni aviso a la persona afectada.** Es
  el cumplimiento de una obligación legal del empleador. Queda anotado (FR-025), y
  ese registro es la rendición de cuentas.
- **El registro de exportaciones se conserva lo mismo que los registros a los que se
  refiere**: su fin es demostrar que un mes estuvo a disposición antes de
  destruirlo, y una vez destruido el mes no hay nada que demostrar sobre él. La
  depuración de la feature de jornada lo incluirá.
- **El mes en curso se puede descargar**, señalado como no terminado (FR-022), en
  lugar de prohibirse: quien lo descarga a mitad de mes tiene derecho a ver lo que
  lleva registrado.
- **No hay entrega activa** (correo, notificación). FR-035 de la feature de jornada
  ya dejó la entrega del resumen mensual al empleador; esta feature pone el
  documento a disposición, que es lo que FR-031d exige para la depuración.
- **Escala**: plantillas de decenas a pocos cientos de personas. SC-009 fija el
  objetivo para 100 personas y un año.
- **Formato**: tabla de texto separada, legible directamente en una hoja de cálculo
  española. Es la elección del responsable del producto; PDF queda fuera de alcance.

## Dependencies

- **Feature de jornada (001)**: los fichajes, pausas, correcciones aprobadas con sus
  valores originales, el resumen mensual (FR-032 a FR-035) y la depuración a los 4
  años (FR-031 a FR-031d), que esta feature desbloquea.
- **Feature de autenticación (002)**: la identidad y el rol de quien exporta salen de
  su sesión. Una persona dada de baja ya no puede iniciar sesión, por eso su acceso a
  sus datos pasa por un `ADMIN`.
- **Constitución, principio III**: la depuración solo puede habilitarse en un entorno
  que ofrezca la descarga mensual. Principio IV: alcance mínimo de `REPRESENTANTE`.

## Out of Scope

- PDF u otros formatos distintos de la tabla de texto.
- Envío del fichero por correo o cualquier entrega activa.
- Firma electrónica del fichero. La huella permite demostrar que un fichero no se ha
  alterado respecto a lo que generó el sistema, pero no es una firma con valor legal
  frente a terceros.
- Exportación de la ubicación.
- Exportación de inventario.
- Habilitar la depuración en ningún entorno: esta feature la hace **posible**, no la
  activa.
