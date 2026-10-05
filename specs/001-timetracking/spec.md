# Feature Specification: Registro horario de personal (timetracking)

**Feature Branch**: `001-timetracking`

**Created**: 2026-10-05

**Status**: Draft

**Input**: User description: "Módulo de fichaje de personal para Granatum. Un empleado ficha su entrada, inicia y termina varias pausas (comida, descanso, otro) y ficha su salida (puede no hacer uso de pausas y fichar únicamente como entrada y salida). Cada fichaje guarda la hora y, opcionalmente, la ubicación de entrada y de salida. Estados: EN_CURSO, CERRADO, INCOMPLETO (olvidó fichar la salida). Un empleado no puede tener dos fichajes en curso. Horas trabajadas = salida - entrada - pausas, calculadas al cerrar. Empleado: nombre, DNI/NIF, puesto, tipo de contrato (completa, parcial, por horas), fecha de alta, activo/inactivo. Correcciones: el empleado solicita corregir un fichaje cerrado con motivo y nuevos valores; un ENCARGADO o ADMIN la aprueba o rechaza; queda registro de quién y cuándo y el original se conserva. El empleado solo ve lo suyo; ENCARGADO y ADMIN ven todo. Las operaciones de fichaje pueden llegar con retraso desde la app móvil (sin conexión) y pueden reenviarse: reenviar la misma operación no debe duplicarla, y se conserva la hora real en que ocurrió y la hora en que llegó. Fuera de alcance: ausencias y vacaciones, geofencing, notificaciones, exportación. Éxito: un empleado completa una jornada con 2 pausas y las horas salen correctas; ningún fichaje cerrado se puede modificar salvo con una corrección aprobada."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Registrar una jornada completa (Priority: P1)

Una persona empleada abre la aplicación al llegar al trabajo y ficha su entrada.
A media mañana inicia una pausa de descanso y la termina al volver. Al mediodía
inicia la pausa de comida y la termina al reincorporarse. Al final del día ficha
su salida y el sistema le muestra las horas efectivamente trabajadas, ya
descontadas las pausas.

**Why this priority**: Es la razón de existir del módulo y la obligación legal
que cubre. Sin esto no hay registro de jornada, y cualquier otra historia
(correcciones, consultas, exportación) opera sobre datos que no existirían.

**Independent Test**: Se puede probar de principio a fin con una sola persona
empleada: fichar entrada, dos pausas y salida, y comprobar que las horas
trabajadas coinciden con el cálculo manual. Entrega valor por sí sola: ya
constituye un registro horario válido.

**Acceptance Scenarios**:

1. **Given** una persona empleada activa sin ningún fichaje en curso, **When** ficha su entrada, **Then** se crea un fichaje en estado `EN_CURSO` con la hora de entrada registrada.
2. **Given** un fichaje `EN_CURSO` sin pausas abiertas, **When** la persona inicia una pausa de tipo comida, **Then** queda registrada una pausa abierta de ese tipo.
3. **Given** un fichaje `EN_CURSO` con una pausa abierta, **When** la persona termina la pausa, **Then** la pausa queda cerrada con su duración.
4. **Given** un fichaje `EN_CURSO` con dos pausas cerradas que suman 1 hora, y una entrada a las 09:00, **When** la persona ficha su salida a las 18:00, **Then** el fichaje pasa a `CERRADO` y las horas trabajadas son 8 horas.
5. **Given** un fichaje `EN_CURSO` sin ninguna pausa, **When** la persona ficha su salida, **Then** las horas trabajadas son la diferencia íntegra entre salida y entrada.
6. **Given** una persona empleada con un fichaje ya `EN_CURSO`, **When** intenta fichar otra entrada, **Then** la operación se rechaza y se le indica que ya tiene una jornada abierta.
7. **Given** una persona empleada marcada como inactiva, **When** intenta fichar su entrada, **Then** la operación se rechaza.
8. **Given** un fichaje `EN_CURSO` con una pausa abierta, **When** la persona intenta iniciar una segunda pausa, **Then** la operación se rechaza: no puede haber dos pausas simultáneas.
9. **Given** un fichaje `EN_CURSO` con una pausa abierta, **When** la persona ficha su salida, **Then** la operación se rechaza y se le indica que debe cerrar la pausa antes de salir.

