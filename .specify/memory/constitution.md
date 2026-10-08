# Constitución de Granatum Suite Backend

## Core Principles

### I. Features independientes

Cada dominio de negocio vive en su propio módulo Gradle bajo `features/`
(`:features:inventory`, `:features:timetracking`, …) y se compila y testea por
separado. La carpeta `features/` solo agrupa: no es un módulo con código ni
puede aportar dependencias a las features. Reglas:

- Un módulo de feature **DEBE** depender únicamente de `common`. Dos features
  **NO DEBEN** depender la una de la otra ni importar sus clases.
- Si dos features necesitan compartir algo, ese algo **DEBE** subir a `common`
  o exponerse como contrato explícito, nunca mediante una dependencia directa.
- Solo `app` tiene `main()`; es el único módulo que agrega features y declara
  la seguridad global.
- Añadir una feature **DEBE** consistir en crear su módulo en `features/` y
  registrarlo en `settings.gradle.kts`, sin tocar los módulos de las demás.

Razón: el inventario y el fichaje tienen ciclos de vida y riesgos distintos.
Mantenerlos desacoplados permite que uno evolucione o falle sin arrastrar al
otro, y hace que el límite de cada feature sea verificable por el compilador
en lugar de depender de la disciplina de quien programa.

### II. Esquema versionado con Flyway

El esquema de base de datos es código versionado, no un efecto secundario del
mapeo JPA. Reglas:

- Todo cambio de esquema **DEBE** ir en una migración Flyway nueva y numerada
  (`V<n>__descripcion.sql`), dentro del módulo de la feature propietaria.
- Una migración ya aplicada **NO DEBE** editarse jamás. Para corregirla se
  escribe otra que la enmiende.
- `ddl-auto` **DEBE** ser `validate` en **todos** los perfiles, incluido `dev`.
  No solo en `prod`.
- Hibernate **NO DEBE** tener permiso para crear ni modificar esquema en ningún
  entorno, ni local.
- Las migraciones **DEBEN** ejecutarse en los tests de integración contra un
  Postgres real, de modo que una discrepancia entre entidad y esquema rompa el
  build y no el arranque en producción.

Razón: `ddl-auto: update` deja el esquema a merced de lo que Hibernate deduzca
en cada despliegue, sin histórico ni revisión posible. Y mantenerlo en `dev`
—como hace Squadfy_Backend— es peor que inútil: el esquema local se construye
por una vía distinta a la de producción, así que los errores de migración
aparecen justo donde más caros son. Con `validate` en todas partes, el entorno
local ejercita exactamente el mismo camino que el despliegue real.

### III. Inmutabilidad del registro horario (NO NEGOCIABLE)

El registro de jornada es un documento con valor legal bajo el RD-ley 8/2019.
Reglas:

- Un fichaje cerrado **NO DEBE** editarse ni borrarse nunca, por ninguna ruta
  de la API, ningún rol y ningún proceso interno.
- Toda corrección **DEBE** materializarse como una entidad
  `SolicitudCorreccionFichaje` con estado `PENDIENTE`, `APROBADA` o
  `RECHAZADA`, que registre qué se pide cambiar, quién lo pide, quién lo
  resuelve y en qué instante.
- Una solicitud solo **PUEDE** ser aprobada o rechazada por `ENCARGADO` o
  `ADMIN`, nunca por quien la creó si es `EMPLEADO`.
- Los registros **DEBEN** conservarse **4 años** y ser exportables a CSV para
  entregarlos a la Inspección de Trabajo o a la persona trabajadora.

**Hechos frente a estado.** El registro se compone de dos cosas con reglas
distintas, y confundirlas es lo que hace que una de las dos regla sea
imposible de cumplir:

- **Los hechos son inmutables, sin excepción.** Cada operación recibida
  (entrada, inicio y fin de pausa, salida) **DEBE** quedar registrada como una
  fila propia en una tabla de eventos append-only, con el instante en que
  ocurrió y el instante en que se recibió. Sobre esa tabla la aplicación
  **NO DEBE** ejecutar `UPDATE` ni `DELETE` jamás, por ninguna ruta, ningún rol
  ni ningún proceso interno. **Es la prueba documental**: ante la Inspección lo
  que importa es qué fichó la persona y cuándo, no el agregado.
