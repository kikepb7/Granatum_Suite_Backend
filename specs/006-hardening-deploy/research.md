# Research: Endurecimiento y despliegue

**Fecha**: 2026-10-08 | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

## D-001 — Limitador propio: cubeta de fichas en memoria

**Decisión**: una cubeta de fichas por (cupo, dirección): capacidad `N`, se
rellena de forma continua a `N` fichas por periodo. Cada petición consume una;
sin fichas, `429` con `Retry-After` = segundos hasta la siguiente ficha
(redondeado hacia arriba, mínimo 1). Reloj inyectado para probarla sin esperas.

Por cupo, un mapa LRU acotado (`auth`: 10 000 direcciones por defecto) bajo un
cerrojo. Al llenarse se olvida la dirección menos usada: la memoria nunca crece
sin límite, aunque alguien invente direcciones.

**Por qué propio y no Bucket4j**: son unas decenas de líneas sin dependencias, y
la constitución pide que la infraestructura responda a una necesidad demostrada.
Una biblioteca aportaría sobre todo contadores distribuidos, que se descartan
(un solo proceso).

**Por qué cubeta y no ventana fija**: una ventana fija admite el doble del cupo
en el cambio de ventana (N al final de una y N al principio de la siguiente).

## D-002 — La dirección del cliente

**Decisión**: `request.remoteAddr`, sin leer cabeceras a mano. Detrás de un proxy
se activa `server.forward-headers-strategy=native`
(`SERVER_FORWARD_HEADERS_STRATEGY`), que hace que Tomcat sustituya
`remoteAddr` por la dirección de `X-Forwarded-For` **solo si la petición viene de
un proxy de confianza** (redes internas por defecto). Por defecto: `none`.

**Por qué**: leer `X-Forwarded-For` siempre permitiría a cualquiera inventarse
una dirección por petición y saltarse el límite. Tomcat ya resuelve la confianza
en el proxy; reimplementarlo sería repetir su lógica peor.

**Datos personales**: la dirección es un dato personal. Solo vive en memoria,
como clave del mapa, mientras está en él. No se registra en logs ni en base de
datos; el filtro no escribe ningún log.

## D-003 — Cupos por defecto

| Cupo | Rutas | Defecto | Variable |
|---|---|---|---|
| `login` | `POST /api/auth/login` | 10 / minuto | `SEGURIDAD_LIMITE_LOGIN` |
| `sesion` | `POST /api/auth/refresh`, `POST /api/auth/logout` | 30 / minuto | `SEGURIDAD_LIMITE_SESION` |
| `registro` | `POST /api/auth/registro` | 5 / hora | `SEGURIDAD_LIMITE_REGISTRO` |
| `general` | `/api/**` | 300 / minuto | `SEGURIDAD_LIMITE_GENERAL` |

Una petición a `login` consume de `login` **y** de `general`. `/actuator/**` no
está bajo `/api`, así que la comprobación de salud nunca se limita (US5).

Una tienda con toda la plantilla detrás de una sola dirección: 10 inicios de
sesión por minuto bastan para un cambio de turno, y la app móvil renueva en
segundo plano (30/min sobra).

## D-004 — Perfil por defecto `prod`

**Decisión**: `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:prod}`. La tarea
`bootRun` fija `SPRING_PROFILES_ACTIVE=dev` si ni el entorno ni `.env` lo dicen.
La imagen lo fija a `prod` explícitamente.

**Por qué**: `POST /api/dev/token` emite tokens de cualquier rol sin credencial.
Con `dev` por defecto, olvidar una variable en el despliegue era suplantar a
cualquiera. El principio VI ya exige que la ruta no exista en `prod`; ahora
tampoco existe si nadie dice nada.

**Tests**: las tareas de test cargan `.env` (localmente `dev`); en la CI, sin
`.env`, corren con `prod`. Ningún test depende del emisor de desarrollo salvo
`DevAuthControllerProfileTest`, que fija sus perfiles.

## D-005 — Errores sin trazas y con formato único

**Decisión**: `server.error.include-stacktrace: never`, `include-message: never`,
`include-exception: false`, `include-binding-errors: never` en todos los
perfiles; y un `ErrorAttributes` propio para que lo que llega a `/error` (ruta
inexistente, método no admitido, excepción no controlada) responda `{code,
message}` con un mensaje genérico por estado.

**Por qué no un `@ExceptionHandler(Exception::class)`**: se adelantaría al
resolutor de Spring y convertiría en `500` los `404`/`405`/`415` que hoy salen
bien, y a las excepciones de seguridad que deben llegar a `EntryPointJson`.

## D-006 — Código de arranque corto

**Decisión**: un componente de `auth` que, al arrancar, falla si
`auth.registro.codigo-arranque` no está vacío y tiene menos de 24 caracteres. El
mensaje nombra `AUTH_CODIGO_ARRANQUE` y no repite el valor.

**Por qué 24**: `openssl rand -base64 18` da 24 caracteres (144 bits). Con el
límite de registro (5/hora por dirección), adivinarlo no es viable; uno corto
("granatum2026") sí.

## D-007 — Cabeceras

En `SecurityConfig`, además de las que Spring Security ya pone
(`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control:
no-store`, HSTS en peticiones seguras):

- `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`
  — la API sirve JSON, CSV y PDF: nada debe ejecutarse ni cargarse.
- `Referrer-Policy: no-referrer`.
- `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`.
- HSTS: un año, con subdominios, solo en conexión segura.

## D-008 — CORS

`seguridad.cors.origenes` (`CORS_ALLOWED_ORIGINS`, lista separada por comas).
Siempre se registra una configuración: con la lista vacía, **cualquier**
petición CORS se rechaza con `403` (por defecto, nadie). Métodos `GET`, `POST`,
`PUT`, `PATCH`, `DELETE`; cabeceras `Authorization`, `Content-Type`; expuestas
`Content-Disposition`, `Retry-After`; sin credenciales (el token viaja en la
cabecera, no en cookies); `max-age` una hora. La app móvil no envía `Origin` y no
se ve afectada.

## D-009 — Imagen

Multietapa:

1. `eclipse-temurin:21-jdk`: copia primero el wrapper y los `build.gradle.kts`
   (capa cacheable), después el código; `./gradlew :app:bootJar -x test`.
   Extrae las capas del jar (`-Djarmode=tools extract --layers --launcher`).
2. `eclipse-temurin:21-jre-alpine`: usuario `granatum` sin privilegios, las capas
   en orden de menos a más cambiante, `SPRING_PROFILES_ACTIVE=prod`,
   `-XX:MaxRAMPercentage=75`, `HEALTHCHECK` con `wget` contra
   `/actuator/health`.

Los tests no corren dentro de la imagen: los corre la CI antes, con Postgres y
Testcontainers, que dentro de un `docker build` no hay.

`.dockerignore` excluye `.git`, `.env`, `build/`, `.gradle/`, `specs/`, `docs/`,
`.claude/` y `.specify/`.

## D-010 — CI

`on: push` en todas las ramas y `pull_request` contra cualquiera. Un segundo
trabajo, tras el build, construye la imagen y comprueba que el proceso no corre
como `root` y que el fichero `.env` no está dentro.
