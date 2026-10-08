# Contrato HTTP: Endurecimiento

## `429 DEMASIADAS_PETICIONES`

Cualquier ruta bajo `/api` puede responder:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 6
Content-Type: application/json

{ "code": "DEMASIADAS_PETICIONES", "message": "Demasiadas peticiones. Espera unos segundos y vuelve a intentarlo." }
```

`Retry-After` en segundos, entero, mínimo 1. La petición no ha hecho nada: se
puede repetir tal cual.

## Errores genéricos (`/error`)

Lo que no responde un manejador de un módulo —ruta inexistente, método no
admitido, excepción no controlada— responde con el formato único, sin traza ni
detalle interno:

| Estado | `code` |
|---|---|
| 400 | `VALIDACION` |
| 404 | `RECURSO_NO_ENCONTRADO` |
| 405 | `METODO_NO_PERMITIDO` |
| 415 | `TIPO_NO_ADMITIDO` |
| 500 | `ERROR_INTERNO` |
| otro | `ERROR_<estado>` |

## Cabeceras en todas las respuestas

```http
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'
Referrer-Policy: no-referrer
Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()
Strict-Transport-Security: max-age=31536000 ; includeSubDomains   # solo en HTTPS
```

## CORS

Solo los orígenes de `CORS_ALLOWED_ORIGINS`. Comprobación previa de un origen
admitido → `200` con `Access-Control-Allow-Origin` igual al origen,
`Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE`,
`Access-Control-Allow-Headers: Authorization, Content-Type`,
`Access-Control-Max-Age: 3600`. Respuestas reales con
`Access-Control-Expose-Headers: Content-Disposition, Retry-After`. Un origen no
admitido → `403`.