- **El estado es una proyección y sí se actualiza**, pero solo por las
  transiciones enumeradas: añadir una pausa, cerrar la pausa abierta, registrar
  la salida, marcar `INCOMPLETO` el fichaje abierto de un día anterior, y
  aplicar una corrección aprobada. **Ninguna otra escritura está permitida.**
- El instante de entrada, una vez registrado, **NO DEBE** cambiar nunca salvo
  por una corrección aprobada — tampoco mientras el fichaje sigue `EN_CURSO`.
  Que la jornada esté abierta no autoriza a reescribir su inicio.
- Un fichaje **finalizado** (`CERRADO` o `INCOMPLETO`) **NO DEBE** cambiar de
  valor salvo por una corrección aprobada.
- **Invariante que cierra lo anterior**: el estado de cualquier fichaje **DEBE**
  poder derivarse en todo momento del log de eventos más las correcciones
  aprobadas. Si un valor de la proyección no se explica por un evento o por una
  corrección, es que se escribió por una vía que no debería existir. Este
  invariante es verificable con un test que reconstruya el estado y lo compare,
  y **DEBE** tenerlo.
- **Dentro del plazo legal de conservación ninguna fila se borra, sin
  excepción**: ni eventos, ni fichajes, ni pausas, ni solicitudes, ni
  empleados. No hay ruta de API, rol, ni anulación administrativa que lo
  permita, y **DEBE** existir un test que lo demuestre.
- **Agotado el plazo de conservación**, los registros **DEBEN** depurarse, y
  solo de esta forma:
  - La depuración la ejecuta **únicamente un proceso automático de retención**.
    **NO DEBE** existir ningún endpoint, rol ni operación manual capaz de
    borrar un registro, ni antes ni después del plazo.
  - Solo alcanza registros cuyo plazo haya vencido **por completo**. Un
    registro con un solo día de plazo restante es intocable.
  - Cada ejecución **DEBE** quedar anotada en un registro de depuración
    append-only —fechas alcanzadas, número de filas, instante— que no contiene
    datos personales y que no se depura nunca.
  - El proceso **NO DEBE** habilitarse en un entorno que no ofrezca la descarga
    mensual de los registros: la base para poder destruirlos es que hayan
    estado a disposición antes.

Razón de la depuración: conservar indefinidamente no es "cumplir mejor". El
RD-ley 8/2019 fija cuatro años, y pasados esos, el principio de limitación del
plazo de conservación del RGPD (art. 5.1.e) convierte la conservación perpetua
en un incumplimiento distinto. La obligación de destruir es tan real como la de
conservar; lo que no admite excepción es el plazo.
- Los repositorios de las tablas append-only **NO DEBEN** exponer operaciones
  de mutación. No basta con no llamarlas: si la interfaz las ofrece, un
  descuido futuro las usará sin que nada lo impida.

Razón: la ley exige poder demostrar la jornada realmente registrada, no la
última versión editada, y un `UPDATE` sobre el hecho destruye la prueba. Pero
prohibir todo `UPDATE` sin distinguir hecho de estado haría imposible cerrar
una jornada: un fichaje nace `EN_CURSO` y necesariamente se actualiza para
registrar su salida. Separar ambos planos conserva la prueba intacta y además
no cuesta nada, porque la tabla de eventos hace falta de todos modos para que
las operaciones diferidas de la app móvil sean idempotentes.

El mismo patrón de "tabla inmutable + solicitud explícita" ya rige
`HistorialMaterial` en `inventory`, así que es una regla del producto, no un
parche del fichaje.

### IV. Autorización por roles

Cuatro roles, con fronteras cerradas:

