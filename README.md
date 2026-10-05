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
- ⏳ `timetracking` (Fichaje, Empleado) — pendiente, se implementará como módulo independiente una vez validado `inventory`.
- ⏳ Login real (`/api/auth/login`, `/api/auth/refresh`) — depende de la entidad `Empleado` del módulo `timetracking`. Mientras tanto, `POST /api/dev/token` (solo perfil `dev`) permite emitir un JWT de prueba con el rol que se indique.

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
├── common/         # kernel compartido: excepciones, JWT, roles
├── inventory/      # dominio de inventario (Material, Categoria, HistorialMaterial)
├── build-logic/    # convention plugins de Gradle (composite build)
├── gradle/         # version catalog + gradle wrapper
├── .specify/       # Spec Kit: plantillas, scripts y constitución del proyecto
├── .claude/skills/ # comandos speckit-* (las skills de terceros van ignoradas)
├── specs/          # una carpeta por feature, la crea /speckit-specify
├── docker-compose.yml
└── docs/ARCHITECTURE.md
```
