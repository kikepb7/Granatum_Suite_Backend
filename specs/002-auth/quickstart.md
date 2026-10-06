# Quickstart: validar el inicio de sesión real

**Spec**: [spec.md](./spec.md) | **Contrato**: [contracts/README.md](./contracts/README.md) | **Decisiones**: [research.md](./research.md)

Guía de validación extremo a extremo. Cubre los 14 criterios de éxito con
comandos ejecutables; los detalles de cada cuerpo están en el contrato y no se
repiten aquí.

> **Nota aprendida de la feature 001**: usa **instantes recientes** en cualquier
> fichaje de esta guía. El validador de reloj rechaza desviaciones de más de 72
> horas, así que una fecha fija copiada de un documento falla — y fallaba en el
> quickstart de la 001 hasta que se corrigió.

---

## 0. Requisitos previos

```bash
docker compose up -d        # Postgres en localhost:5432
```

`.env` en la raíz con, al menos:

```bash
DB_PASSWORD=...
JWT_SECRET_BASE64=$(openssl rand -base64 32)
```

Ningún secreto tiene valor por defecto (principio VI): si falta uno, la
aplicación **no arranca**, que es la avería correcta.

```bash
./gradlew build                        # suite completa en verde
./gradlew :app:bootRun                 # perfil dev por defecto
```

Variables propias de esta feature, todas con valor razonable para desarrollo:

