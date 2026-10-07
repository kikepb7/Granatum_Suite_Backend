# Research: Exportación del registro de jornada

**Fecha**: 2026-10-06 | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Decisiones técnicas de la feature, cada una con su porqué y lo que se descartó.
Las que vienen de una lección de las features 001 y 002 lo dicen, porque esa
lección es justo la razón de la decisión.

---

## D-001: Vive en `timetracking`, sin depender de `auth`

**Decisión**: todo el código —exportación, descarga mensual, registro de
exportaciones, verificación— va en el módulo `timetracking`.

**Motivo**: es el dueño de todos los datos que se exportan: fichajes, pausas,
correcciones y la ficha de la persona (nombre, documento, puesto, tipo de
contrato). Los nombres de quién solicitó y quién aprobó una corrección también
están en `empleados`, porque toda cuenta de acceso apunta a una persona de la
plantilla y el sujeto del token **es** el id de esa persona (decisión de la
feature 001). La identidad y el rol de quien exporta llegan por el token, que
interpreta `JwtAuthFilter` en `common`. No hace falta nada de `auth`, así que el
principio I se cumple sin contrato nuevo.

---

## D-002: Streaming dentro de una sola transacción de lectura con instantánea

**Decisión**: la respuesta es un `StreamingResponseBody`. Dentro, un
`TransactionTemplate` abre **una** transacción de solo lectura con aislamiento
`REPEATABLE READ` y recorre los datos persona a persona, en lotes de 500 fichajes
con paginación por clave (`empleado_id`, `entrada`, `id`), escribiendo cada fila
según se lee.

**Motivo**:

- *Una sola instantánea.* En Postgres, `REPEATABLE READ` es aislamiento por
  instantánea: todas las lecturas ven la base de datos tal como estaba al empezar.
  Con una transacción por lote, una corrección aprobada a mitad de exportación
  haría que el fichero mezclara estados anteriores y posteriores. Además rompería
  FR-011/SC-008: dos exportaciones "sin cambios entre medias" no serían
  comparables si cada una ve un momento distinto.
- *Streaming de verdad.* Lo pediste, y la escala lo justifica: un año de 100
  personas son ~25.000 filas, pero cuatro años de toda la plantilla pueden pasar
  de 100.000. Montar eso en memoria por cada exportación simultánea es arriesgado.
- *Paginación por clave y no por `OFFSET`*: el coste de `OFFSET` crece con cada
  página, y el índice existente `idx_fichajes_empleado_entrada (empleado_id,
  entrada)` sirve exactamente este recorrido.

**Coste aceptado y cómo se acota**: la conexión a la base de datos queda ocupada
mientras se escriben bytes al socket, así que un cliente lento la retiene. Se
acota de dos formas:

1. Un semáforo limita las exportaciones simultáneas (`timetracking.exportacion.concurrencia`,
   2 por defecto). Pasada la espera, `503` con `Retry-After`. Es el mismo patrón que
   `VerificadorAcotado` en `auth`.
2. Qué libera la conexión cuando un cliente deja de leer **lo fija y lo demuestra
   `ClienteLentoExportacionIT`**, no esta investigación. La primera versión de este
   punto decía que el tiempo máximo de transacción (`timeout-segundos`) lo
   resolvía, y `/speckit-analyze` vio que no: ese tiempo solo se aplica a las
   consultas, y un hilo bloqueado escribiendo al socket no lanza ninguna. Los
   candidatos eran el tiempo máximo de la petición asíncrona (T001), el de escritura
   del servidor, o un corte explícito en la salida.

   **Resultado medido (2026-10-07)**: lo que libera la conexión es el tiempo de
   **escritura** de Tomcat, que es `server.tomcat.connection-timeout`. Con su valor
   implícito de 60 s, un cliente parado retuvo la conexión **65 s**. Ahora se fija
   explícitamente en `application.yml` (`SERVER_TOMCAT_CONNECTION_TIMEOUT`, 10 s
   por defecto), y con él la conexión vuelve en **15,5 s**. La escritura bloqueada
   falla, la exportación se anota como interrumpida y la conexión vuelve al pool.
   El tope de una exportación es `timeout-segundos` (corta toda consulta posterior)
   más `connection-timeout` (corta una escritura bloqueada). Un corte explícito en
   la salida no añadiría nada: una escritura bloqueada no vuelve para comprobar
   ningún plazo.