| Rol | Alcance |
|-----|---------|
| `ADMIN` | Todo. |
| `ENCARGADO` | Inventario completo, aprobación de correcciones de fichaje y resolución de ausencias de otras personas (incluido registrar bajas). |
| `EMPLEADO` | Únicamente lo suyo: sus fichajes (fichar y consultar su historial) y sus ausencias (pedirlas, cancelarlas y consultarlas con su saldo). |
| `REPRESENTANTE` | **Solo lectura** del registro de jornada de toda la plantilla. Nada más: tampoco ausencias. |

- Un `EMPLEADO` **NO DEBE** poder leer ni modificar los fichajes de otra
  persona, ni acceder a ninguna ruta de inventario.
- `REPRESENTANTE` existe porque el art. 34.9 del Estatuto nombra expresamente a
  la representación legal de los trabajadores como destinataria del registro.
  Su alcance es el mínimo que satisface esa obligación:
  - **PUEDE** consultar la jornada registrada de cualquier persona de la
    plantilla, en modo lectura.
  - **NO DEBE** ver la ubicación de los fichajes. El art. 34.9 no la exige y es
    el dato más intrusivo del registro: dárselo sería tratar datos personales
    sin obligación que lo sostenga.
  - **NO DEBE** aprobar ni rechazar correcciones, ni solicitarlas en nombre de
    nadie, ni dar de alta o baja personal, ni acceder a inventario.
  - **NO DEBE** poder escribir nada, en ninguna tabla.
- La propiedad del recurso **DEBE** comprobarse en el servidor contra el
  sujeto del JWT, nunca aceptando un identificador de usuario que venga en el
  cuerpo o la query de la petición.
- Las rutas públicas y protegidas **DEBEN** declararse en un único sitio
  (`SecurityConfig`), para que el mapa de autorización se pueda auditar de
  una lectura.

Razón: los datos de jornada son datos personales. Que un empleado pueda
consultar los fichajes de un compañero es tanto un fallo de autorización como
una brecha de protección de datos.

### V. Tests por feature y CI en verde

Ninguna feature se considera terminada sin pruebas. Reglas:

- Cada feature **DEBE** aportar tests unitarios de su lógica de negocio con
  **MockK**, sin tocar base de datos ni contexto de Spring.
- Cada feature **DEBE** aportar al menos un test de integración con
  **Testcontainers** contra un **Postgres real**, que ejecute las migraciones
  Flyway de verdad y valide el mapeo JPA de punta a punta.
- Las reglas invariantes de los principios III, IV, VI y VII **DEBEN** tener
  un test que falle si se rompen. Una regla sin test es una intención, no una
  garantía.
- **NADA se fusiona con la CI en rojo.** Un test que falla bloquea el merge;
  no se desactiva, se arregla o se revierte el cambio.
- Un test **NO DEBE** reescribirse para que pase si el fallo revela un
  comportamiento incorrecto del código.

Razón: H2 o un mock de base de datos no detectan discrepancias entre las
migraciones y las entidades, que es exactamente la clase de fallo que rompe el
arranque en producción con `ddl-auto: validate`.

### VI. Seguridad y gestión de secretos

- Los secretos (contraseñas de base de datos, clave de firma JWT) **DEBEN**
  provenir exclusivamente de variables de entorno. **NO DEBEN** aparecer en
  el repositorio, ni en `application.yml`, ni en logs, ni en mensajes de
  error.
- Los valores por defecto que existan en configuración **DEBEN** servir solo
  para desarrollo local y ser inservibles en producción.
- El endpoint `POST /api/dev/token` **NO DEBE** existir con el perfil `prod`
  activo, y `DevAuthControllerProfileTest` lo verifica.
- Sin perfil explícito, la aplicación **DEBE** arrancar con `prod`, nunca con
  `dev`: que la ruta anterior no exista no puede depender de que alguien
  recuerde fijar una variable en el despliegue. `PerfilPorDefectoTest` lo
  verifica. Solo el arranque local (`bootRun`) usa `dev` por defecto.
