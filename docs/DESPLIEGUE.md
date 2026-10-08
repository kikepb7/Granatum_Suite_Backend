# Despliegue

Guía para poner en producción una versión de Granatum Suite, válida para
cualquier plataforma que ejecute contenedores (Railway, Fly.io, Render, Cloud
Run, un VPS con Docker…). Lo específico de cada plataforma —cómo se declaran las
variables, el volumen o el dominio— queda fuera; lo que la aplicación necesita
está aquí.

## 1. Qué se despliega

- **La imagen**: `ghcr.io/<owner>/<repo>:<versión>`, publicada al etiquetar
  `vX.Y.Z` en `main` (`.github/workflows/publicar-imagen.yml`). Despliega
  siempre una versión concreta (`1.0.0`), nunca `latest`: así sabes qué corre y
  puedes volver atrás.
- **Una base de datos PostgreSQL 16** (Supabase u otra). Las migraciones se
  aplican solas al arrancar (Flyway), y todas las tablas quedan con Row Level
  Security, incluida la de Flyway.
- **Una sola instancia.** Los límites de peticiones se cuentan en memoria: con
  varias instancias, el límite efectivo se multiplica (ver README, Despliegue).

La imagen arranca con el perfil `prod`, como usuario sin privilegios (uid
10001), escucha en el puerto `8080` y expone la salud en `/actuator/health`.

## 2. Variables de entorno

### Obligatorias (la aplicación no arranca sin ellas)

| Variable | Qué es |
|---|---|
| `JWT_SECRET_BASE64` | **Secreto.** Clave de firma de los tokens: `openssl rand -base64 32`. Cambiarla cierra todas las sesiones. |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` | Conexión a Postgres. |
| `DB_PASSWORD` | **Secreto.** |
| `DB_SSLMODE` | `require` contra una base de datos gestionada (Supabase). |

### Para el primer arranque

| Variable | Qué es |
|---|---|
| `AUTH_CODIGO_ARRANQUE` | **Secreto**, 24 caracteres o más (`openssl rand -base64 24`). Permite crear el primer `ADMIN` (apartado 4). **Quítala** en cuanto exista. |

### Según el entorno

| Variable | Cuándo |
|---|---|
| `SERVER_FORWARD_HEADERS_STRATEGY=native` | Si hay un proxy o balanceador delante (casi todas las plataformas gestionadas). Sin ella, todas las peticiones parecen venir del proxy y comparten los límites. |
| `CORS_ALLOWED_ORIGINS` | El dominio de la app web (`https://app.granatum.es`). Vacío = ningún navegador de otro origen. |
| `DB_POOL_MAX_SIZE` | Al menos el doble de `AUTH_HASH_CONCURRENCIA` (la app se niega a arrancar si no). Con el *transaction pooler* de Supabase, ver README → Supabase. |
| `AUTH_ARGON2_MEMORY_KB`, `AUTH_ARGON2_ITERATIONS` | Recalibrados en la máquina de destino (apartado 5). |
| `ANTHROPIC_API_KEY` | **Secreto.** Solo si se usa el reconocimiento de facturas, y tras el contrato de encargado del tratamiento con Anthropic. Sin ella, la facturación funciona en modo manual. |
| `OPENAPI_ENABLED=true` | Solo si se quiere exponer `/v3/api-docs` y Swagger UI en ese entorno. |

**Nunca** `SPRING_PROFILES_ACTIVE=dev` en un despliegue: serviría
`POST /api/dev/token`, que emite tokens de cualquier rol sin credencial. Por eso
no se reutiliza el `.env` local. El resto de variables (límites, plazos) tiene
valores por defecto razonables; la lista completa está en `.env.example`.

## 3. Primer arranque

1. Crea la base de datos y define las variables del apartado 2.
2. Arranca la imagen. En el log deben aparecer, en este orden:
   - `The following 1 profile is active: "prod"`;
   - `Successfully applied N migrations` (23 en la 1.0.0);
   - `Row Level Security activado en flyway_schema_history` (solo la primera vez).
3. Comprueba:

   ```bash
   curl -s https://<host>/actuator/health      # {"status":"UP"}
   curl -s https://<host>/actuator/info        # la versión que esperabas
   curl -s -o /dev/null -w "%{http_code}\n" -X POST "https://<host>/api/dev/token?role=ADMIN"   # 404
   ```

## 4. El primer `ADMIN` (el propietario)

Es la única persona que se registra; al resto se la da de alta (apartado 4 bis).