Una exportación ocupa **una** conexión y nunca espera una segunda (D-003), así
que no puede producir el interbloqueo del pool que apareció en `auth`; como mucho
reduce la capacidad mientras dura.

**Alternativas descartadas**:

| Opción | Por qué no |
|---|---|
| Montar el CSV en memoria y enviarlo | Seguro y sencillo a esta escala, pero cuatro años de plantilla por exportación simultánea es memoria sin techo; y no es lo que pediste. |
| Una transacción por lote | No hay instantánea común; rompe FR-011 y puede mezclar estados de una misma jornada. |
| Fichero temporal y luego enviarlo | Añade disco, limpieza y un fichero con DNI en el servidor, para resolver un problema que el semáforo ya acota. |

---

## D-003: El registro de la exportación se escribe **después** de la transacción de lectura

**Decisión**: la fila del registro de exportaciones se inserta cuando la
transacción de lectura ya ha terminado, en una transacción propia. Nunca anidada.

**Motivo**: es la lección más cara de la feature 002. Allí, escribir un evento en
una transacción `REQUIRES_NEW` mientras otra seguía abierta hacía que cada
petición retuviera **dos** conexiones, y con el pool lleno las peticiones se
bloqueaban entre sí hasta el timeout. Aquí la transacción de lectura dura toda la
exportación; una inserción anidada dentro tendría el mismo efecto, y peor, porque
dura más.

**Inserción única, nunca actualización** (FR-027): el registro es inalterable,
así que no puede existir una fila "en curso" que luego se complete. Se inserta una
sola vez al final:

- exportación completa: con número de filas y huella;
- exportación interrumpida (el cliente cortó, timeout): con `completada = false`,
  las filas escritas hasta el corte y sin huella, porque la huella de un fichero
  a medias no identifica nada que alguien tenga.

Así ningún intento de exportación queda sin anotar y ninguna fila se modifica
nunca.

---

## D-004: Formato del fichero

**Decisión**:

| Aspecto | Valor | Por qué |
|---|---|---|
| Codificación | UTF-8 **con BOM** | Sin BOM, Excel en Windows abre el fichero como ANSI y estropea tildes y eñes (FR-008). Lo pediste. |
| Separador | `;` | Excel con configuración regional española usa `;` como separador de listas, porque `,` es el separador decimal. Con `,`, todo aparecería en la columna A. Lo pediste. |
| Fin de línea | CRLF | RFC 4180, y lo que Excel espera. |
| Entrecomillado | RFC 4180: comillas dobles si el valor contiene `;`, `"`, CR o LF; `"` interiores duplicadas | FR-010: el mismo número de columnas en todas las filas aunque un motivo tenga punto y coma o un salto de línea. |
| Fechas | `yyyy-MM-dd` | ISO 8601. Excel en español lo reconoce como fecha, y no tiene la ambigüedad de `05/10/2026`, que es el 5 de octubre aquí y el 10 de mayo si el fichero se abre con configuración de EE. UU. |
| Entrada y salida | `yyyy-MM-dd HH:mm` | Con fecha, porque un fichaje que cruza la medianoche sale al día siguiente, y `06:00` a secas mentiría. |
| Horas trabajadas | `H:MM` (`8:30`) | Legible, y Excel lo reconoce como duración. |
| Minutos trabajados | Entero | Columna extra: quien procese el fichero con un programa necesita un número, no un texto `H:MM`. |
| Zona | `Europe/Madrid` | FR-003. Las horas se calculan sobre instantes (`CalculadoraJornada`), así que el día de 23 o de 25 horas suma bien; solo la presentación pasa a hora local. |

**Columnas, en este orden**: Persona; Documento*; Puesto; Fecha; Entrada; Salida;
Pausas; Horas trabajadas; Minutos trabajados; Estado; Completado a posteriori;
Corregido; Entrada original; Salida original; Pausas originales; Correcciones.

\* Omitida —la columna entera, no vacía— cuando exporta `REPRESENTANTE` (D-012).

---

## D-005: Ninguna celda se ejecuta como fórmula

**Decisión**: toda celda que empiece por `=`, `+`, `-`, `@`, tabulador o retorno
de carro se escribe con un apóstrofo delante y entrecomillada.