- El gate de ese endpoint **DEBE** ser una lista de perfiles permitidos
  (`@Profile("dev")`), nunca una negación (`@Profile("!prod")`): una negación
  lo dejaría vivo bajo cualquier perfil futuro —`staging`, `qa`, `demo`— y el
  test de "no existe en prod" seguiría pasando. Hay un test específico para
  esto.
- Las contraseñas **DEBEN** almacenarse con un hash adaptativo y salado
  (bcrypt o Argon2 mediante el encoder delegado de Spring Security). **NUNCA**
  en claro, ni con hash rápido tipo MD5/SHA.
- Los logs **NO DEBEN** contener datos personales: ni nombres, ni correos, ni
  identificadores de empleado asociados a jornada, ni tokens.

Razón: `/api/dev/token` emite un JWT con cualquier rol que se le pida y sin
credencial alguna. Expuesto en producción es una suplantación total de
`ADMIN`, y un flag de perfil mal puesto es un error de una línea: de ahí que
la protección tenga que ser un test y no una convención.

### VII. Row Level Security en todas las tablas

- Toda tabla **DEBE** activar `ROW LEVEL SECURITY` en la misma migración que la
  crea, no en una posterior.
- Una tabla nueva sin RLS **DEBE** considerarse un defecto que bloquea el
  merge, igual que un test en rojo. `RowLevelSecurityIT` lo comprueba de forma
  genérica, así que el olvido rompe el build sin depender de la revisión.
- El backend conecta como propietario de las tablas, así que RLS no altera su
  comportamiento. Las políticas existen para que la **API de datos de Supabase
  (PostgREST) no exponga nada** a quien tenga la clave anónima o de usuario.
- Por defecto se deniega: sin política explícita que lo permita, ninguna fila
  es visible para los roles `anon` ni `authenticated`.
- **NO DEBE** usarse `FORCE ROW LEVEL SECURITY`: aplicaría RLS también al
  propietario y dejaría a la aplicación sin acceso a sus propios datos. El
  bypass del propietario es justo el mecanismo que permite que la API siga
  autorizando en `SecurityConfig` mientras PostgREST queda ciego.
- Excepción conocida: `flyway_schema_history` no puede activar RLS desde una
  migración, porque Flyway mantiene un lock sobre esa tabla durante toda su
  ejecución y el `ALTER TABLE` se bloquearía contra sí mismo. Se activa como
  paso de operaciones por entorno, documentado en `README.md`.

Razón: Supabase publica automáticamente una API REST sobre el esquema
`public`. Una tabla sin RLS ahí queda legible desde Internet con la clave
anónima, que es pública por diseño. El riesgo no es hipotético y no depende de
nada que haga el backend.

### VIII. Contrato de la API REST

- Todos los endpoints **DEBEN** vivir bajo el prefijo `/api`.
- Los errores **DEBEN** devolver un formato único y estable:
  `{ "code": "<CODIGO_ESTABLE>", "message": "<texto legible>" }`, emitido desde
  un `@RestControllerAdvice` y nunca construido a mano en un controller.
- El `code` **DEBE** ser un identificador estable que los clientes puedan
  consumir; el `message` es para personas y puede cambiar.
- Los DTO **DEBEN** estar separados de las entidades JPA. Una entidad
  **NO DEBE** salir por HTTP ni aparecer en la firma de un controller, y un
  DTO **NO DEBE** llegar a la capa de persistencia.
- El flujo **DEBE** ser `Controller → Service → Repository`: el controller no
  toca entidades JPA, el service no devuelve DTO.
- Toda entrada **DEBE** validarse en el borde con Jakarta Validation
  (`@Valid`).

Razón: filtrar entidades JPA por HTTP ata el contrato público al esquema de
base de datos — cualquier renombrado de columna se convierte en un cambio
incompatible para los clientes — y arrastra colecciones lazy que explotan al
serializarse fuera de transacción.

### IX. Documentación de cada feature

- Cada feature **DEBE** quedar documentada en `specs/<NNN-nombre>/` con su
  especificación, su plan y sus tareas, generados por el flujo de Spec Kit.
- El `README.md` **DEBE** reflejar el estado real de cada módulo
  (implementado / pendiente) y cómo ejercitarlo.