| Variable | Defecto | Qué hace |
|----------|---------|----------|
| `AUTH_REFRESH_EXPIRATION_DAYS` | 30 | Vida del token de renovación ([D-017](./research.md#d-017-vida-de-los-tokens)) |
| `JWT_EXPIRATION_MINUTES` | 15 | Vida del token de acceso, igual que Squadfy_Backend. En `dev` el defecto es 1000, pero `.env.example` fija 15 y la variable de entorno gana al fichero de perfil, así que siguiendo el README `dev` también va a 15 — lo útil para ejercitar la renovación |
| `AUTH_ARGON2_MEMORY_KB` | 65536 | Memoria por verificación ([D-002](./research.md#d-002-parámetros-de-argon2id--m64-mib-t3-p1)) |
| `AUTH_ARGON2_ITERATIONS` | 3 | Iteraciones |
| `AUTH_HASH_CONCURRENCIA` | 4 | Verificaciones simultáneas ([D-003](./research.md#d-003-límite-de-verificaciones-simultáneas-consecuencia-de-d-002)) |
| `AUTH_HASH_ESPERA_MS` | 1000 | Cuánto espera un login en cola antes de recibir `503`; derivado del presupuesto de 2 s de SC-011 |
| `AUTH_PURGA_SESIONES_DIAS` | 30 | Antigüedad para purgar sesiones muertas |

**Calibra Argon2 en el hardware de destino antes de desplegar.** La tabla de
D-002 se midió en un Mac mini de 10 núcleos; un contenedor pequeño dará números
muy distintos y el apartado *Cómo se midió* de `research.md` trae el programa.

---

## 1. Alta de una persona y de su acceso — SC-013

Hace falta un `ADMIN`. Mientras `/api/dev/token` siga existiendo es la vía más
corta para arrancar el ciclo; en cuanto haya un `ADMIN` real, usa su sesión.

```bash
ADMIN=$(curl -s -X POST 'http://localhost:8080/api/dev/token?role=ADMIN' | jq -r .accessToken)

# La persona empleada vive en timetracking; su identificador es el sujeto del token.
EMPLEADO=$(curl -s -X POST http://localhost:8080/api/empleados \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"nombre":"Ana Prueba","documentoIdentidad":"12345678Z","puesto":"Florista",
       "tipoContrato":"JORNADA_COMPLETA","fechaAlta":"2026-10-01"}' | jq -r .id)

# Dar acceso: UNA sola operación (FR-029b).
ALTA=$(curl -s -X POST http://localhost:8080/api/auth/cuentas \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"empleadoId\":\"$EMPLEADO\",\"email\":\"ana@granatum.es\"}")
TEMPORAL=$(echo "$ALTA" | jq -r .passwordTemporal)
echo "$TEMPORAL"   # la única vez que se ve; no se almacena en claro ni se registra
```

**Esperado**: `201` con `passwordTemporal` de 16 caracteres.

Los dos rechazos que pide SC-013:

```bash
# Persona que no existe -> 404 EMPLEADO_NO_ENCONTRADO
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/auth/cuentas \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"empleadoId":"00000000-0000-0000-0000-000000000000","email":"nadie@granatum.es"}'

# Segunda cuenta para quien ya la tiene -> 409 CUENTA_YA_EXISTE
curl -s -X POST http://localhost:8080/api/auth/cuentas \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"empleadoId\":\"$EMPLEADO\",\"email\":\"otra@granatum.es\"}" | jq -r .code
```

---

## 2. Primer acceso: solo se puede cambiar la contraseña — SC-008, SC-014

```bash
PAR=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"ana@granatum.es\",\"password\":\"$TEMPORAL\"}")
echo "$PAR" | jq '{requiereCambioPassword, expiresIn}'   # -> true, 900
ACCESO=$(echo "$PAR" | jq -r .accessToken)
```

Cualquier otra operación se rechaza (FR-020):

```bash
curl -s -o /dev/null -w 'fichar con token pendiente: %{http_code}\n' \
  -X POST http://localhost:8080/api/fichajes/entrada \
  -H "Authorization: Bearer $ACCESO" -H 'Content-Type: application/json' \
  -d "{\"clientEventId\":\"$(uuidgen)\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
```

**Esperado**: `403`. El token no lleva `ROLE_EMPLEADO`, solo `PWD_CHANGE_ONLY`
([D-010](./research.md#d-010-pendiente-de-cambio-viaja-en-el-token-y-restringe-la-autoridad)).

Política de contraseña (FR-023, SC-014) — fíjate en que la respuesta **enumera
los requisitos y no reproduce la contraseña**:

```bash
curl -s -X POST http://localhost:8080/api/auth/change-password \
  -H "Authorization: Bearer $ACCESO" -H 'Content-Type: application/json' \
  -d "{\"passwordActual\":\"$TEMPORAL\",\"passwordNueva\":\"abc\"}" | jq
# -> 422 PASSWORD_DEBIL, requisitos: [LONGITUD_MINIMA, FALTA_MAYUSCULA, FALTA_DIGITO, FALTA_SIMBOLO]
```

Y el cambio bueno:

```bash
NUEVO=$(curl -s -X POST http://localhost:8080/api/auth/change-password \
  -H "Authorization: Bearer $ACCESO" -H 'Content-Type: application/json' \
  -d "{\"passwordActual\":\"$TEMPORAL\",\"passwordNueva\":\"Granatum-2026!\"}")
echo "$NUEVO" | jq .requiereCambioPassword     # -> false
ACCESO=$(echo "$NUEVO" | jq -r .accessToken)
REFRESH=$(echo "$NUEVO" | jq -r .refreshToken)
```

Comprueba también una frase larga, que es la razón de elegir Argon2
([D-001](./research.md#d-001-argon2id-en-lugar-de-bcrypt)): una contraseña de 64
caracteres **acentuados** debe aceptarse. Con BCrypt este caso devolvía `500`.

---

## 3. Entrar y fichar — SC-001

El criterio que da sentido a la feature: una jornada completa **sin**
`/api/dev/token`.

```bash
AHORA=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FICHAJE=$(curl -s -X POST http://localhost:8080/api/fichajes/entrada \
  -H "Authorization: Bearer $ACCESO" -H 'Content-Type: application/json' \
  -d "{\"clientEventId\":\"$(uuidgen)\",\"occurredAt\":\"$AHORA\"}" | jq -r .id)

curl -s -X POST "http://localhost:8080/api/fichajes/$FICHAJE/salida" \
  -H "Authorization: Bearer $ACCESO" -H 'Content-Type: application/json' \
  -d "{\"clientEventId\":\"$(uuidgen)\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" \
  | jq '{estado, minutosTrabajados}'
```

**Esperado**: el fichaje se atribuye a `$EMPLEADO` sin que el cliente haya
enviado ningún identificador — sale del sujeto del token (principio IV).

---

## 4. Renovar, un solo uso, cerrar sesión — SC-004, SC-006

```bash
R1=$(curl -s -X POST http://localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
REFRESH2=$(echo "$R1" | jq -r .refreshToken)

# El anterior ya no sirve (FR-008, SC-004).
curl -s -X POST http://localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}" | jq -r .code
# -> TOKEN_RENOVACION_INVALIDO
```

Dos dispositivos, para SC-006 — cerrar uno no toca el otro:

```bash
login() { curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@granatum.es","password":"Granatum-2026!"}'; }

MOVIL=$(login | jq -r .refreshToken)
WEB=$(login | jq -r .refreshToken)

curl -s -o /dev/null -w 'logout movil: %{http_code}\n' -X POST http://localhost:8080/api/auth/logout \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$MOVIL\"}"     # 204

curl -s -X POST http://localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$MOVIL\"}" | jq -r .code   # invalido

curl -s -X POST http://localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$WEB\"}" | jq -r .expiresIn  # 900
```

Y el cierre de sesión es idempotente: repetirlo devuelve `204`, no un error.

---

## 5. Correo inexistente y contraseña incorrecta, indistinguibles — SC-002

```bash
medir() {  # imprime el código y el tiempo total
  curl -s -o /tmp/cuerpo -w '%{http_code} %{time_total}\n' \
    -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' -d "$1"
  cat /tmp/cuerpo; echo
}

medir '{"email":"ana@granatum.es","password":"IncorrectaPero1!"}'
medir '{"email":"no-existe@granatum.es","password":"IncorrectaPero1!"}'
```

**Esperado**: el **mismo** `401`, el **mismo** cuerpo
`{"code":"CREDENCIALES_INVALIDAS",...}` y tiempos del mismo orden (~110 ms de
Argon2 en ambos). El segundo caso no tiene hash que comprobar, así que verifica
contra un **señuelo** precisamente para que el tiempo coincida; sin él la
diferencia sería trivial de medir y el inicio de sesión enumeraría correos
([D-005](./research.md#d-005-rechazo-uniforme-con-hash-siempre-también-para-cuentas-bloqueadas)).

Para una comparación seria, 30 repeticiones de cada uno y compara **medianas**:
una sola medida es ruido de red. El test automático lo hace así.

---

## 6. Bloqueo creciente — SC-005, SC-012

```bash
for i in $(seq 1 5); do
  curl -s -o /dev/null -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"ana@granatum.es","password":"Mal1!Mal1!"}'
done

# Sexto intento con la contraseña CORRECTA -> rechazado igual (FR-014).
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@granatum.es","password":"Granatum-2026!"}' | jq -r .code
# -> CREDENCIALES_INVALIDAS, indistinguible de una contraseña mala (decisión del plan, D-005)
```

**Esperado**: tras el minuto de bloqueo, la contraseña correcta vuelve a
funcionar sin intervención (FR-016).

El punto que sostiene todo lo demás es **FR-016c**: fallar *durante* el bloqueo
**no** lo prolonga. Compruébalo mirando `bloqueada_hasta` antes y después:

```bash
psql "postgresql://granatum@localhost:5432/granatum" -c \
  "SELECT nivel_bloqueo, intentos_fallidos, bloqueada_hasta FROM cuentas_acceso;"

curl -s -o /dev/null -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"email":"ana@granatum.es","password":"x"}'

psql "postgresql://granatum@localhost:5432/granatum" -c \
  "SELECT nivel_bloqueo, intentos_fallidos, bloqueada_hasta FROM cuentas_acceso;"
```

**Esperado**: las tres columnas **idénticas**. Si `bloqueada_hasta` se movió,
cualquiera puede dejar a una persona fuera indefinidamente sin conocer su
contraseña — y en este producto eso significa impedirle fichar y abrir un hueco
en un registro con valor legal.

Y la escalada: cumplido el bloqueo, otros 5 fallos dan 5 minutos; luego 15;
luego 60. Un inicio de sesión correcto pone el nivel a cero (FR-016b).

---

## 7. Persona inactiva — SC-010

```bash
curl -s -o /dev/null -X PATCH "http://localhost:8080/api/empleados/$EMPLEADO/activo" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"activo":false}'

curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@granatum.es","password":"Granatum-2026!"}' | jq -r .code
# -> CREDENCIALES_INVALIDAS (indistinguible, FR-003)

curl -s -X POST http://localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$WEB\"}" | jq -r .code
# -> EMPLEADO_INACTIVO (aquí SÍ se distingue: ya demostró ser titular)
```

**Esto es lo que prueba que el contrato `DirectorioEmpleados` funciona**: `auth`
no conoce la tabla `empleados` ni compila contra `timetracking`, y aun así
reacciona a una baja laboral.

---

## 8. Restablecer: cierra todo y levanta el bloqueo — SC-007

```bash
A=$(login | jq -r .refreshToken); B=$(login | jq -r .refreshToken)

NUEVA=$(curl -s -X POST "http://localhost:8080/api/auth/cuentas/$EMPLEADO/restablecer" \
  -H "Authorization: Bearer $ADMIN" | jq -r .passwordTemporal)

for t in "$A" "$B"; do
  curl -s -X POST http://localhost:8080/api/auth/refresh \
    -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$t\"}" | jq -r .code
done
# -> TOKEN_RENOVACION_INVALIDO las dos veces (FR-025, SC-007)

curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"ana@granatum.es\",\"password\":\"$NUEVA\"}" | jq .requiereCambioPassword
# -> true; y el bloqueo, si lo había, está levantado (FR-026)
```

Quien no sea `ADMIN` recibe `403` (US5 escenario 4).

---

## 9. Ni contraseñas ni tokens ni correos en los logs — SC-009, SC-003

```bash
./gradlew :app:bootRun --args='--logging.level.root=DEBUG' > /tmp/granatum.log 2>&1 &
# ...repite los apartados 1 a 8...
grep -ci -E 'Granatum-2026!|ana@granatum\.es' /tmp/granatum.log   # -> 0
```

**Esperado**: cero coincidencias, incluso con la raíz en `DEBUG` — que es
exactamente el estado en el que se diagnostica una avería en producción. Los dos
*loggers* de Hibernate que imprimen valores por reflexión están fijados en
`WARN` y sobreviven a subir la raíz. En la feature 001 así se descubrió que
`EntityPrinter` filtraba el documento de identidad **esquivando** el `toString()`
de la entidad.

Y SC-003, que ninguna contraseña sea recuperable:

```bash
psql "postgresql://granatum@localhost:5432/granatum" -c \
  "SELECT left(password_hash, 40), requiere_cambio_password FROM cuentas_acceso;"
# -> {argon2}$argon2id$v=19$m=65536,t=3,p=1$...
```

Dos cuentas con la **misma** contraseña deben tener hashes **distintos**: es la
prueba de que hay sal por fila.

---

## 10. RLS: Postgres no publica estas tablas — principio VII

```bash
psql "postgresql://granatum@localhost:5432/granatum" -c \
  "SELECT relname, relrowsecurity FROM pg_class c
     JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname='public' AND relkind='r'
      AND relname IN ('cuentas_acceso','sesiones_renovacion','eventos_seguridad');"
```

**Esperado**: `relrowsecurity = t` en las tres. Importa más aquí que en cualquier
otra tabla del sistema: `cuentas_acceso` guarda correos y hashes de contraseña, y
la API de datos de Supabase la publicaría con la clave anónima, que es pública
por diseño.

---

## 11. Cuentas huérfanas — FR-029c

```bash
curl -s http://localhost:8080/api/auth/cuentas/huerfanas \
  -H "Authorization: Bearer $ADMIN" | jq
```

**Esperado**: `{"total": 0, "cuentas": []}` en un sistema sano. Existe porque
**la base de datos no puede impedirlas**: sin clave ajena entre módulos, una
persona puede dejar de existir después de crearse la cuenta. La respuesta no
incluye el correo, que sería un dato personal innecesario en un diagnóstico.

---

## Cobertura de los criterios de éxito

| Criterio | Apartado |
|----------|----------|
| SC-001 entrar y fichar | [3](#3-entrar-y-fichar--sc-001) |
| SC-002 respuestas indistinguibles | [5](#5-correo-inexistente-y-contraseña-incorrecta-indistinguibles--sc-002) |
| SC-003 hashes salados | [9](#9-ni-contraseñas-ni-tokens-ni-correos-en-los-logs--sc-009-sc-003) |
| SC-004 token de un solo uso | [4](#4-renovar-un-solo-uso-cerrar-sesión--sc-004-sc-006) |
| SC-005 bloqueo tras 5 fallos | [6](#6-bloqueo-creciente--sc-005-sc-012) |
| SC-006 cierre de una sola sesión | [4](#4-renovar-un-solo-uso-cerrar-sesión--sc-004-sc-006) |
| SC-007 restablecer cierra todo | [8](#8-restablecer-cierra-todo-y-levanta-el-bloqueo--sc-007) |
| SC-008 sesión pendiente limitada | [2](#2-primer-acceso-solo-se-puede-cambiar-la-contraseña--sc-008-sc-014) |
| SC-009 sin secretos en logs | [9](#9-ni-contraseñas-ni-tokens-ni-correos-en-los-logs--sc-009-sc-003) |
| SC-010 persona inactiva fuera | [7](#7-persona-inactiva--sc-010) |
| SC-011 inicio < 2 s | [5](#5-correo-inexistente-y-contraseña-incorrecta-indistinguibles--sc-002) (`time_total`) |
| SC-012 bloqueo creciente y FR-016c | [6](#6-bloqueo-creciente--sc-005-sc-012) |
| SC-013 una sola operación de alta | [1](#1-alta-de-una-persona-y-de-su-acceso--sc-013) |
| SC-014 política siempre aplicada | [2](#2-primer-acceso-solo-se-puede-cambiar-la-contraseña--sc-008-sc-014) |

Los 14 están además cubiertos por tests automáticos; esta guía sirve para
comprobar la aplicación **en marcha**, que es cómo se encontró la caída de
Jackson 3 en la feature 001 — una avería total que la suite entera no veía.
