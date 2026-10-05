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
         ┌───────────────┼───────────────────┐
         ▼               ▼                   ▼
   ┌──────────┐   ┌─────────────┐   ┌────────────────┐
   │  common  │◄──│  inventory  │   │  timetracking  │
   └──────────┘   └─────────────┘   └────────────────┘
        ▲                                   │
        └───────────────────────────────────┘
```

- **`app`**: único módulo con `main()`. Seguridad global (`SecurityConfig`),
  el endpoint de desarrollo para tokens (`DevAuthController`) y las
  propiedades por entorno.
- **`common`**: kernel compartido — excepciones base (`NotFoundException`,
  `ForbiddenException`, `UnauthorizedException`, `InvalidOperationException`),
  `Role` (`ADMIN` / `ENCARGADO` / `EMPLEADO`), `JwtService` y `JwtAuthFilter`.
- **`inventory`**: dominio de inventario de material de trabajo.
- **`timetracking`**: dominio de registro horario. Depende solo de `common`;
  `inventory` y `timetracking` no se conocen entre sí.

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

Flyway (`inventory/src/main/resources/db/migration`) sustituye al
`ddl-auto: update` del skeleton — `application.yml` usa
`ddl-auto: validate`, así que un mapeo JPA que no coincida con el esquema
falla rápido al arrancar en vez de alterar la tabla silenciosamente.

## Seguridad y roles

`JwtService` incluye el rol como claim del token. `SecurityConfig` exige
`ADMIN` o `ENCARGADO` para todo `/api/materiales/**` y `/api/categorias/**`;
`EMPLEADO` no tiene acceso al inventario (solo lo tendrá a las rutas de
fichaje, cuando existan). No hay todavía un flujo de login real porque las
credenciales viven en `Empleado`, que es parte del módulo `timetracking`
pendiente; `POST /api/dev/token` (perfil `dev`) permite emitir tokens de
prueba con el rol indicado mientras tanto.

## Corrección de fichajes (para cuando se implemente `timetracking`)

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

Todo el borrado del producto vive en `RetencionPurgaRepository`, con cada método
como consulta `@Modifying` acotada por la fecha de corte. Ningún otro
repositorio expone operaciones de mutación destructiva, y
`SinBorradoDentroDelPlazoIT` lo comprueba por reflexión sobre los seis.

### Numeración de migraciones

Flyway comparte un único histórico en `classpath:db/migration` para todos los
módulos, así que la numeración es global: `inventory` ocupa `V1`–`V5` y
`timetracking` `V6`–`V11`. Es un acoplamiento real entre módulos — al añadir una
migración hay que mirar qué número ocupa el otro — y se acepta porque la
alternativa (esquemas o históricos separados) complica el despliegue mucho más
de lo que ahorra.