- `docs/ARCHITECTURE.md` **DEBE** actualizarse cuando una feature introduzca
  una decisión estructural: un módulo nuevo, un patrón de auditoría, una
  dependencia entre capas.
- Una decisión de diseño no evidente **DEBE** registrar su *por qué*, no solo
  su *qué*. La documentación que describe lo obvio y calla lo sorprendente no
  sirve.

Razón: el riesgo de este repo no es olvidar cómo funciona el código, sino
olvidar por qué se descartó la alternativa — por ejemplo por qué
`cantidadDisponible` solo se puede cambiar por un `PATCH` que exige motivo.

## Restricciones Técnicas

**Stack.** Kotlin sobre JVM 21, Spring Boot (Web, Security, Data JPA,
Validation, Actuator), Gradle multi-módulo, PostgreSQL con Flyway, JWT con
jjwt, y JUnit 5 + MockK + Testcontainers para pruebas. Sustituir cualquiera de
estas piezas es una enmienda a esta constitución, no una decisión de feature.

**Versiones de dependencias.** `gradle/libs.versions.toml` es la única fuente
de verdad. Un `build.gradle.kts` de módulo **NO DEBE** fijar versiones a mano.

**Convention plugins.** La configuración común de compilación vive en
`build-logic/` (`granatum.kotlin-common`, `granatum.spring-boot-service`,
`granatum.spring-boot-app`). Un módulo nuevo **DEBE** aplicar el convention
plugin que le corresponda en lugar de duplicar configuración.

**Estructura de paquetes.** Todos los módulos comparten el namespace
`com.granatum.core` porque forman un único producto, y cada feature replica el
mismo layout:

```
com.granatum.core/
├── api/
│   ├── controllers/          # entrada HTTP, sin lógica de negocio
│   ├── dto/                  # contratos de entrada/salida
│   ├── mappers/              # Model → Dto
│   └── exception_handling/   # @RestControllerAdvice del dominio
├── domain/
│   ├── model/                # modelo de dominio, sin JPA ni HTTP
│   ├── exception/            # excepciones de negocio
│   └── type/                 # enums y typealiases
├── infrastructure/
│   └── database/
│       ├── entities/         # @Entity JPA
│       ├── mappers/          # Entity → Model
│       └── repositories/     # Spring Data JPA
└── service/                  # orquesta repositorios y reglas de negocio
```

**Simplicidad deliberada.** El proyecto no incluye Redis ni mensajería. Añadir
infraestructura **DEBE** responder a una necesidad demostrada en una spec, no
a una previsión. Lo que no se necesita hoy no se instala hoy.

## Flujo de Desarrollo

**Toda feature pasa por Spec Kit, en este orden:**

1. `/speckit-specify` — especificación de la feature (crea su rama y carpeta).
2. `/speckit-clarify` — *(opcional)* cerrar ambigüedades antes de planificar.
3. `/speckit-plan` — plan de implementación.
4. `/speckit-tasks` — tareas ordenadas por dependencias.
5. `/speckit-analyze` — *(opcional)* coherencia entre spec, plan y tareas.
6. `/speckit-implement` — ejecución.

Escribir código de producción antes de que exista la spec de su feature es una
violación de este flujo. Las correcciones de bugs y los cambios de
infraestructura quedan fuera del ciclo y van por PR directo.

**Puertas de calidad antes de fusionar:**

- `./gradlew build` en verde en local y en CI.
- Tests unitarios y de integración de la feature presentes y pasando.
- Migraciones Flyway nuevas con RLS activado.
- Sin secretos ni datos personales añadidos al repositorio o a los logs.
- `README.md` y, si procede, `docs/ARCHITECTURE.md` actualizados.

**Trabajo en ramas.** Una rama por feature, con el nombre que genere
`/speckit-specify`. No se commitea directamente en `main`.

## Governance

Esta constitución **prevalece** sobre cualquier otra práctica, costumbre o
preferencia de estilo. Ante un conflicto entre lo que dice este documento y lo
que hace el código, el código es el que está equivocado.

