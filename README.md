# Granatum Suite Backend

Backend de gestión para Granatum: inventario de material de trabajo (flores/eventos)
y, próximamente, fichaje de personal conforme al RD-ley 8/2019.

Extraído a partir de [Spring_Boot_Skeleton](https://github.com/kikepb7/Spring_Boot_Skeleton),
adaptando el kernel compartido (JWT, manejo de errores) al dominio de Granatum y
sustituyendo la feature de ejemplo por el módulo `inventory` real. Ver
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) para el detalle.

## Stack

- Kotlin 2.2 / JVM 21
- Spring Boot 4 (Web, Security, Data JPA, Validation, Actuator)
- Gradle multi-módulo con convention plugins propios (`build-logic/`)
- PostgreSQL + Flyway
- JWT (jjwt) con roles `ADMIN` / `ENCARGADO` / `EMPLEADO`
- JUnit 5 + MockK; Testcontainers para tests de integración con Postgres real

## Estado del proyecto

- ✅ `inventory` (Material, Categoria, HistorialMaterial) — implementado y con migraciones Flyway.
- ✅ `timetracking` (Empleado, Fichaje, Pausa, SolicitudCorreccionFichaje) — implementado. Registro de jornada conforme al RD-ley 8/2019: entrada, pausas, salida, correcciones con aprobación, consulta por rango, resumen mensual, modo sin conexión idempotente y depuración a los 4 años. Especificado en [`specs/001-timetracking/`](specs/001-timetracking/).
- ✅ `auth` (CuentaAcceso, SesionRenovacion, EventoSeguridad) — implementado. Inicio de sesión real con correo y contraseña, renovación de un solo uso, cierre de sesión, bloqueo creciente por fuerza bruta, alta con contraseña temporal y cambio obligatorio, y restablecimiento por un `ADMIN`. Especificado en [`specs/002-auth/`](specs/002-auth/). `POST /api/dev/token` sigue existiendo en el perfil `dev`, pero ya no es necesario para usar la aplicación.
- ⚠️ **Falta una vía para crear el primer `ADMIN` en producción** — ver [El primer administrador](#el-primer-administrador). Bloquea el primer despliegue.
- ✅ Exportación del registro de jornada — implementada. Cada persona descarga su registro, la representación legal y quien gestiona la plantilla el de todos, y para cada persona la descarga mensual con su total. CSV para hoja de cálculo española, una huella SHA-256 por fichero y un registro de quién exportó qué. Especificada en [`specs/003-timetracking-export/`](specs/003-timetracking-export/). Con ella, la depuración a los 4 años queda **desbloqueada pero desactivada** (ver [Exportación](#exportación-del-registro-de-jornada)).

## Arranque rápido

Requisitos: JDK 21, Docker.

```bash
# 1. Levanta Postgres
docker compose up -d

# 2. Copia las variables de entorno de ejemplo
cp .env.example .env

# 3. Genera tu clave de firma JWT y ponla en .env (JWT_SECRET_BASE64)
openssl rand -base64 32

# 4. Arranca la app (perfil "dev" por defecto, aplica las migraciones Flyway al arrancar)
./gradlew :app:bootRun
```

> **El paso 3 no es opcional.** `application.yml` **no tiene valor por defecto**
> para `JWT_SECRET_BASE64` ni para `DB_PASSWORD`, así que la app se niega a
> arrancar si faltan. Es deliberado: un valor por defecto significa que, si la
> variable no está puesta en producción, la aplicación arranca con una clave
> committeada en este repositorio — y quien conozca esa clave puede emitir un
> token con cualquier rol, lo que equivale a no tener autenticación.
>
> `.env` **sí se carga**: `bootRun` y las tareas de test lo leen y lo pasan como
> variables de entorno de verdad (ver `build-logic/src/main/kotlin/DotEnv.kt`).
> Spring Boot no lee `.env` por su cuenta, y Gradle tampoco. Los tests no
> necesitan que pongas ninguna clave: se genera una nueva en cada ejecución.

La app queda escuchando en `http://localhost:8080`.

### Probar el módulo de inventario

Lo de abajo usa el emisor de desarrollo por brevedad. Con una cuenta real, el
token sale de `POST /api/auth/login` (ver [Autenticación](#autenticación)).

```bash
# Consigue un token de desarrollo con rol ENCARGADO
TOKEN=$(curl -s -X POST "http://localhost:8080/api/dev/token?role=ENCARGADO" | jq -r .accessToken)

# Crea una categoría
CATEGORIA_ID=$(curl -s -X POST http://localhost:8080/api/categorias \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"nombre": "Cilindros", "descripcion": "Cilindros de cristal"}' | jq -r .id)

# Crea un material
curl -X POST http://localhost:8080/api/materiales \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{
    \"nombre\": \"Cilindro alto 40cm\",
    \"categoriaId\": \"$CATEGORIA_ID\",
    \"cantidadDisponible\": 10,
    \"cantidadTotal\": 10,
    \"tamano\": {\"alto\": 40, \"ancho\": 15, \"diametro\": 15, \"unidadMedida\": \"CM\"},
    \"color\": \"Transparente\",
    \"materialFisico\": \"Cristal\",
    \"estado\": \"NUEVO\",
    \"ubicacion\": \"Almacen A\",
    \"precioUnitario\": 12.5,
    \"proveedor\": \"Cristaleria Sur\",
    \"fotos\": []
  }"
```

## Autenticación

El ciclo completo, con comandos que se pueden copiar, está en
[`specs/002-auth/quickstart.md`](specs/002-auth/quickstart.md); el contrato de
cada ruta, en [`specs/002-auth/contracts/README.md`](specs/002-auth/contracts/README.md).
En resumen:

| Ruta | Quién | Qué hace |
|------|-------|----------|
| `POST /api/auth/login` | pública | Correo y contraseña → token de acceso (15 min) y de renovación (30 días) |
| `POST /api/auth/refresh` | pública | Renueva; el token presentado deja de servir en el acto |
| `POST /api/auth/logout` | pública | Cierra **esa** sesión; las de otros dispositivos siguen |
| `POST /api/auth/change-password` | autenticada | Única operación permitida mientras la contraseña es temporal |
| `POST /api/auth/cuentas` | `ADMIN` | Da acceso a una persona ya registrada; devuelve la contraseña temporal **una sola vez** |
| `POST /api/auth/cuentas/{empleadoId}/restablecer` | `ADMIN` | Nueva temporal, cierra todas las sesiones, levanta el bloqueo |
| `GET /api/auth/cuentas/huerfanas` | `ADMIN` | Cuentas cuya persona ya no existe |

Cinco fallos seguidos bloquean la cuenta 1, 5, 15 y 60 minutos de forma
creciente. Mientras dura el bloqueo, la respuesta es **idéntica** a la de una
contraseña incorrecta: así el inicio de sesión no revela qué correos existen, a
costa de que quien se equivoca no sepa cuánto esperar.

### Variables de entorno

Ninguna es un secreto, así que todas tienen valor por defecto. El único secreto
sigue siendo `JWT_SECRET_BASE64`.

| Variable | Defecto | Para qué |
|----------|---------|----------|
| `JWT_EXPIRATION_MINUTES` | 15 | Vida del token de acceso (igual que Squadfy_Backend; en `dev` el defecto es 1000) |
| `AUTH_REFRESH_EXPIRATION_DAYS` | 30 | Vida del token de renovación |
| `AUTH_ARGON2_MEMORY_KB` | 65536 | Memoria por verificación de contraseña |
| `AUTH_ARGON2_ITERATIONS` | 3 | Iteraciones de Argon2id |
| `AUTH_ARGON2_PARALLELISM` | 1 | Carriles de Argon2id |
| `AUTH_HASH_CONCURRENCIA` | 4 | Verificaciones simultáneas como máximo |
| `AUTH_HASH_ESPERA_MS` | 1000 | Espera en cola antes de responder `503` |
| `AUTH_PURGA_SESIONES_DIAS` | 30 | Antigüedad para purgar sesiones ya muertas |

> **Recalibra Argon2 en el hardware de destino antes de desplegar.** Los valores
> por defecto cuestan ~110 ms en un Mac mini de 10 núcleos; en un contenedor
> pequeño pueden costar mucho más. El programa de medición está en
> [`specs/002-auth/research.md`](specs/002-auth/research.md#cómo-se-midió).
> Si subes la memoria, recuerda que es memoria **por login simultáneo**.

> **`DB_POOL_MAX_SIZE` tiene que ser al menos el doble de
> `AUTH_HASH_CONCURRENCIA`.** Una petición de autenticación puede usar dos
> conexiones a la vez (su transacción y la del registro de seguridad), y con un
> pool menor las peticiones simultáneas se bloquean entre sí hasta el timeout.
> La aplicación **se niega a arrancar** si no se cumple, con un mensaje que dice
> cuál de las dos variables tocar.

### El primer administrador

**Hoy no existe una vía para crearlo en producción.** Dar acceso a alguien exige
un token de `ADMIN`; en desarrollo se obtiene con `POST /api/dev/token`, pero ese
endpoint no existe con el perfil `prod`, a propósito, y no hay ninguna cuenta
sembrada. Así que el primer despliegue no tiene con qué empezar.

No se ha resuelto aquí porque cada alternativa es una decisión de seguridad:

- **Sembrar por variables de entorno al arrancar** (correo y contraseña inicial
  del primer `ADMIN`): sencillo, pero deja una credencial en la configuración del
  despliegue que alguien tiene que acordarse de rotar.
- **Una tarea de línea de comandos** que genere el hash con el mismo encoder y
  emita el `INSERT`: no deja nada en la configuración, pero requiere acceso a la
  base de datos para el primer arranque.
- **Una ruta de arranque de un solo uso** que solo funcione con la tabla de
  cuentas vacía: cómoda, pero es una ruta pública mientras no se use.

La cuenta tiene que estar vinculada a una persona de `empleados`, como todas.

### Deuda declarada de esta feature

- **`eventos_seguridad` no tiene plazo de conservación.** No es el registro de
  jornada, así que el principio III no le aplica, pero el art. 5.1.e del RGPD sí:
  una tabla de auditoría que crece para siempre es el mismo incumplimiento por el
  otro lado. Fijar el plazo es decisión del responsable del producto.
- **Desviación declarada del principio VIII.** El error `PASSWORD_DEBIL` lleva un
  tercer campo, `requisitos`, además de `code` y `message`. Lo exige FR-023 —hay
  que decir qué requisito falla, y un cliente que marque campos necesita
  identificadores, no una frase—, pero el principio fija el formato en esos dos
  campos exactamente, así que queda escrito aquí en lugar de pasar en silencio.

## Exportación del registro de jornada

El ciclo completo está en
[`specs/003-timetracking-export/quickstart.md`](specs/003-timetracking-export/quickstart.md);
el contrato, en
[`specs/003-timetracking-export/contracts/README.md`](specs/003-timetracking-export/contracts/README.md).

| Ruta | Quién | Qué hace |
|------|-------|----------|
| `GET /api/fichajes/export?desde=&hasta=[&empleadoId=]` | los cuatro roles | El registro de un rango. `EMPLEADO`: solo el suyo. El resto: una persona o, sin `empleadoId`, toda la plantilla (incluidas las personas dadas de baja) |
| `GET /api/fichajes/empleado/{id}/resumen/descarga?anio=&mes=` | la propia persona, `ENCARGADO`, `ADMIN`, `REPRESENTANTE` | El mes natural con su total, tipo de contrato y si el mes ha terminado. Es el resumen del art. 12.4.c para contratos a tiempo parcial |
| `GET /api/exportaciones` | `ADMIN` | Quién exportó qué. Filtros: `empleadoId` (incluye las exportaciones de toda la plantilla), `generadaDesde`/`generadaHasta`, `cubreDesde`/`cubreHasta` (por solapamiento) |
| `POST /api/exportaciones/verificar` | `ADMIN` | Envía un fichero como cuerpo (`text/csv`) y dice si salió de aquí y cuándo. No se guarda nada del fichero |

```bash
curl -H "Authorization: Bearer $TOKEN" -OJ \
  "http://localhost:8080/api/fichajes/export?desde=2026-10-01&hasta=2026-10-31"
```

El fichero es CSV en UTF-8 con BOM, separador `;` y fin de línea CRLF, para que se
abra sin asistente en una hoja de cálculo con configuración española. Ninguna celda
se ejecuta como fórmula. La ubicación no sale nunca, y con rol `REPRESENTANTE` la
columna del documento de identidad no existe. Dos exportaciones iguales sin cambios
entre medias dan el mismo fichero byte a byte.

### La depuración a los 4 años: desbloqueada, no activada

La feature 001 dejó la depuración apagada hasta que el registro se pudiera
descargar. Esta feature cumple esa condición, **pero no la activa**: borrar
registros con valor legal es una decisión de cada entorno. Para activarla:

```yaml
timetracking:
  retencion:
    habilitada: true
```

> **Es irreversible.** Lo que la depuración borra no se recupera. Antes de
> activarla en un entorno, asegúrate de que la descarga mensual se ha puesto a
> disposición de cada persona.

La depuración borra también las exportaciones anotadas cuyo periodo cubierto ya
ha salido entero del plazo, y cuenta cuántas en `depuraciones_retencion`.

### Variables de entorno

Ninguna es un secreto.

| Variable | Defecto | Para qué |
|----------|---------|----------|
| `TIMETRACKING_EXPORTACION_CONCURRENCIA` | 2 | Exportaciones simultáneas como máximo; cada una ocupa una conexión mientras se descarga |
| `TIMETRACKING_EXPORTACION_ESPERA_MS` | 1000 | Espera antes de responder `503 EXPORTACION_SATURADA` con `Retry-After` |
| `TIMETRACKING_EXPORTACION_TIMEOUT_SEGUNDOS` | 120 | Tiempo máximo de la lectura de una exportación |
| `TIMETRACKING_EXPORTACION_TAMANO_LOTE` | 500 | Fichajes por lote al recorrer el registro |
| `TIMETRACKING_VERIFICACION_MAX_BYTES` | 104857600 | Tamaño máximo de un fichero a verificar (`413` por encima) |
| `SERVER_TOMCAT_CONNECTION_TIMEOUT` | 10s | Además, lo que puede retener una conexión a la base de datos un cliente que deja de leer una descarga |

### Errores de validación

Todos los `400` de validación de la API, de cualquier módulo, responden ahora
`{"code": "VALIDACION", "message": …}`, sin traza y sin el valor rechazado.
Antes salían con el cuerpo por defecto de Spring, que en `dev` incluía la traza y
el valor enviado (un correo, o una contraseña demasiado larga).

## Desarrollo guiado por especificaciones (SDD)

Este repo usa [Spec Kit](https://github.com/github/spec-kit) para desarrollo
guiado por especificaciones: antes de escribir código se escribe una
especificación, de ahí sale un plan, del plan una lista de tareas, y solo
entonces se implementa. La idea es que el *qué* y el *por qué* queden escritos
y revisables, en vez de vivir en la cabeza de quien programó.

### Requisito previo

El flujo lo conduce un agente de código (Claude Code), pero el andamiaje lo
genera la CLI `specify`, que no viene en el repo. Instálala una vez:

```bash
uv tool install specify-cli --from git+https://github.com/github/spec-kit.git
```

Si no tienes `uv`: `brew install uv`. Comprueba con `specify check`.

No hace falta volver a ejecutar `specify init`: el repo ya está inicializado
(integración `claude`, scripts `sh`, numeración secuencial de features).

### El ciclo

Cada paso es un comando que se invoca dentro del agente, no en la terminal:

| Comando | Para qué |
|---------|----------|
| `/speckit-constitution` | Fija los principios del proyecto. **Se hace una vez**, antes de la primera spec. |
| `/speckit-specify` | Escribe la especificación de una feature a partir de una descripción en lenguaje natural. Crea su rama y su carpeta. |
| `/speckit-clarify` | *(opcional)* Hace preguntas dirigidas para cerrar ambigüedades. Mejor antes de `plan`. |
| `/speckit-plan` | Convierte la spec en plan de implementación y artefactos de diseño. |
| `/speckit-tasks` | Desglosa el plan en tareas ordenadas por dependencias. |
| `/speckit-analyze` | *(opcional)* Informe de coherencia entre spec, plan y tareas. |
| `/speckit-implement` | Ejecuta las tareas. |
| `/speckit-converge` | Compara el código real con la spec y añade como tareas lo que falte. |

`/speckit-specify` crea una rama y una carpeta por feature, numeradas de forma
secuencial:

```
specs/
└── 001-nombre-de-la-feature/
    ├── spec.md
    ├── plan.md
    └── tasks.md
```

### Qué hay versionado

- `.specify/` — plantillas, scripts y la **constitución** del proyecto
  (`.specify/memory/constitution.md`).
- `.claude/skills/speckit-*/` — los comandos de arriba.

> La constitución está todavía **sin rellenar** (tiene marcadores como
> `[PROJECT_NAME]` y `[PRINCIPLE_1_NAME]`). Es el primer paso pendiente:
> ejecuta `/speckit-constitution` antes de la primera especificación.

### Skills de terceros (no versionadas)

Aparte de las de Spec Kit, el entorno de desarrollo usa skills de Supabase y
JetBrains que **no están en el repo** a propósito (son ~744 KB de código ajeno;
ver las reglas en `.gitignore`). Si las quieres, instálalas tú:

```bash
# Necesita Node: brew install node
npx skills add supabase/agent-skills -a claude-code -y
npx skills add Kotlin/kotlin-agent-skills -a claude-code -y
```

Son opcionales — el flujo SDD funciona sin ellas. Las útiles aquí son
`kotlin-backend-jpa-entity-mapping` (entidades JPA, `LazyInitializationException`,
fetch plans) y `supabase-postgres-best-practices` (esquema, migraciones, RLS,
índices), relevante porque el `datasource` ya admite Supabase y hoy la
autorización vive solo en `SecurityConfig`, sin nada a nivel de base de datos.

## Supabase

Supabase es Postgres, así que no hace falta ningún cambio de esquema ni de
JPA/Flyway - solo apuntar el `datasource` a su host. Supabase da tres cadenas
de conexión distintas (pestaña *Connect* del proyecto); cuál usar importa:

| Conexión                    | Puerto | Cuándo usarla |
|------------------------------|--------|----------------|
| **Direct connection**         | 5432   | Recomendada si el backend corre en un solo proceso de larga duración (esto). Sin límites de PgBouncer. |
| **Session pooler**            | 5432   | Igual de compatible que la directa, útil si tu red no soporta IPv6 (la directa de Supabase es IPv6-only salvo add-on de IPv4). |
| **Transaction pooler (PgBouncer)** | 6543 | Pensada para serverless/muchas conexiones cortas. **No** soporta prepared statements a nivel de sesión ni `SET`, así que rompe a Hibernate y a Flyway si no se ajusta (ver abajo). |

Para desarrollo o un backend "tradicional" como este, usa **Direct connection**
o **Session pooler**. Variables de entorno (`.env`):

```bash
DB_HOST=aws-0-<region>.pooler.supabase.com   # o db.<project-ref>.supabase.co para la directa
DB_PORT=5432
DB_NAME=postgres
DB_USERNAME=postgres.<project-ref>            # la directa usa solo "postgres"
DB_PASSWORD=<tu contraseña de base de datos>
DB_SSLMODE=require
```

Si en su lugar necesitas el **Transaction pooler** (puerto 6543), añade:

```bash
DB_PORT=6543
DB_PREPARE_THRESHOLD=0        # desactiva prepared statements de servidor (PgBouncer los rompe)
DB_POOL_MAX_SIZE=4             # mantente por debajo del límite de conexiones por cliente del pooler
FLYWAY_DB_URL=jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require
FLYWAY_DB_USERNAME=postgres
FLYWAY_DB_PASSWORD=<tu contraseña de base de datos>
```

(Flyway necesita locks de sesión que el modo transacción de PgBouncer no da,
así que ahí sí conviene que las migraciones vayan por la conexión directa
aunque el resto de la app use el pooler.)

En producción, usa el perfil `prod` (`SPRING_PROFILES_ACTIVE=prod`), que pone
`ddl-auto: validate` y no formatea el SQL en logs.

### Row Level Security

Supabase publica automáticamente una API REST (PostgREST) sobre el esquema
`public`. Una tabla sin RLS ahí queda legible desde Internet con la clave
anónima, que es **pública por diseño** — y eso es independiente de la
autorización que haga el backend.

Por eso todas las tablas de aplicación llevan RLS activado desde su migración
(`V5__enable_row_level_security.sql`), sin ninguna política: eso significa
denegar por defecto, así que `anon` y `authenticated` no ven ni una fila. El
backend conecta como propietario de las tablas, y los propietarios no están
sujetos a RLS, así que sigue funcionando sin cambios.

> No se usa `FORCE ROW LEVEL SECURITY` a propósito: aplicaría RLS también al
> propietario y dejaría a la aplicación sin acceso a sus propios datos.

`RowLevelSecurityIT` falla el build si alguna tabla se queda sin RLS, así que
una migración futura que cree una tabla y lo olvide no llega a `main`.

Donde más importa es en `cuentas_acceso`, que guarda correos y hashes de
contraseña: sin RLS, PostgREST la serviría a cualquiera con la clave anónima.

**Un paso manual pendiente por entorno.** La tabla de control de Flyway,
`flyway_schema_history`, también vive en `public` y PostgREST la serviría
(versiones y descripciones de las migraciones; no hay datos personales ni
credenciales, pero sí información de reconocimiento). No se puede arreglar
desde una migración, porque Flyway mantiene un lock sobre esa tabla durante
toda su ejecución y el `ALTER TABLE` se bloquearía contra sí mismo
indefinidamente. Ejecútalo una vez, a mano, en cada entorno con Supabase:

```sql
ALTER TABLE flyway_schema_history ENABLE ROW LEVEL SECURITY;
```

## Comandos habituales

```bash
./gradlew build          # compila y ejecuta tests de todos los módulos
./gradlew test           # solo tests
./gradlew :app:bootRun   # arranca la app
docker compose down -v   # apaga y limpia los volúmenes locales
```

## Estructura del repositorio

```
.
├── app/            # módulo ejecutable: main class, seguridad, config, application.yml
├── common/         # kernel compartido: excepciones, JWT, roles y contratos entre features
├── inventory/      # dominio de inventario (Material, Categoria, HistorialMaterial)
├── timetracking/   # registro de jornada (Empleado, Fichaje, Pausa, correcciones)
├── auth/           # inicio de sesión, sesiones, bloqueo, alta y restablecimiento
├── build-logic/    # convention plugins de Gradle (composite build)
├── gradle/         # version catalog + gradle wrapper
├── .specify/       # Spec Kit: plantillas, scripts y constitución del proyecto
├── .claude/skills/ # comandos speckit-* (las skills de terceros van ignoradas)
├── specs/          # una carpeta por feature, la crea /speckit-specify
├── docker-compose.yml
└── docs/ARCHITECTURE.md
```