```bash
curl -s -X POST https://<host>/api/auth/registro -H 'Content-Type: application/json' \
  -d '{"email":"…","password":"…","nombre":"…","documentoIdentidad":"…","codigoArranque":"<AUTH_CODIGO_ARRANQUE>"}'
```

Responde `201` y ya se puede iniciar sesión como `ADMIN`. Después:

1. **Quita `AUTH_CODIGO_ARRANQUE`** del entorno y reinicia. Ya no sirve (deja de
   funcionar en cuanto hay un `ADMIN`), pero un secreto sin uso es un riesgo sin
   beneficio.
2. **Da de alta un segundo `ADMIN`** cuanto antes (`POST /api/auth/altas` con
   `"rol": "ADMIN"`): no hay recuperación de contraseña por correo. El
   procedimiento de emergencia, en el README (*El primer administrador*).
3. Corrige la ficha del primer `ADMIN` (puesto, contrato, fecha de alta) con
   `PUT /api/empleados/{id}`.

## 4 bis. Dar de alta al personal

Cada persona la da de alta un `ADMIN` con `POST /api/auth/altas` (ficha y
acceso en un paso). La respuesta trae la contraseña provisional una sola vez;
entrégasela en persona. Al entrar, la aplicación le obliga a cambiarla.

## 5. Ajustar Argon2 a la máquina

Los valores por defecto (64 MiB, 3 iteraciones) cuestan ~110 ms en un Mac mini;
en un contenedor pequeño pueden costar varias veces más, y cada inicio de
sesión los paga. Mide en la máquina real con el procedimiento de
`specs/002-auth/research.md` ("Cómo se midió") y ajusta `AUTH_ARGON2_*` para
quedarte entre 100 y 300 ms. Recuerda: la memoria es **por inicio de sesión
simultáneo** (`AUTH_HASH_CONCURRENCIA`, 4 por defecto).

## 6. Copias de seguridad

El registro de jornada tiene valor legal (RD-ley 8/2019): hay que conservarlo
cuatro años y entregarlo a la Inspección si lo pide. Perder la base de datos es
un incumplimiento, no solo una molestia.

- **Diarias y automáticas.** En Supabase, las del plan (y *Point in Time
  Recovery* si el plan lo permite). En otra plataforma, un `pg_dump` diario
  fuera de la máquina de la base de datos:

  ```bash
  pg_dump --format=custom --no-owner "$DATABASE_URL" > granatum-$(date +%F).dump
  ```

- **Conservación**: al menos 30 días de diarias y una mensual durante cuatro
  años. Una copia guarda lo que había ese día, también lo que la depuración
  borre después: protégelas como la propia base de datos (contienen DNI y
  correos).
- **Prueba la restauración** antes de necesitarla, y luego cada trimestre:

  ```bash
  createdb granatum_restauracion
  pg_restore --no-owner --dbname=granatum_restauracion granatum-AAAA-MM-DD.dump
  ```

  y arranca la imagen contra ella: si Flyway dice "Schema is up to date" y
  `/actuator/health` responde `UP`, la copia sirve.

## 7. Actualizar a una versión nueva

1. Haz una copia de la base de datos (apartado 6).
2. Cambia la imagen a la nueva versión (`:1.1.0`) y reinicia. Flyway aplica las
   migraciones nuevas al arrancar.
3. Comprueba `/actuator/info` y `/actuator/health`.

**Volver atrás**: las migraciones solo van hacia delante. Si una versión trae
migraciones, volver a la imagen anterior no deshace el esquema; la vuelta segura
es restaurar la copia del paso 1 y arrancar la imagen anterior. Por eso el paso 1
no es opcional.

## 8. Lista de comprobación

- [ ] Imagen con versión concreta, no `latest`.
- [ ] `JWT_SECRET_BASE64`, `DB_PASSWORD` como secretos de la plataforma.
- [ ] Sin `SPRING_PROFILES_ACTIVE=dev`; `POST /api/dev/token` responde `404`.
- [ ] `SERVER_FORWARD_HEADERS_STRATEGY=native` si hay proxy.
- [ ] `CORS_ALLOWED_ORIGINS` con el dominio de la app web.
- [ ] Argon2 medido en la máquina de destino.
- [ ] Propietario registrado como primer `ADMIN`, `AUTH_CODIGO_ARRANQUE` retirado, segundo `ADMIN` dado de alta.
- [ ] Copias diarias activas y una restauración probada.
- [ ] Decidido si se activa la depuración a los 4 años (`TIMETRACKING_RETENCION_HABILITADA`; ver README).