**Motivo**: FR-009 y SC-006. Es la mitigación de OWASP para la inyección en CSV.
Un nombre o un motivo de corrección que empiece por `=` se ejecutaría como
fórmula al abrir el fichero en Excel, y estos ficheros van a terceros: la
Inspección, la representación legal. El apóstrofo hace que Excel lo trate como
texto.

**Sin falsos positivos**: ningún valor legítimo del fichero empieza por esos
caracteres. No hay números negativos, las fechas empiezan por un dígito y las
horas también. Solo los textos libres —nombre, puesto, motivos— pueden
dispararlo, que es justo donde está el riesgo. Hay un test que lo comprueba sobre
todos los formatos de valor.

---

## D-006: Huella SHA-256 sobre los bytes exactos

**Decisión**: SHA-256 de los bytes exactos que salen por la respuesta, BOM
incluido, calculado al vuelo con un `DigestOutputStream` y guardado en
hexadecimal.

**Motivo**: FR-025 y FR-028. La huella tiene que identificar el fichero tal como
lo recibió quien lo descargó, así que se calcula sobre lo que de verdad se envió
y no sobre una representación intermedia. Calcularla al vuelo evita guardar el
fichero, que contiene documentos de identidad.

**Por qué no es única**: por diseño, dos exportaciones idénticas producen la
misma huella (FR-011). La verificación devuelve **todas** las exportaciones que
coinciden, y eso es información útil: muestra quién más obtuvo exactamente ese
fichero.

**Lo que no es**: una firma. Demuestra que un fichero no cambió respecto a lo que
generó el sistema, siempre que se confíe en el propio sistema. No sirve frente a
terceros que no confíen en él. La spec deja la firma electrónica fuera de alcance.

---

## D-007: La verificación recibe el fichero como cuerpo sin procesar

**Decisión**: `POST /api/exportaciones/verificar` con el fichero como cuerpo
`text/csv` o `application/octet-stream`, no `multipart/form-data`. La huella se
calcula mientras se lee y el contenido se descarta; con un tope de 100 MB.

**Motivo**:

- `multipart` tiene en Spring un límite por defecto de 1 MB, y una exportación de
  plantilla lo supera enseguida. Subirlo significaría cambiar un límite global que
  afecta a toda la aplicación.
- Leer el cuerpo como flujo permite calcular la huella sin guardar nunca el
  contenido, que lleva documentos de identidad.
- El tope evita que un cuerpo infinito ocupe un hilo indefinidamente. Es un
  endpoint de `ADMIN`, pero no hay motivo para que no esté acotado.

**El contenido no se registra nunca**: ni el cuerpo ni un fragmento. La lección de
la feature 002 es que, en `DEBUG`, Spring MVC registra los cuerpos de petición
deserializados. Un `InputStream` no se deserializa ni se registra; y el test de
logs lo comprueba (D-017).

---

## D-008: Orden estable y determinista

**Decisión**: primero se cargan las personas del alcance, que son pocas, y se
ordenan en Kotlin por nombre con un `Collator` español y luego por id. Después se
recorren sus fichajes persona a persona por (`entrada`, `id`).

**Motivo**: FR-011 exige que el mismo alcance y rango, sin cambios entre medias,
produzcan el mismo fichero byte a byte, y que el orden sea "por persona".

- Ordenar por nombre en la base de datos depende de la *collation* del servidor,
  que no es la misma en Docker local que en Supabase. La misma consulta podría
  ordenar `Ángel` antes o después de `Zoe` según el entorno.
- Ordenar por id en lugar de por nombre sería determinista pero inútil para quien
  lee el fichero.
- Un `Collator` español en la JVM es estable entre entornos con la misma versión
  de JDK, ordena las tildes como espera una persona y desempata por id cuando dos
  personas se llaman igual.

---

## D-009: El límite del plazo de conservación, en el nombre del fichero y en una cabecera

**Decisión**: el rango pedido se recorta por abajo al primer día que todavía puede
tener datos según el plazo de conservación. El rango efectivo va en el nombre del
fichero y en la cabecera `X-Registro-Disponible-Desde`. El contenido de la tabla no
cambia.

