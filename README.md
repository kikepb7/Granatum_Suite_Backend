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

# 3. Arranca la app (perfil "dev" por defecto, aplica las migraciones Flyway al arrancar)
./gradlew :app:bootRun
```

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
├── docker-compose.yml
└── docs/ARCHITECTURE.md
```
