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

Las tres features dependen **solo** de `common`. `auth` necesita saber si una
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

Flyway (`<módulo>/src/main/resources/db/migration` en cada feature) sustituye al
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

**Nombres de clase únicos en todo el producto.** Todos los módulos comparten el
namespace `com.granatum.core` y cada uno va en su propio jar, así que dos clases
con el mismo nombre cualificado significan que en ejecución solo se carga una:
el módulo que compiló contra la otra obtiene un comportamiento distinto con
todos sus tests en verde. `auth` estuvo a punto de declarar un
`EmpleadoInactivoException` que ya existe en `timetracking` con otro código
HTTP. `SinColisionDeClasesIT` (en `app`, el único sitio con todos los módulos
en un classpath) lo vigila.

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
  jornada a los cuatro años. `SinBorradoDentroDelPlazoIT` comprueba por reflexión
  que ningún otro de sus seis repositorios expone mutación destructiva.
- `SesionRenovacionRepository.purgarMuertasAntesDe` (`auth`): sesiones ya
  usadas, revocadas o caducadas hace más de 30 días. Una sesión viva no se toca
  por antigua que sea. Es un añadido del plan de `auth`, no de su spec, porque
  sin él esa tabla crece sin tope (~35.000 filas al año por persona) y un token
  consumido no prueba nada que no esté ya en `eventos_seguridad`.

Las tablas de `auth` que no admiten borrado (`cuentas_acceso`,
`eventos_seguridad`) tienen repositorios que extienden `Repository<T, ID>` y no
declaran `delete`.

### Numeración de migraciones

Flyway comparte un único histórico en `classpath:db/migration` para todos los
módulos, así que la numeración es global: `inventory` ocupa `V1`–`V5`,
`timetracking` `V6`–`V11` y `auth` `V12`–`V14`. Es un acoplamiento real entre módulos — al añadir una
migración hay que mirar qué número ocupa el otro — y se acepta porque la
alternativa (esquemas o históricos separados) complica el despliegue mucho más
de lo que ahorra.