---

### User Story 2 - Corregir un fichaje sin destruir el original (Priority: P2)

Una persona empleada se dio cuenta de que ayer fichó su salida una hora más
tarde de lo que realmente se marchó. No puede editar el fichaje: solicita una
corrección indicando el valor correcto y el motivo. Su responsable recibe la
solicitud, la revisa y la aprueba. El fichaje refleja a partir de entonces el
valor corregido, pero el valor original sigue consultable, junto con quién
aprobó el cambio y cuándo.

**Why this priority**: Es el núcleo del cumplimiento del RD-ley 8/2019 y del
principio III de la constitución. Un registro que se puede editar no sirve como
prueba. Va después de P1 porque sin jornadas registradas no hay nada que
corregir.

**Independent Test**: Partiendo de un fichaje cerrado, se solicita una
corrección, se aprueba, y se comprueba que el valor vigente cambió, que el
original sigue recuperable y que consta la autoría y el instante de la
aprobación. Verificable sin tocar el resto del módulo.

**Acceptance Scenarios**:

1. **Given** un fichaje `CERRADO` propio, **When** la persona empleada solicita una corrección con motivo y nuevos valores, **Then** se crea una solicitud en estado `PENDIENTE`.
2. **Given** una solicitud `PENDIENTE`, **When** un `ENCARGADO` la aprueba, **Then** la solicitud pasa a `APROBADA`, queda registrado quién la aprobó y en qué instante, y el fichaje pasa a reflejar los valores corregidos.
3. **Given** una solicitud `PENDIENTE`, **When** un `ENCARGADO` la rechaza indicando el motivo, **Then** la solicitud pasa a `RECHAZADA` y el fichaje conserva sus valores sin cambio alguno.
4. **Given** una solicitud ya `APROBADA`, **When** cualquier persona intenta aprobarla o rechazarla de nuevo, **Then** la operación se rechaza.
5. **Given** un fichaje con una corrección aprobada, **When** se consulta su histórico, **Then** son recuperables tanto el valor original como el corregido, con la autoría y el instante de la aprobación.
6. **Given** un fichaje `CERRADO`, **When** se intenta modificarlo por cualquier vía distinta de una corrección aprobada, **Then** la operación se rechaza.
7. **Given** una solicitud creada por una persona con rol `EMPLEADO`, **When** esa misma persona intenta aprobarla, **Then** la operación se rechaza.
8. **Given** un fichaje `EN_CURSO`, **When** se solicita una corrección sobre él, **Then** la operación se rechaza: solo se corrigen fichajes ya finalizados.
9. **Given** un fichaje `INCOMPLETO` por una salida no registrada, **When** la persona solicita una corrección aportando la hora de salida y el motivo, y un `ENCARGADO` la aprueba, **Then** el fichaje queda completo, con sus horas trabajadas calculadas, y sigue siendo distinguible de una jornada cerrada con normalidad en su momento.
10. **Given** una solicitud de corrección que añade una pausa no registrada, **When** se aprueba, **Then** la pausa pasa a formar parte del fichaje y las horas trabajadas se recalculan descontándola.
11. **Given** una solicitud de corrección cuyos valores propuestos dejarían la salida antes de una pausa ya registrada, **When** se intenta aprobar, **Then** la aprobación se rechaza por incoherencia.
12. **Given** una solicitud de corrección que pretende alterar la ubicación de entrada, **When** se envía, **Then** la operación se rechaza: la ubicación no es corregible.

---

### User Story 3 - Consultar fichajes respetando la privacidad (Priority: P2)

Una persona empleada consulta sus propias jornadas de la última quincena para
comprobar sus horas. Su responsable consulta las de todo el equipo para revisar
la actividad del mes. Ninguna persona con rol `EMPLEADO` puede ver las jornadas
de otra, ni siquiera indicando su identificador.

**Why this priority**: Los datos de jornada son datos personales, y el acceso
indebido es a la vez un fallo de autorización y una brecha de protección de
datos. Es condición para que el módulo pueda usarse con datos reales.

**Independent Test**: Con dos personas empleadas y un responsable, comprobar que
cada empleada ve solo lo suyo, que el intento de leer lo de otra se rechaza, y
que el responsable ve todo.