**Motivo**: FR-017 pide que un fichero vacío no se lea como "no trabajó" cuando en
realidad los datos ya se depuraron. Hay tres sitios donde ponerlo:

| Dónde | Problema |
|---|---|
| Una línea de texto dentro del CSV | Rompe la lectura como tabla, y la spec ya decidió que los metadatos no van dentro. |
| Solo una cabecera HTTP | Quien abre el fichero en Excel no la ve nunca. |
| **El nombre del fichero** | Es lo primero que ve quien lo abre, y no toca la tabla. |

Se usan el nombre **y** la cabecera: el primero para personas, la segunda para
programas.

**El límite se calcula en un solo sitio**: un componente `PlazoConservacion`
compartido con `DepuracionRetencionJob`, que hoy lo calcula por su cuenta
(`timetracking.retencion.anios`, 4). Si se calculara dos veces, cambiar el plazo en
un sitio y no en el otro haría que la exportación anunciara datos ya depurados o
escondiera datos que existen.

---

## D-010: Valores originales y columna de correcciones

**Decisión**:

- **Valores originales** (FR-004): los `valoresOriginales` de la **primera**
  corrección aprobada del fichaje. Cada aprobación guarda los valores que tenía el
  fichaje justo antes de aplicarse, así que la primera guarda los que se fichó de
  verdad; las siguientes guardan valores ya corregidos.
- **Correcciones** (FR-005): todas las aprobadas, por orden cronológico, en una
  celda: `2026-10-07 09:12 solicitada por Ana Pérez, aprobada por Luis Gil | …`.
  Sin el motivo: no lo pide la spec y es texto libre, que puede contener cualquier
  dato personal.
- Las correcciones **pendientes** o **rechazadas** no aparecen: no están aplicadas.

**Cargadas en lote**: las correcciones aprobadas de cada lote de 500 fichajes en
una consulta (`fichaje_id IN (...) AND estado = 'APROBADA'`, servida por el índice
`idx_solicitudes_fichaje_estado`), y los nombres de solicitantes y resolutores en
otra. Nunca una consulta por fila.

---

## D-011: La descarga mensual reutiliza el cálculo del resumen, y se arregla un fallo en él

**Decisión**: la descarga mensual calcula el total con `CalculadoraResumenMensual`,
la misma función que ya produce el resumen en pantalla, y escribe las filas con el
mismo escritor que la exportación por rango, más tres filas finales: total del mes,
tipo de contrato y si el mes está cerrado.

**Motivo**: FR-020 y SC-003 exigen que el total coincida **siempre** con el del
resumen en pantalla. La única forma de garantizarlo es que salga de la misma
función; reimplementarlo es exactamente cómo dos totales acaban divergiendo.

**Fallo encontrado en el código existente**: `FichajeService.resumenMensual` decide
qué días están corregidos cargando
`solicitudRepository.findAllByEstado(APROBADA)`: **todas las correcciones aprobadas
de la tabla, de todos los tiempos y de todas las personas**, para cada resumen. Su
comentario dice "una consulta sobre el mes", pero la consulta no está acotada al
mes. Es una lectura que crece con los cuatro años de historia, y la descarga
mensual de toda la plantilla la repetiría una vez por persona.

Se corrige en esta feature, porque está en el camino que FR-020 reutiliza: la
consulta pasa a acotarse a los fichajes del mes (`fichaje_id IN (...)`). Sin cambio
de comportamiento, y con un test que cuenta las consultas.

---

## D-012: `REPRESENTANTE` recibe el fichero sin la columna del documento

**Decisión**: para `REPRESENTANTE` la columna *Documento* se omite entera, no se
deja vacía. La ubicación no sale para nadie (FR-007).

**Motivo**: FR-012, confirmado por el responsable del producto. Una columna vacía
con cabecera "Documento" sugiere que el dato existe y falta. Omitirla deja el
fichero honesto sobre lo que contiene. El rol con el que se generó cada exportación
queda en el registro (D-013), así que siempre se sabe qué versión recibió cada
persona.

---

## D-013: Registro de exportaciones y su conservación

**Decisión**: tabla `exportaciones` (migración **V15**), solo inserción, con un
repositorio estrecho. La depuración a los 4 años la incluye: se borran las
exportaciones cuyo rango termina antes del corte. Para dejarlo anotado, **V16** añade
la columna `exportaciones_eliminadas` a `depuraciones_retencion`.

