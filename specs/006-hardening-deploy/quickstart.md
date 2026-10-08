# Quickstart: validar el endurecimiento y la imagen

**Spec**: [spec.md](./spec.md) | **Contrato**: [contracts/README.md](./contracts/README.md)

## 1. Límite de inicio de sesión — US1

Con la aplicación en marcha (`./gradlew :app:bootRun`):

```bash
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code} " -X POST localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' -d '{"email":"nadie@granatum.es","password":"x"}'
done; echo
```

**Esperado**: diez `401` y después `429`, con `Retry-After`.

## 2. Perfil por defecto — US2

```bash
docker compose --profile app up -d --build
docker compose --profile app logs app | grep "profile is active"
curl -s -o /dev/null -w "%{http_code}\n" -X POST "localhost:8080/api/dev/token?role=ADMIN"
```

**Esperado**: `The following 1 profile is active: "prod"` y la ruta de
desarrollo responde `404`, nunca un token. Una ruta inexistente responde `{code,
message}` sin traza.

> Ojo con `docker run --env-file .env`: el `.env` local trae
> `SPRING_PROFILES_ACTIVE=dev` y se impondría al `prod` de la imagen. Para un
> despliegue usa un fichero de entorno propio, sin esa línea; `docker compose`
> ya fija `prod` en el servicio `app`.

## 3. Cabeceras y CORS — US3

```bash
curl -sI localhost:8080/actuator/health
curl -s -o /dev/null -w "%{http_code}\n" -X OPTIONS localhost:8080/api/auth/login \
  -H 'Origin: https://otro.example' -H 'Access-Control-Request-Method: POST'
```

**Esperado**: las cabeceras del contrato; el origen no configurado → `403`.

## 4. Imagen — US4

```bash
docker compose --profile app exec app id -u
docker inspect --format '{{.State.Health.Status}}' $(docker compose --profile app ps -q app)
docker compose --profile app exec app ls /app
```

**Esperado**: `10001`, `healthy`, y en `/app` solo `BOOT-INF`, `META-INF` y
`org` (las capas del jar), sin `.env` ni código fuente.