**Acceptance Scenarios**:

1. **Given** una persona con rol `EMPLEADO`, **When** consulta sus propios fichajes en un rango de fechas, **Then** recibe únicamente sus fichajes de ese rango.
2. **Given** una persona con rol `EMPLEADO`, **When** intenta consultar los fichajes de otra persona, **Then** la operación se rechaza, con independencia del identificador que indique en la petición.
3. **Given** una persona con rol `ENCARGADO` o `ADMIN`, **When** consulta los fichajes de cualquier persona empleada, **Then** los recibe.
4. **Given** un rango de fechas sin fichajes, **When** se consulta, **Then** se devuelve un resultado vacío y no un error.

---

### User Story 4 - Fichar sin conexión y reenviar sin duplicar (Priority: P3)

Una persona empleada ficha su entrada en un almacén sin cobertura. La
aplicación guarda la operación y la envía cuando recupera conexión, media hora
más tarde. Si el envío falla a medias y la aplicación lo reintenta, la jornada
no se duplica. El registro conserva la hora real a la que la persona fichó, no
la hora a la que la operación llegó al servidor.

**Why this priority**: Es un requisito operativo real (almacenes, eventos,
desplazamientos) y afecta a la validez legal del registro, porque la hora que
cuenta es la del hecho. Va después de P1 y P2 porque el registro y las
correcciones son utilizables aunque de momento se exija conexión.

**Independent Test**: Enviar la misma operación dos veces con el mismo
identificador de operación y comprobar que existe un único fichaje, que la
segunda respuesta es equivalente a la primera, y que se conservan por separado
la hora del hecho y la hora de recepción.

**Acceptance Scenarios**:

1. **Given** una operación de fichaje con un identificador de operación concreto, **When** se envía por primera vez, **Then** se registra y se devuelve su resultado.
2. **Given** una operación ya registrada, **When** se reenvía con el mismo identificador de operación, **Then** se devuelve el resultado original sin crear ni modificar nada.
3. **Given** una operación realizada a las 08:00 y recibida a las 08:30, **When** se consulta el fichaje, **Then** la hora de la jornada es 08:00 y consta por separado que se recibió a las 08:30.
4. **Given** una operación cuya hora declarada está más de 5 minutos en el futuro, **When** se envía, **Then** se rechaza con un error de desviación de reloj, distinguible de un error de validación corriente.
5. **Given** una operación realizada hace 48 horas sin cobertura, **When** se envía, **Then** se acepta y la jornada consta con la hora del hecho.
6. **Given** una operación realizada hace 5 días, **When** se envía, **Then** se rechaza por exceder el margen de 72 horas.

---

### User Story 5 - Dar de alta y de baja personal (Priority: P3)

Una persona con rol `ADMIN` da de alta a una nueva incorporación con sus datos
laborales. Cuando alguien deja la empresa, la marca como inactiva: deja de poder
fichar, pero todo su histórico de jornadas se conserva.

**Why this priority**: Es condición previa para cualquier fichaje, pero su
lógica es sencilla y puede resolverse con un alta mínima mientras se desarrolla
P1.

**Independent Test**: Dar de alta a una persona, comprobar que puede fichar,
marcarla como inactiva, comprobar que ya no puede fichar y que su histórico
sigue consultable.

**Acceptance Scenarios**:

1. **Given** una persona con rol `ADMIN`, **When** da de alta a alguien con nombre, documento de identidad, puesto, tipo de contrato y fecha de alta, **Then** queda registrada como activa.
2. **Given** una persona con rol `ENCARGADO` o `EMPLEADO`, **When** intenta dar de alta o modificar personal, **Then** la operación se rechaza.
3. **Given** una persona empleada con fichajes registrados, **When** se la marca como inactiva, **Then** no puede fichar y su histórico sigue íntegro y consultable.
4. **Given** un documento de identidad ya registrado, **When** se intenta dar de alta a otra persona con el mismo documento, **Then** la operación se rechaza.
5. **Given** una persona empleada, **When** se intenta eliminarla, **Then** la operación se rechaza: la baja se representa marcándola como inactiva, nunca borrando.

---

### Edge Cases

