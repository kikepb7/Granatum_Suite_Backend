# Arquitectura

## Idea general

Monorepo Gradle multi-módulo: cada módulo compila y testea por separado pero
todos comparten el namespace `com.granatum.core`, porque forman parte de un
único producto. Adaptado de
[Spring_Boot_Skeleton](https://github.com/kikepb7/Spring_Boot_Skeleton), que a
su vez está extraído de [Squadfy_Backend](https://github.com/kikepb7/squadfy_backend).

```
                       ┌─────────────┐
                       │     app     │  ← runnable, agrega todos los módulos
                       └──────┬──────┘
                              │ depende de
       ┌──────────────┬───────┴────────┬──────────────────┐
       ▼              ▼                ▼                  ▼
 ┌──────────┐  ┌─────────────┐  ┌────────────────┐  ┌──────────┐
 │  common  │◄─│  inventory  │  │  timetracking  │  │   auth   │
 └──────────┘  └─────────────┘  └────────────────┘  └──────────┘
      ▲   ▲                       │  implementa        │  consume
      │   └───────────────────────┘  DirectorioEmp.    │  DirectorioEmp.
      └────────────────────────────────────────────────┘
```

Las features viven en `features/` (`:features:inventory`,
`:features:timetracking`, `:features:auth`), cada una como módulo Gradle propio.
La carpeta solo agrupa: no es un módulo, no tiene código y no añade
dependencias. Las tres features dependen **solo** de `common`. `auth` necesita saber si una
persona existe y está en activo, y ese dato es de `timetracking`; lo obtiene a
través de un contrato declarado en `common`, nunca importando a `timetracking`.
Ver [Colaboración entre features](#colaboración-entre-features-contratos-en-common).

- **`app`**: único módulo con `main()`. Seguridad global (`SecurityConfig`),
  el endpoint de desarrollo para tokens (`DevAuthController`) y las
  propiedades por entorno.
- **`common`**: kernel compartido — excepciones base (`NotFoundException`,
  `ForbiddenException`, `UnauthorizedException`, `InvalidOperationException`),
  `Role` (`ADMIN` / `ENCARGADO` / `EMPLEADO` / `REPRESENTANTE`), `JwtService`,
  `JwtAuthFilter`, y los **contratos entre features** (`domain/contract/`).
- **`inventory`**: dominio de inventario de material de trabajo.
- **`timetracking`**: dominio de registro horario. Implementa
  `DirectorioEmpleados`.
- **`auth`**: inicio de sesión, sesiones, bloqueo por fuerza bruta, alta y
  restablecimiento de credenciales. Consume `DirectorioEmpleados`.
- **`invoices`**: facturación. Sube, reconoce con Claude, revisa y confirma
  facturas, saca reportes y cierra trimestres. No necesita nada de las otras
  features; lo que comparte con `timetracking` (el formato CSV y la validación de
  NIF) vive en `common` (`FormatoCsv`, `NifValidator`).

Ninguna feature conoce a otra: lo verifica el compilador, porque ningún
`build.gradle.kts` de feature menciona otro módulo que no sea `common`.

A diferencia del skeleton original, este proyecto **no incluye Redis ni
RabbitMQ**: el MVP no los necesita (no hay caché ni comunicación asíncrona
entre módulos todavía). Si `timetracking` termina necesitando publicar
eventos que `inventory` consuma (o viceversa), se puede reintroducir la
mensajería siguiendo el mismo patrón que tenía el skeleton.

## Capas dentro de una feature

Cada módulo de dominio sigue el mismo layout de paquetes:

```
com.granatum.core/
├── api/
│   ├── controllers/        # entrada HTTP, sin lógica de negocio
│   ├── dto/                 # contratos de entrada/salida de la API
│   ├── mappers/              # Model -> Dto
│   └── exception_handling/  # @RestControllerAdvice específico del dominio
├── domain/
│   ├── model/                # modelo de dominio (independiente de JPA/HTTP)
│   ├── exception/             # excepciones de negocio
│   └── type/                   # enums / typealiases del dominio
├── infrastructure/
│   └── database/
│       ├── entities/          # @Entity JPA
│       ├── mappers/            # Entity -> Model
│       └── repositories/       # Spring Data JPA
└── service/                     # orquesta repos + reglas de negocio
```

Flujo de una request: `Controller → Service → Repository (entidad JPA)`. El
controller nunca toca entidades JPA; el service nunca devuelve DTOs.

## Dominio de inventario

- **`Categoria`**: catálogo editable, tabla propia (no enum fijo).
- **`Material`**: cantidad disponible/total, tamaño embebido
  (`TamanoEmbeddable`: alto, ancho, diámetro, unidad de medida), estado
  (enum `EstadoMaterial`), fotos como colección de URLs
  (`@ElementCollection`, tabla `material_fotos`).
- **`HistorialMaterial`**: tabla de auditoría **inmutable** — la aplicación
  nunca hace `UPDATE`/`DELETE` sobre ella. Hoy solo se escribe desde
  `PATCH /api/materiales/{id}/cantidad`, que exige un `motivo` y registra
  `valorAnterior`/`valorNuevo`. Si se necesita auditar otros cambios
  (`estado`, `ubicacion`, `precioUnitario`...) se puede extender
  `MaterialService.update` para escribir entradas adicionales del mismo modo.

`cantidadDisponible` solo puede modificarse a través de ese PATCH: el PUT
general (`/api/materiales/{id}`) actualiza los atributos descriptivos del
material pero no la cantidad disponible, precisamente para forzar que todo
cambio de stock quede auditado.

## Migraciones

Flyway (`features/<feature>/src/main/resources/db/migration` en cada feature) sustituye al
`ddl-auto: update` del skeleton — `application.yml` usa
`ddl-auto: validate`, así que un mapeo JPA que no coincida con el esquema
falla rápido al arrancar en vez de alterar la tabla silenciosamente.

## Seguridad y roles

`JwtService` incluye el rol como claim del token, y el sujeto del token es el
**id de la persona empleada** (no el de la cuenta): así un fichaje se atribuye a
quien lo hizo sin ninguna consulta intermedia. Las rutas se declaran todas en
`SecurityConfig`, el único sitio donde vive el mapa de autorización.

Las credenciales viven en `auth` (`cuentas_acceso`), no en `Empleado`: el correo
y el rol son propiedades de la credencial, no del empleo. El rol se guarda ahí
por eso mismo — `puesto` dice qué hace alguien, `rol` dice qué puede leer y
escribir —, y se descubrió al implementar el login: ni la spec ni el plan decían
dónde guardarlo, y hasta entonces lo aportaba `/api/dev/token` como parámetro.

**Sesiones pendientes de cambio de contraseña.** El token lleva un claim
`pwd_change`, y `JwtAuthFilter` concede entonces la autoridad `PWD_CHANGE_ONLY`
**en lugar** del rol. Como todas las reglas son `hasAnyRole(...)`, todas rechazan
ese token por sí solas sin que ninguna feature sepa que `auth` existe, y
`change-password` es la única ruta que lo admite — siempre una concesión, nunca
una negación. El comodín final exige un rol real y no solo `authenticated()`,
porque un token pendiente **está** autenticado.

**Rechazos con cuerpo.** `EntryPointJson` responde los `401` y `403` con el
formato único `{code, message}`, y distingue `TOKEN_ACCESO_EXPIRADO` de
`NO_AUTENTICADO` para que el cliente sepa si renovar o pedir la contraseña.

`POST /api/dev/token` sigue existiendo solo con el perfil `dev`, pero ya no hace
falta para usar la aplicación.

**Errores de validación.** `CommonExceptionHandler` (`common`) responde todos los
`400` de validación de la API —cuerpo inválido, parámetro ausente o de tipo
incorrecto, JSON ilegible— con `{code: "VALIDACION", message}`, sin traza y sin el
valor rechazado. Hasta la feature 003 salían con el cuerpo por defecto de Spring,
que en `dev` traía la traza y el valor enviado.

**Descargas en streaming.** Una respuesta `StreamingResponseBody` termina con un
*async dispatch* que vuelve a pasar por la cadena de seguridad, y `JwtAuthFilter`
no actúa en él. `SecurityConfig` permite `DispatcherType.ASYNC`: solo reanuda una
petición ya autorizada. Sin eso, la descarga se denegaba con el fichero ya
enviado y la conexión se cortaba (`ExportacionHttpIT`). Y el
`server.tomcat.connection-timeout` explícito es lo que libera la conexión a la
base de datos cuando un cliente deja de leer (`ClienteLentoExportacionIT`).

## Endurecimiento (feature 006)

**Límite de peticiones propio, en memoria.** Una cubeta de fichas por cupo y
dirección (`CuboFichas`, `LimitadorPorOrigen` en `app`), en un mapa LRU acotado.
Propio y no una biblioteca porque son unas decenas de líneas y lo que aportaría
una biblioteca —contadores distribuidos— exige infraestructura que la
constitución deja fuera. Cubeta y no ventana fija porque una ventana fija admite
el doble del cupo en el cambio de ventana.

**El filtro va antes del JWT.** `FiltroLimitePorOrigen` se coloca en la cadena de
seguridad antes de `JwtAuthFilter`: una petición rechazada cuesta un acceso a un
mapa, nunca un hash Argon2 ni una escritura. Escribe el `429` él mismo —como
`EntryPointJson` los `401`/`403`— porque ocurre antes del `DispatcherServlet`,
donde no hay `@RestControllerAdvice` (desviación declarada del principio VIII).
No es un bean: un `Filter` bean se registraría además fuera de la cadena.

**La dirección es `remoteAddr`, nunca una cabecera leída a mano.** Detrás de un
proxy, `server.forward-headers-strategy=native` deja que Tomcat la sustituya por
la de `X-Forwarded-For` solo si la petición viene de un proxy de confianza.
Leerla en el filtro permitiría inventarse una dirección por petición.

**`prod` por defecto.** `spring.profiles.active` vale `prod` si nadie lo fija;
`bootRun` pone `dev`. La ruta `POST /api/dev/token` ya no depende de que alguien
recuerde una variable en el despliegue.

**Errores.** `ErroresJson` (un `DefaultErrorAttributes`) reduce lo que llega a
`/error` a `{code, message}`; `server.error.*` no incluye nunca trazas, clases ni
mensajes de excepción. No es un `@ExceptionHandler(Exception::class)` porque ese
se adelantaría a los resolutores de Spring y convertiría en `500` los `404`,
`405` y `415`.

## Ausencias (feature 007)

Módulo propio, `features/absences`, que solo depende de `common` y comprueba
que la persona existe y está activa con `DirectorioEmpleados`. Una ausencia no
crea ni toca fichajes: el registro de jornada (principio III) queda igual.

**Solapamiento y saldo bajo un bloqueo por persona.** Crear una ausencia toma
`pg_advisory_xact_lock(7007, hashtext(empleado_id))` antes de comprobar
solapamientos y saldo. Una restricción de exclusión (`EXCLUDE USING gist`)
necesitaría la extensión `btree_gist`, que una migración no puede dar por
hecha en Supabase; y el saldo necesitaba el mismo bloqueo de todas formas: dos
peticiones de vacaciones a la vez leerían el mismo saldo.

## Colaboración entre features: contratos en `common`

**Patrón nuevo, introducido por `auth`.** Cuando una feature necesita un dato que
es de otra, la dependencia no va de una a otra: se declara una interfaz en
`common/src/main/kotlin/com/granatum/core/domain/contract/`, la feature dueña del
dato la implementa, y la que lo necesita la consume. Es la vía que el principio
I de la constitución autoriza expresamente ("contrato explícito").

El primero es `DirectorioEmpleados`:

```kotlin
interface DirectorioEmpleados {
    fun estado(empleadoId: EntityId): EstadoEmpleado?        // null = no existe
    fun existentes(empleadoIds: Collection<EntityId>): Set<EntityId>
}
```

Reglas que este primer caso dejó claras:

- **El contrato es estrecho a propósito.** Responde si alguien existe y si está
  en activo, nada más: ni nombre, ni documento, ni ubicaciones. Un contrato que
  expusiera la ficha entera dejaría a cualquier feature futura leer datos
  personales que no necesita.
- **Las operaciones en lote son parte del contrato.** `existentes(...)` existe
  porque recorrer todas las cuentas preguntando una a una sería un N+1 que nadie
  podría ver desde el otro lado de la interfaz; la implementación está obligada a
  responder en una sola consulta, y `DirectorioEmpleadosIT` lo cuenta.
- **La dependencia es obligatoria.** Quien consume el contrato lo recibe por
  constructor sin valor por defecto, así que si nadie lo implementa la
  aplicación no arranca. Es el fallo correcto: un `auth` que no pudiera
  comprobar si una persona existe aceptaría cuentas huérfanas en silencio.
- **El coste es la integridad referencial.** No hay clave ajena entre tablas de
  módulos distintos, así que la base de datos no puede impedir que una cuenta
  apunte a una persona que dejó de existir. Por eso además de prevenirse, se
  **detectan**: `GET /api/auth/cuentas/huerfanas` y un trabajo nocturno.

El segundo, `FichasPersonal` (feature 005), es de **escritura**: `auth` busca la
ficha de personal que tiene un documento y, si no la hay, la crea al aprobar un
registro o al crear el primer `ADMIN`.

```kotlin
interface FichasPersonal {
    fun buscarPorDocumento(documento: String): EntityId?
    fun crear(alta: AltaFichaPersonal): EntityId
}
```

- **Es un contrato aparte, no dos métodos más en `DirectorioEmpleados`.** Ese
  se diseñó sin datos personales; este maneja nombre y documento y escribe.
  Mezclarlos daría a cualquier consumidor del contrato estrecho una forma de
  crear fichas. Dos contratos con un propósito cada uno se auditan de un vistazo.
- **Se une a la transacción de quien llama.** Aprobar crea la ficha y la cuenta
  juntas o ninguna; la implementación de `timetracking` usa la propagación por
  defecto y el mismo `EmpleadoService.crear` que el alta manual.

**Por qué el registro vive en `auth` y no en un módulo nuevo.** Lo que crea es
una credencial, y registrarse y entrar comparten la tabla de cuentas: separarlos
obligaría a compartir esa tabla entre módulos, que es justo lo que el principio
I prohíbe.

**Eventos de dominio: el otro contrato (feature 008).** Cuando una feature
necesita **contar** algo y no **preguntar**, el contrato es un evento en
`common/domain/event` (`AvisoDominio`). Quien publica usa el
`ApplicationEventPublisher` de Spring y no sabe quién escucha; `notifications`
escucha con `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution =
true)`. Tres reglas:

- **Solo después de confirmar**: una operación que se deshace no deja aviso de
  algo que no ocurrió.
- **El oyente no puede romper a quien publica**: escribe en un bean aparte con
  `REQUIRES_NEW` y captura cualquier fallo. Sin transacción en quien publica
  (el registro de la 005 cifra fuera de transacción), el oyente corre dentro de
  `publishEvent`, y ese `catch` es lo único que lo separa de la petición.
- **Solo identificadores**: el evento lleva tipo, referencia, titular y autor;
  nunca texto.

Para decidir destinatarios por rol hay un segundo contrato de consulta,
`DirectorioRoles`, que implementa `auth`.

**Nombres de clase únicos en todo el producto.** Todos los módulos comparten el
namespace `com.granatum.core` y cada uno va en su propio jar, así que dos clases
con el mismo nombre cualificado significan que en ejecución solo se carga una:
el módulo que compiló contra la otra obtiene un comportamiento distinto con
todos sus tests en verde. `auth` estuvo a punto de declarar un
`EmpleadoInactivoException` que ya existe en `timetracking` con otro código
HTTP. `SinColisionDeClasesIT` (en `app`, el único sitio con todos los módulos
en un classpath) lo vigila.

## Integración con un servicio externo: el reconocimiento de facturas

`invoices` es la primera feature que llama a un servicio externo (la API de
Claude), y lo hace con unas reglas que conviene repetir en la siguiente:

- **Detrás de un puerto.** `ReconocedorFacturas` es una interfaz del dominio. La
  implementación real (`ReconocedorClaude`, SDK oficial de Java) solo se construye
  si hay clave; sin ella, `ReconocedorDeshabilitado` deja la feature en modo
  manual. En los tests, un doble: **ningún test de la suite llama a la API real**.
- **La clave tiene una sola fuente**, la configuración. El cliente nunca se
  construye con `fromEnv()`, porque el SDK buscaría credenciales por su cuenta
  (variables, perfil de `ant` en disco) y un contexto "sin clave" podría gastar
  dinero. Los contextos de test la fijan vacía con un valor literal, porque Gradle
  carga el `.env` en los tests.
- **Nunca con una transacción abierta mientras responde.** Una llamada tarda
  decenas de segundos: la subida responde `202` tras confirmar, el reconocimiento
  corre en un ejecutor acotado, lee en una transacción corta, llama sin ninguna
  abierta y escribe en otra transacción corta. `SubidaFacturasIT` lo vigila.
- **Lo que vuelve es un dato.** Salida estructurada con esquema cerrado, sin
  herramientas: el contenido de un documento no puede provocar nada más que
  rellenar un borrador que una persona revisa.
- **Los fallos tienen tope.** Errores y rechazos se anotan por su tipo, nunca con
  contenido; el reintento programado hace como mucho tres intentos por factura.

## Corrección de fichajes

Los fichajes cerrados no se editan directamente. Cualquier corrección pasa
por una entidad `SolicitudCorreccionFichaje` con estado
(`PENDIENTE`/`APROBADA`/`RECHAZADA`) que registra quién la aprobó y cuándo —
mismo patrón de "tabla de auditoría inmutable + solicitud explícita" que usa
`HistorialMaterial` para el inventario.


## Registro horario: hechos frente a estado

El módulo `timetracking` separa dos cosas con reglas distintas, y esa
separación es lo que hace implementable el principio III de la constitución:

| Tabla | Naturaleza | Se actualiza |
|-------|-----------|--------------|
| `fichaje_eventos` | append-only: una fila por operación recibida, con la hora del hecho y la de llegada | **Nunca** dentro del plazo de conservación |
| `fichajes`, `pausas` | proyección del estado actual | Sí, pero solo por las cinco transiciones enumeradas |
| `solicitudes_correccion_fichaje` | append-only una vez resuelta; conserva los valores originales | No, tras resolverse |
| `depuraciones_retencion` | auditoría de la depuración; sin datos personales | Nunca |

La prueba documental es el log de eventos: ante la Inspección lo que importa es
qué fichó la persona y cuándo, no el estado agregado. La proyección existe
porque un fichaje nace `EN_CURSO` y necesariamente se actualiza para registrar
su salida — prohibir todo `UPDATE` sin distinguir hecho de estado haría
imposible cerrar una jornada.

El invariante que lo sostiene: **el estado de cualquier fichaje debe poder
derivarse del log de eventos más sus correcciones aprobadas**. Si un valor no se
explica por ninguno de los dos, se escribió por una vía que no debería existir.
Lo verifica `DerivabilidadEstadoIT`.

### Garantías que viven en el motor, no en el código

Dos reglas no se pueden expresar en JPA y van escritas a mano en SQL, porque son
las únicas a prueba de concurrencia:

```sql
CREATE UNIQUE INDEX uk_fichajes_empleado_en_curso
    ON fichajes (empleado_id) WHERE estado = 'EN_CURSO';
CREATE UNIQUE INDEX uk_pausas_fichaje_abierta
    ON pausas (fichaje_id) WHERE fin IS NULL;
```

La comprobación en el servicio solo da un mensaje legible: dos peticiones
simultáneas de entrada —el reintento de la app móvil— pasarían ambas la lectura
antes de que cualquiera escriba.

### Borrado

El borrado del producto vive en **dos** sitios, ambos como consultas
`@Modifying` acotadas por una fecha de corte y sin ningún endpoint ni rol que
los alcance — solo un proceso programado:

- `RetencionPurgaRepository` (`timetracking`): la depuración del registro de
  jornada a los cuatro años, y desde la feature 003 también de las
  `exportaciones` anotadas cuyo periodo cubierto (`hasta`) ha salido entero del
  plazo; cada ejecución anota cuántas borró (`exportaciones_eliminadas`, V16).
  `SinBorradoDentroDelPlazoIT` comprueba por reflexión que ningún otro de sus
  siete repositorios expone mutación destructiva.
  El límite del plazo se calcula en **un solo sitio**, `PlazoConservacion`, que
  usan a la vez la depuración y la exportación: si cada una lo calculara por su
  cuenta, un cambio en una y no en la otra haría que la exportación anunciara
  datos ya borrados o escondiera datos que existen.
  La depuración sale **desactivada** (`timetracking.retencion.habilitada`) y
  activarla es una decisión explícita de cada entorno.
- `SesionRenovacionRepository.purgarMuertasAntesDe` (`auth`): sesiones ya
  usadas, revocadas o caducadas hace más de 30 días. Una sesión viva no se toca
  por antigua que sea. Es un añadido del plan de `auth`, no de su spec, porque
  sin él esa tabla crece sin tope (~35.000 filas al año por persona) y un token
  consumido no prueba nada que no esté ya en `eventos_seguridad`.

Las tablas de `auth` que no admiten borrado (`cuentas_acceso`,
`eventos_seguridad`) tienen repositorios que extienden `Repository<T, ID>` y no
declaran `delete`. Las de `invoices` tampoco admiten ninguno: una factura se
conserva al menos seis años, descartarla no la borra y los historiales son de solo
inserción (`SinBorradoFacturacionIT`).

### Numeración de migraciones

Flyway comparte un único histórico en `classpath:db/migration` para todos los
módulos, así que la numeración es global: `inventory` ocupa `V1`–`V5`,
`timetracking` `V6`–`V11`, `auth` `V12`–`V14`, la exportación de
`timetracking` `V15`–`V16`, `invoices` `V17`–`V19` y el registro de `auth`
`V20`–`V21`, `absences` `V22` y `notifications` `V23`. Es un acoplamiento real entre módulos — al añadir una
migración hay que mirar qué número ocupa el otro — y se acepta porque la
alternativa (esquemas o históricos separados) complica el despliegue mucho más
de lo que ahorra.
