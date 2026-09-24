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