- **Jornada que cruza la medianoche**: una persona entra a las 22:00 y sale a
  las 06:00. El fichaje es uno solo, se atribuye a la fecha de su entrada, y las
  horas trabajadas son 8.
- **Cambio de hora (horario de verano/invierno)**: una jornada que atraviesa el
  cambio de hora debe reflejar las horas realmente transcurridas, no la
  diferencia aparente entre las horas del reloj local.
- **Olvido de fichar la salida**: el proceso programado diario lo marca como
  `INCOMPLETO`, con lo que deja de bloquear nuevas jornadas, y solo puede
  completarse con una corrección aprobada.
- **Olvido de fichar la salida justo antes de unas vacaciones**: el fichaje pasa
  a `INCOMPLETO` en la siguiente ejecución del proceso, no al volver, así que el
  registro es correcto aunque la persona tarde semanas en reaparecer.
- **Varios fichajes `INCOMPLETO` acumulados**: no bloquean fichar; cada uno se
  completa con su propia corrección.
- **Fichaje diferido con el reloj del móvil adelantado**: si la hora declarada
  cae más de 5 minutos en el futuro, se rechaza con un error distinguible para
  que la aplicación pueda avisar de que el reloj está mal.
- **Fichaje offline de hace más de 72 horas**: se rechaza. La vía para
  registrarlo es que el responsable lo introduzca y quede como corrección.
- **Ubicación denegada o no disponible**: la persona puede tener desactivada la
  geolocalización o estar sin cobertura GPS. El fichaje se registra igualmente
  sin ubicación: la ubicación es un dato opcional y nunca un requisito para
  fichar.
- **Pausa abierta al cerrar la jornada**: no se puede fichar salida con una
  pausa sin terminar.
- **Pausa más larga que la jornada**: si las pausas sumasen más que el intervalo
  entre entrada y salida, las horas trabajadas no pueden ser negativas; la
  situación indica datos incoherentes y debe rechazarse al cerrar.
- **Fichaje con hora de salida anterior a la de entrada**: incoherente, se
  rechaza.
- **Reenvío de una operación sobre un fichaje ya cerrado**: el reenvío devuelve
  el resultado original sin reabrir nada.
- **Corrección que deja las pausas incoherentes**: una corrección que adelantase
  la salida por delante de una pausa ya registrada produciría datos imposibles;
  esos valores propuestos no son aprobables.

## Requirements *(mandatory)*

### Functional Requirements

**Registro de jornada**

- **FR-001**: El sistema MUST permitir a una persona empleada activa registrar su entrada, creando un fichaje en estado `EN_CURSO`.
- **FR-002**: El sistema MUST impedir que una persona empleada tenga más de un fichaje en estado `EN_CURSO` de forma simultánea.
- **FR-003**: El sistema MUST permitir registrar varias pausas dentro de un fichaje, cada una con su tipo (comida, descanso u otro), su inicio y su fin.
- **FR-004**: El sistema MUST impedir que haya más de una pausa abierta a la vez dentro del mismo fichaje.
- **FR-005**: El sistema MUST permitir cerrar un fichaje sin ninguna pausa registrada.
- **FR-006**: El sistema MUST impedir cerrar un fichaje que tenga una pausa sin terminar.
- **FR-007**: El sistema MUST calcular las horas trabajadas al cerrar el fichaje como el intervalo entre entrada y salida menos la suma de las pausas, y MUST rechazar el cierre si el resultado fuese negativo.
- **FR-008**: El sistema MUST registrar el instante de entrada y de salida de forma inequívoca en el tiempo, de modo que el cálculo de horas sea correcto en jornadas que cruzan la medianoche y en las que atraviesan un cambio de hora.
- **FR-009**: El sistema MUST aceptar opcionalmente una ubicación en la entrada y en la salida, y MUST registrar el fichaje con normalidad cuando la ubicación no se aporte.
- **FR-010**: El sistema MUST impedir fichar a una persona empleada marcada como inactiva.
- **FR-011**: El sistema MUST rechazar un fichaje cuya hora de salida sea anterior a su hora de entrada.
- **FR-012**: El sistema MUST marcar como `INCOMPLETO`, mediante un proceso programado que se ejecuta una vez al día a una hora fija configurable, todo fichaje que siga `EN_CURSO` y cuya entrada corresponda a un día anterior al de la ejecución.
- **FR-012a**: El sistema MUST permitir que una persona empleada vuelva a fichar entrada aunque arrastre fichajes en estado `INCOMPLETO`: un fichaje `INCOMPLETO` ya no cuenta como jornada en curso y por tanto no la bloquea.
- **FR-012b**: El sistema MUST permitir completar un fichaje `INCOMPLETO` únicamente a través del flujo de solicitud y aprobación de correcciones (FR-013 a FR-019), de modo que la hora de salida añadida a posteriori quede siempre acompañada de su motivo, de quién la aprobó y de cuándo.
- **FR-012c**: El sistema MUST conservar el estado `INCOMPLETO` como parte del histórico del fichaje una vez completado, de modo que sea distinguible de una jornada que se cerró con normalidad en su momento.