**Motivo**:

- Solo inserción y repositorio que extiende `Repository<T, ID>` sin `delete`: el
  último punto del principio III y la deuda nº 2 de la constitución, ya cerrada
  con este mismo patrón.
- El registro existe para demostrar que un mes estuvo a disposición antes de
  destruirlo. Una vez destruido el mes no queda nada que demostrar sobre él, y
  conservar el registro sin plazo sería el mismo problema del art. 5.1.e del RGPD.
  Así lo dejó la spec en *Assumptions*.
- **V16 añade una columna a una tabla existente.** No edita V11: el principio II
  prohíbe editar una migración ya aplicada, y V11 lo está en `main`. La columna
  lleva `DEFAULT 0`, para que las filas de depuraciones anteriores, que no borraban
  exportaciones, sigan siendo ciertas.
- Numeración global: `auth` llega a V14, así que esta feature empieza en V15.

---

## D-014: Rutas y autorización

| Método | Ruta | Quién | Notas |
|---|---|---|---|
| `GET` | `/api/fichajes/export?formato=csv&desde=&hasta=[&empleadoId=]` | todos los roles de lectura | `EMPLEADO`: sin `empleadoId` o con el suyo; con otro, `403`. Resto: sin `empleadoId` es la plantilla. |
| `GET` | `/api/fichajes/empleado/{empleadoId}/resumen/descarga?anio=&mes=` | la propia persona, `ENCARGADO`, `ADMIN`, `REPRESENTANTE` | Junto al resumen en pantalla que ya existe, con las mismas reglas de propiedad. |
| `GET` | `/api/exportaciones?empleadoId=&desde=&hasta=` | `ADMIN` | FR-029. |
| `POST` | `/api/exportaciones/verificar` | `ADMIN` | FR-028; cuerpo sin procesar (D-007). |

**Por qué `/api/exportaciones` y no bajo `/api/fichajes`**: la regla actual de
`SecurityConfig` deja hacer `POST` en `/api/fichajes/**` a `EMPLEADO`. Meter ahí la
verificación obligaría a una excepción dentro de esa regla. Una ruta propia con
`hasRole("ADMIN")` declara la autorización en `SecurityConfig` (principio IV) y no
depende de que el servicio se acuerde de comprobarla.

**La propiedad se comprueba contra el token**, nunca contra el parámetro
(principio IV). Para `EMPLEADO`, `empleadoId` solo puede coincidir con su propio
sujeto; si no, `403`, igual que el resumen mensual existente.

---

## D-015: `formato` acepta solo `csv`

**Decisión**: el parámetro existe, su valor por defecto es `csv`, y cualquier
otro valor responde `400`.

**Motivo**: lo pediste en la ruta, y deja sitio a otro formato sin cambiar el
contrato. Aceptar `pdf` y devolver CSV sería mentir; ignorarlo en silencio,
también.

---

## D-016: Dónde vive cada test

| Test | Módulo | Por qué ahí |
|---|---|---|
| Escritor CSV: entrecomillado, inyección de fórmulas, BOM, CRLF, formato de horas, determinismo | `timetracking` (unitario) | Lógica pura, sin Spring ni base de datos (principio V). |
| Contenido contra Postgres real: valores vigentes y originales, correcciones, orden, recorte por conservación, día de cambio de hora, personas dadas de baja | `timetracking` (Testcontainers) | Necesita las migraciones y los datos reales. |
| Total mensual igual al del resumen; consulta de correcciones acotada (contando consultas) | `timetracking` (Testcontainers) | D-011. |
| Registro de exportaciones, completas e interrumpidas; RLS de las tablas nuevas | `timetracking` (Testcontainers) | |
| Bytes reales por HTTP, permisos por rol, `REPRESENTANTE` sin documento, verificación de un fichero alterado | **`app`** | Solo `app` tiene `SecurityConfig` y la cadena de filtros real. |
| Ninguna línea de log con el DNI ni el nombre durante una exportación y una verificación | **`app`** | Por HTTP real: es la lección de la feature 002. |

---

## D-017: Higiene de logs

**Decisión**:

- El contenido del fichero no se registra nunca. `StreamingResponseBody` no lo
  registra Spring MVC; la verificación lee un `InputStream`, que no se deserializa
  (D-007).
- El nombre del fichero usa el id de la persona y las fechas, **nunca el nombre**:
  la cabecera `Content-Disposition` puede acabar en un log de acceso, y el nombre
  es un dato personal.
- El registro de exportaciones guarda solo identificadores, rango, recuento y
  huella (FR-026).
- Los mensajes de error no citan ni el documento ni el nombre.

**Motivo**: principio VI. Y la tarea abierta sobre los DTO de `timetracking` que
escriben el DNI en los logs en `DEBUG` deja claro que este módulo ya tiene esa
clase de fuga. Esta feature no debe añadir otra.

---

## D-018: Rendimiento (SC-009)

**Decisión**: medir en el quickstart un año completo de 100 personas sintéticas
contra Postgres real. Objetivo: menos de 30 s.

**Estimación**: ~25.000 fichajes en lotes de 500 son ~50 lotes con 4 consultas
cada uno (ids por clave, fichajes con pausas, correcciones, nombres). Unas 200 consultas
indexadas: el tiempo debería estar dominado por escribir los bytes, no por la base
de datos. Si la medición lo desmiente, el primer sitio que mirar es el tamaño del
lote.

**Medición real (2026-10-07, quickstart apartado 9)**: 100 personas sintéticas con
un año de jornadas laborables (26.100 fichajes), exportación de la plantilla
entera por HTTP contra Postgres 16 en Docker, en un Mac de 10 núcleos: **0,70 s,
0,73 s y 0,84 s** en tres ejecuciones, 2,8 MB. Unas cuarenta veces por debajo del
objetivo de SC-009, así que el tamaño de lote no necesita ajuste.

---

## D-019: Ningún handler producía `VALIDACION` (hueco encontrado al planificar)

**Decisión**: añadir a `CommonExceptionHandler` (en `common`) el tratamiento de los
errores de validación y de parámetros del propio framework —
`MethodArgumentNotValidException`, `HandlerMethodValidationException`,
`MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException` y
`HttpMessageNotReadableException`— para que respondan
`400 { "code": "VALIDACION", "message": … }`. El mensaje nombra el campo o el
parámetro y **nunca el valor rechazado**.

**Motivo**: lo encontré al redactar el contrato de esta feature, que, como el de
`auth`, promete un `400 VALIDACION`. Lo comprobé contra la aplicación en marcha:

```
POST /api/auth/login {"email":"no-es-correo",...}
→ 400 {"timestamp":…,"status":400,"error":"Bad Request",
       "trace":"org.springframework.web.bind.MethodArgumentNotValidException: …
                rejected value [no-es-correo] …", …}

GET /api/fichajes/empleado/{id}/resumen        (sin anio ni mes)
→ 400 {…,"trace":"…","message":"Required parameter 'anio' is not present.",…}
```

Ninguna clase del producto produce `VALIDACION`. Los `400` de validación salen con
el cuerpo por defecto de Spring:

- **En todos los entornos** incumple el principio VIII: no tiene el formato
  `{code, message}` que los clientes consumen.
- **En `dev`**, devtools añade la traza completa y el **valor rechazado**. Con un
  correo mal formado, el correo vuelve en la respuesta; con una contraseña nueva de
  más de 128 caracteres en `change-password`, la contraseña volvería en claro. En
  `prod` no hay devtools y no sale la traza, pero el formato sigue mal.
- Los tests de `auth` comprobaban solo el código de estado `400`, no el cuerpo. Por
  eso pasaron.

**Por qué en esta feature y no en la de endurecimiento**: el contrato de esta
feature no puede ser verdad sin ello, y el de `auth`, ya en `main`, promete algo que
no existe. Es un cambio pequeño en `common`, sin conflicto de precedencia: son
excepciones del framework, no subclases de las de los módulos, así que los
`@Order(HIGHEST_PRECEDENCE)` de `timetracking` y `auth` no compiten con él.

**Tests**: en `app`, por HTTP real, una petición mal formada por cada familia de
rutas (inventario, fichajes, auth, exportación) debe devolver `code: VALIDACION`, y
el cuerpo no debe contener el valor enviado. Así el hueco no puede volver a pasar
por un test que solo mira el código de estado.