**Enmiendas.** Modificar un principio requiere: (1) un PR que cambie este
fichero, (2) una justificación explícita del porqué, y (3) un plan de
migración para el código que quede en incumplimiento. Los principios marcados
NO NEGOCIABLE solo se enmiendan si cambia la obligación legal que los
sostiene.

**Versionado.** Semántico sobre este documento:

- **MAJOR** — se elimina o redefine un principio de forma incompatible.
- **MINOR** — se añade un principio o se amplía materialmente su alcance.
- **PATCH** — aclaraciones, redacción, correcciones sin cambio de significado.

**Revisión de cumplimiento.** Toda revisión de PR verifica las puertas de
calidad de la sección anterior. Un incumplimiento conocido y aceptado
temporalmente **DEBE** quedar registrado como tarea en la spec de su feature,
con su motivo; no basta con dejarlo pasar en silencio.

**Deuda declarada.** Ninguna sobre los principios V, VI y VII: los tres
incumplimientos que existían al redactar la primera versión de este documento
—falta del test del perfil `prod`, ausencia de RLS en las tablas, y una CI sin
Postgres— se cerraron antes de la ratificación.

Excepciones y deuda vivas, todas acotadas y con su motivo:

1. **`flyway_schema_history` sin RLS** (principio VII), por la limitación
   técnica descrita allí. Se mitiga con un paso manual por entorno recogido en
   `README.md`.
2. **La depuración a los 4 años existe y está desbloqueada, pero desactivada.**
   El principio III la exige desde v2.0.0; la feature 001 la construyó, y la
   feature 003 cumplió la condición que la bloqueaba (la descarga mensual). Sigue
   apagada por defecto (`timetracking.retencion.habilitada`) porque activarla es
   la única operación del producto que destruye registros con valor legal, y esa
   decisión corresponde a cada entorno. Mientras no se active se conserva de más,
   que es el lado seguro del incumplimiento. `DepuracionPorDefectoIT` fija que
   desplegar no la enciende.

Deuda cerrada, conservada aquí como historial:

- **`HistorialMaterialRepository` extendía `JpaRepository`** (principio III).
  Estrechado a `Repository<T, ID>` sin `delete` en la feature 001.
- **`Role` no incluía `REPRESENTANTE`** (principio IV). Añadido en la feature
  001, con acceso de solo lectura al registro.

**Guía de desarrollo en tiempo de ejecución.** Los agentes de código leen esta
constitución junto a `README.md` y `docs/ARCHITECTURE.md`. Si los tres se
contradicen, manda esta constitución.

**Version**: 2.2.0 | **Ratified**: 2026-10-04 | **Last Amended**: 2026-10-08

*v2.2.0 (MINOR): el principio IV amplía el alcance de `ENCARGADO` y `EMPLEADO` a las ausencias (feature 007): la persona pide y ve las suyas; `ENCARGADO` y `ADMIN` resuelven las de otros, nunca las propias. `REPRESENTANTE` sigue sin acceso fuera del registro de jornada. Plan de migración: ninguno; la feature 007 añade las reglas en `SecurityConfig` y `AutorizacionAusenciasIT`.*

*v2.1.0 (MINOR): el principio VI exige que, sin perfil explícito, la aplicación arranque como `prod` (feature 006). Hasta entonces el perfil por defecto era `dev`, y olvidar `SPRING_PROFILES_ACTIVE` en un despliegue dejaba viva `POST /api/dev/token`. Plan de migración: ninguno pendiente; la feature 006 cambió el defecto y añadió el test.*

*v2.0.2 (PATCH): el principio I dice dónde viven los módulos de feature (`features/`), tras agruparlos ahí. Ninguna regla cambia: cada feature sigue siendo un módulo propio que solo depende de `common`.*

*v2.0.1 (PATCH): actualiza la lista de deuda declarada sin cambiar ningún principio. Las deudas 2 y 3 se cerraron en la feature 001, y la 4 cambió de naturaleza con la feature 003: la depuración ya existe y puede activarse.*