**Correcciones**

- **FR-013**: El sistema MUST permitir a una persona empleada solicitar la corrección de un fichaje ya finalizado, aportando obligatoriamente un motivo y los valores propuestos.
- **FR-014**: El sistema MUST mantener las solicitudes de corrección en uno de estos tres estados: `PENDIENTE`, `APROBADA` o `RECHAZADA`.
- **FR-015**: El sistema MUST permitir aprobar o rechazar una solicitud únicamente a personas con rol `ENCARGADO` o `ADMIN`.
- **FR-015a**: El sistema MUST impedir que quien creó una solicitud la resuelva, **con independencia de su rol**. Un `ENCARGADO` también tiene sus propios fichajes, así que podría corregir su jornada y aprobársela él mismo — que es exactamente la autoaprobación que el paso de aprobación existe para evitar. *(La redacción original de FR-015 solo lo impedía "cuando su rol es `EMPLEADO`", cláusula vacua: un `EMPLEADO` nunca llega a resolver. Detectado al implementar.)*
- **FR-016**: El sistema MUST registrar, al resolver una solicitud, quién la resolvió y en qué instante.
- **FR-017**: El sistema MUST conservar los valores originales del fichaje tras aplicar una corrección aprobada, de modo que sigan siendo consultables.
- **FR-018**: El sistema MUST impedir toda modificación o borrado de un fichaje finalizado que no proceda de una corrección aprobada.
- **FR-019**: El sistema MUST impedir resolver dos veces una misma solicitud.
- **FR-020**: El sistema MUST admitir como datos corregibles de un fichaje únicamente: su hora de entrada, su hora de salida y sus pausas, pudiendo una corrección añadir una pausa que no se registró, eliminar una que se registró por error y modificar el inicio o el fin de una existente.
- **FR-020a**: El sistema MUST impedir que una corrección altere la ubicación de entrada o de salida. La ubicación es evidencia de dónde se fichó y editarla la invalidaría como prueba.
- **FR-020b**: El sistema MUST aplicar a los valores corregidos las mismas reglas de coherencia que a un fichaje normal: salida posterior a la entrada, pausas sin solapamiento, pausas contenidas en el intervalo de la jornada y horas trabajadas no negativas. Una solicitud cuyos valores propuestos incumplan estas reglas MUST ser rechazable sin posibilidad de aprobación.

**Consulta y visibilidad**

- **FR-021**: El sistema MUST permitir consultar los fichajes de una persona empleada en un rango de fechas.
- **FR-022**: El sistema MUST restringir a las personas con rol `EMPLEADO` el acceso exclusivamente a sus propios fichajes y solicitudes, determinando la identidad a partir de la sesión autenticada y nunca de un identificador aportado en la petición.
- **FR-023**: El sistema MUST permitir a las personas con rol `ENCARGADO` y `ADMIN` consultar los fichajes de todo el personal.
- **FR-023a**: El sistema MUST permitir a una persona con rol `REPRESENTANTE` consultar, **en modo lectura**, la jornada registrada de cualquier persona de la plantilla. Es la vía por la que se satisface la puesta a disposición del registro a la representación legal de los trabajadores.
- **FR-023b**: El sistema MUST ocultar la ubicación de los fichajes a las personas con rol `REPRESENTANTE`. No es un dato exigido para la puesta a disposición y es el más intrusivo del registro, así que mostrarlo sería tratar datos personales sin obligación que lo sostenga.
- **FR-023c**: El sistema MUST impedir a `REPRESENTANTE` toda operación de escritura: no puede fichar, ni solicitar correcciones en nombre de nadie, ni aprobarlas o rechazarlas, ni gestionar personal, ni acceder al inventario.

**Operaciones diferidas e idempotencia**

- **FR-024**: El sistema MUST aceptar en cada operación de fichaje un identificador de operación generado por el cliente, y MUST tratar el reenvío de un identificador ya procesado devolviendo el resultado original sin crear ni alterar datos.
- **FR-025**: El sistema MUST registrar por separado el instante en que ocurrió el hecho y el instante en que la operación llegó al servidor, y MUST usar el primero para el cómputo de la jornada.
- **FR-026**: El sistema MUST rechazar operaciones cuya hora declarada por el dispositivo se sitúe más de 5 minutos en el futuro respecto al reloj del servidor. Una hora futura siempre indica un reloj desajustado o un intento de manipulación.
- **FR-026a**: El sistema MUST aceptar operaciones cuya hora declarada esté hasta 72 horas en el pasado, y rechazarlas más allá de ese plazo. El margen cubre un fin de semana largo sin cobertura sin abrir una ventana indefinida para declarar jornadas antiguas.
- **FR-026b**: Ambos límites MUST ser configurables por entorno, y el rechazo MUST distinguirse del resto de errores de validación para que la aplicación móvil pueda explicar a la persona que el reloj de su dispositivo está desajustado.

**Personal**

- **FR-027**: El sistema MUST permitir únicamente a personas con rol `ADMIN` dar de alta, modificar y desactivar personal.
- **FR-028**: El sistema MUST registrar de cada persona empleada: nombre, documento de identidad, puesto, tipo de contrato (jornada completa, parcial o por horas), fecha de alta y si está activa.
- **FR-029**: El sistema MUST impedir dar de alta a dos personas con el mismo documento de identidad.
- **FR-030**: El sistema MUST representar la baja de una persona empleada marcándola como inactiva, y MUST impedir su borrado, conservando íntegro su histórico de jornadas.

**Resumen mensual**

- **FR-032**: El sistema MUST poder producir, para una persona empleada y un mes natural, un resumen de su jornada: cada día con su entrada, su salida, sus pausas y sus horas trabajadas, más el total del mes.
- **FR-033**: El sistema MUST señalar en el resumen las jornadas completadas a posteriori y las que han sido objeto de una corrección aprobada, para que quien lo recibe sepa qué se reconstruyó y con qué autorización.
- **FR-034**: El sistema MUST reflejar en el resumen los valores vigentes tras las correcciones aprobadas, no los originales.
- **FR-035**: El sistema MUST producir el resumen de las personas con contrato a tiempo parcial con la periodicidad mensual que exige su entrega junto con el recibo de salarios. La **entrega** del documento queda fuera de este alcance y corresponde a la feature de exportación.

**Conservación y depuración**

- **FR-031**: El sistema MUST conservar los fichajes, sus pausas, sus eventos y sus correcciones durante **4 años contados desde la fecha de entrada de cada fichaje**.
- **FR-031a**: El sistema MUST impedir, **dentro de ese plazo y sin excepción**, el borrado de cualquier registro: no puede existir ninguna ruta de API, ningún rol ni ninguna operación manual capaz de borrarlo.
- **FR-031b**: El sistema MUST depurar los registros **una vez agotado por completo el plazo**, y MUST hacerlo exclusivamente desde un proceso automático de retención. Un registro al que le quede un solo día de plazo es intocable.
- **FR-031c**: El sistema MUST anotar cada ejecución de la depuración en un registro propio e inmutable —fechas alcanzadas, número de filas eliminadas e instante de ejecución— que no contiene datos personales y que no se depura nunca.
- **FR-031d**: El sistema MUST mantener la depuración deshabilitada mientras no esté disponible la descarga de los resúmenes mensuales. La base para poder destruir los registros es que hayan estado a disposición antes.

### Key Entities *(include if feature involves data)*

- **Empleado**: persona cuya jornada se registra. Nombre, documento de identidad (único), puesto, tipo de contrato, fecha de alta y situación activa/inactiva. Una persona empleada tiene muchos fichajes.
- **Fichaje**: una jornada de trabajo. Instante de entrada y, cuando termina, de salida; ubicación opcional en cada uno; estado (`EN_CURSO`, `CERRADO`, `INCOMPLETO`); horas trabajadas una vez cerrada. Pertenece a una persona empleada y agrupa sus pausas.
- **Pausa**: una interrupción dentro de un fichaje. Tipo (comida, descanso, otro), instante de inicio y de fin. Pertenece a un único fichaje.
- **SolicitudCorreccionFichaje**: petición de modificar un fichaje finalizado. Motivo, valores propuestos, estado (`PENDIENTE`, `APROBADA`, `RECHAZADA`), quién la solicitó, quién la resolvió y cuándo. Referencia al fichaje afectado y conserva sus valores originales.
- **RegistroDeOperacion**: constancia inmutable de cada operación recibida (entrada, inicio o fin de pausa, salida), con el instante en que ocurrió, el instante en que llegó y el identificador de operación que aportó el cliente. Nunca se modifica ni se borra. Es la prueba documental del registro horario —lo que la persona fichó y cuándo— frente al estado agregado de la jornada, y es además lo que permite que reenviar una operación no la duplique.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Una persona empleada completa una jornada con dos pausas y las horas trabajadas que ve coinciden exactamente con el cálculo manual (entrada a salida, menos pausas), sin desviación de ningún minuto.
- **SC-002**: Ningún fichaje finalizado cambia de valor sin una corrección aprobada: el 100% de los intentos de modificación por cualquier otra vía se rechazan.
- **SC-003**: Tras aplicar una corrección, el valor original del fichaje sigue siendo recuperable en el 100% de los casos, junto con la identidad de quien aprobó y el instante de la aprobación.
- **SC-004**: Ninguna persona con rol `EMPLEADO` logra acceder a datos de jornada de otra persona: el 100% de los intentos se rechazan, incluso indicando explícitamente el identificador ajeno.
- **SC-005**: Reenviar una operación de fichaje ya procesada no produce jornadas duplicadas en el 100% de los reintentos, y la respuesta es equivalente a la original.
- **SC-006**: Una jornada que cruza la medianoche o un cambio de hora arroja las horas realmente transcurridas, verificable contra el cálculo manual.
- **SC-007**: Una persona empleada puede registrar su entrada o su salida en menos de 5 segundos desde que lo solicita, incluso cuando la operación se envía en diferido al recuperar conexión.
- **SC-008**: Una persona responsable obtiene los fichajes de todo el personal de un mes natural en menos de 3 segundos.
- **SC-009**: Ningún fichaje permanece en estado `EN_CURSO` más allá del día siguiente a su entrada: tras la ejecución diaria del proceso, el 100% de los fichajes abiertos de días anteriores están marcados como `INCOMPLETO`.
- **SC-010**: Un fichaje completado a posteriori es siempre distinguible de uno cerrado en su momento, de modo que un informe para la Inspección de Trabajo puede señalar qué jornadas se reconstruyeron y con qué autorización.
- **SC-011**: Una persona con rol `REPRESENTANTE` obtiene la jornada registrada de cualquier persona de la plantilla **sin ninguna ubicación**, y el 100% de sus intentos de escritura —fichar, solicitar o resolver correcciones, gestionar personal— se rechazan.
- **SC-012**: Ningún registro dentro del plazo de conservación se puede borrar: el 100% de los intentos por cualquier vía se rechazan, incluido el rol `ADMIN`.
- **SC-013**: Agotado el plazo, la depuración elimina los registros vencidos y **solo** esos, y cada ejecución queda anotada de forma recuperable con el número de filas eliminadas.
- **SC-014**: El resumen mensual de una persona cuadra con la suma de sus jornadas de ese mes, y señala cuáles se reconstruyeron o corrigieron.

## Assumptions

Decisiones tomadas ante detalles no especificados, derivadas del contexto del
proyecto o de la práctica habitual del dominio. **Las cinco primeras se
propusieron como valor por defecto y fueron confirmadas expresamente por el
responsable del producto el 2026-10-05**, así que son decisiones firmes y no
suposiciones pendientes de validar:

- **La ubicación es siempre opcional.** Si la persona deniega el permiso de
  geolocalización o no hay señal, el fichaje se registra sin ubicación. La
  alternativa (bloquear el fichaje) impediría cumplir la obligación legal de
  registrar la jornada por un motivo accesorio, y el geofencing está
  explícitamente fuera de alcance.
- **Una jornada que cruza la medianoche es un único fichaje**, atribuido a la
  fecha de su entrada. No se parte en dos.
- **No puede haber dos pausas abiertas a la vez** dentro de un fichaje, por
  simetría con la regla de "un solo fichaje en curso" y porque dos pausas
  simultáneas no tienen sentido físico. Las pausas de un mismo fichaje no se
  solapan.
- **Solo se corrigen fichajes finalizados.** Un fichaje `EN_CURSO` se arregla
  continuando la jornada con normalidad, no con una solicitud de corrección. Un
  fichaje `INCOMPLETO` sí es corregible: es justamente su vía de arreglo.
- **El paso a `INCOMPLETO` es automático y diario**, no depende de que nadie lo
  revise. Decisión tomada en la aclaración de FR-012: un fichaje que miente
  durante semanas porque la persona está de vacaciones es un problema legal, no
  una molestia de usabilidad.
- **La ubicación no es corregible.** Decisión tomada en la aclaración de FR-020.
- **La tolerancia de reloj es asimétrica** (5 minutos hacia el futuro, 72 horas
  hacia el pasado). Decisión tomada en la aclaración de FR-026: un fichaje con
  fecha futura siempre es incorrecto, mientras que uno con fecha pasada puede
  ser perfectamente legítimo si el dispositivo estuvo sin cobertura.
- **El alta de personal es competencia exclusiva de `ADMIN`**, coherente con el
  principio IV de la constitución (`ENCARGADO` gestiona inventario y aprueba
  correcciones, no personal).
- **La conservación es de 4 años**, el plazo que fija el principio III de la
  constitución para el RD-ley 8/2019.
- **Las personas empleadas se identifican por su sesión autenticada.** Este
  módulo asume que existe un mecanismo de autenticación que aporta la identidad
  y el rol; el inicio de sesión real es una feature aparte (ver Dependencias).
- **Una corrección aprobada se aplica de inmediato.** No hay estado intermedio
  de "aprobada pero no aplicada".

## Dependencies

- **Autenticación e identidad**: este módulo necesita conocer la persona
  autenticada y su rol. Hoy el proyecto solo dispone de un emisor de tokens de
  desarrollo; el inicio de sesión real es una feature independiente y es
  requisito para explotar este módulo en producción.
- **Roles compartidos**: usa los roles `ADMIN`, `ENCARGADO` y `EMPLEADO` ya
  definidos en el módulo común del proyecto.
- **La exportación de jornadas queda fuera de este alcance** y se especifica
  como feature aparte, pero depende de las entidades que aquí se definen.
- **El resumen mensual se reparte entre dos features, a propósito.** Su
  **cálculo** vive aquí (FR-032 a FR-035), porque opera sobre los fichajes de
  este módulo y aplica sus reglas de dominio: qué cuenta como tiempo trabajado,
  cómo influyen las correcciones aprobadas, qué jornadas se reconstruyeron. Su
  **entrega** —descarga, formato de fichero, periodicidad de envío— vive en la
  feature de exportación. Poner el formato CSV aquí duplicaría lo que esa
  feature ya posee; poner el cálculo allí haría que el módulo de exportación
  tuviese que conocer las reglas de jornada, que es el acoplamiento peor de los
  dos.
- **La depuración a los 4 años depende de esa entrega** (FR-031d): no puede
  habilitarse hasta que la descarga mensual exista. Mientras tanto no se borra
  nada, que es el lado seguro del incumplimiento.

## Out of Scope

- Ausencias, vacaciones y permisos.
- Geofencing o validación de que la ubicación del fichaje está dentro de un
  recinto permitido.
- Notificaciones (avisos de fichaje olvidado, de solicitud pendiente, etc.).
- Exportación de los registros a cualquier formato.
- Cuadrantes, turnos previstos y comparación de jornada real frente a prevista.
- Cálculo de horas extra, recargos o nóminas.
