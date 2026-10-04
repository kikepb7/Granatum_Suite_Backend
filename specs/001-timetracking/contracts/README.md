# Phase 1 — Contratos de API: 001-timetracking

Contrato HTTP del módulo. Formato de error, autorización e idempotencia son
transversales y se describen una vez aquí arriba; cada endpoint solo documenta
lo propio.

Ficheros:

- [`fichajes.md`](fichajes.md) — registro de jornada y consulta
- [`correcciones.md`](correcciones.md) — solicitudes y resolución
- [`empleados.md`](empleados.md) — gestión de personal

---

## Convenciones transversales

### Prefijo y formato de error

Todo bajo `/api` (principio VIII). Los errores tienen **una sola forma**,
emitida siempre desde un `@RestControllerAdvice`, nunca construida en un
controller:

```json
{ "code": "CODIGO_ESTABLE", "message": "texto legible para personas" }
```

`code` es estable y consumible por el cliente; `message` es para personas y
puede cambiar sin previo aviso.

### Códigos de error del módulo

| `code` | HTTP | Cuándo |
|--------|------|--------|
| `FICHAJE_NOT_FOUND` | 404 | El fichaje no existe |
| `EMPLEADO_NOT_FOUND` | 404 | El empleado no existe |
| `SOLICITUD_NOT_FOUND` | 404 | La solicitud no existe |
| `FICHAJE_YA_EN_CURSO` | 409 | FR-002: ya hay una jornada abierta |
| `PAUSA_YA_ABIERTA` | 409 | FR-004: ya hay una pausa sin cerrar |
| `PAUSA_NO_ABIERTA` | 409 | Se intenta cerrar una pausa que no está abierta |
| `PAUSA_ABIERTA_AL_CERRAR` | 409 | FR-006: hay una pausa sin terminar al fichar salida |
| `FICHAJE_NO_EN_CURSO` | 409 | La operación exige un fichaje `EN_CURSO` |
| `FICHAJE_NO_FINALIZADO` | 409 | FR-013: solo se corrigen fichajes finalizados |
| `FICHAJE_INMUTABLE` | 409 | FR-018: modificación que no viene de una corrección aprobada |
| `SOLICITUD_YA_RESUELTA` | 409 | FR-019 |
| `EMPLEADO_INACTIVO` | 409 | FR-010 |
| `DOCUMENTO_DUPLICADO` | 409 | FR-029 |
| `VALORES_INCOHERENTES` | 422 | FR-011, FR-020b: salida antes de entrada, pausas solapadas, horas negativas |
| `UBICACION_NO_CORREGIBLE` | 422 | FR-020a |
| `DESVIACION_RELOJ` | 422 | FR-026: fuera de la tolerancia. **Distinguible a propósito** para que la app pueda avisar de que el reloj del dispositivo está mal |
| `CLIENT_EVENT_ID_REUTILIZADO` | 409 | Misma clave de idempotencia con cuerpo distinto |
| `VALIDATION_ERROR` | 400 | Validación de formato de la petición |
| `FORBIDDEN` | 403 | Rol o propiedad insuficientes |
| `UNAUTHORIZED` | 401 | Sin token válido |

Los mensajes **nunca** incluyen documento de identidad ni ubicación
(principio VI). Un error de validación referencia el campo, no su valor.

### Autorización

| Rol | Alcance |
|-----|---------|
| `ADMIN` | Todo, incluido el CRUD de empleados |
| `ENCARGADO` | Fichajes de todo el personal y resolución de correcciones. **No** gestiona empleados |
| `EMPLEADO` | Solo sus propios fichajes y sus propias solicitudes |
| `REPRESENTANTE` | **Solo lectura** de la jornada de toda la plantilla, **sin ubicación**. Ninguna escritura, en ningún endpoint |

`REPRESENTANTE` recibe `403` en todo lo que no sea una consulta de jornada o un
resumen mensual, incluidos `POST /api/fichajes/entrada` (no ficha, porque no es
personal de la empresa a estos efectos) y toda operación sobre correcciones.
Sus respuestas de fichaje **omiten los campos de ubicación**, no los devuelven a
`null`: la diferencia importa, porque `null` sería indistinguible de un fichaje
sin ubicación registrada.

**La identidad sale del sujeto del JWT, nunca del cuerpo ni de la ruta**
(FR-022). Donde un endpoint lleva `{empleadoId}` en la ruta, para un `EMPLEADO`
se compara contra el sujeto del token y se responde `403` si no coincide. El
identificador de la ruta está ahí por legibilidad de la API; no es fuente de
autoridad.

### Idempotencia (FR-024, FR-025)

Las cuatro operaciones de fichaje (entrada, inicio de pausa, fin de pausa,
salida) llevan en su cuerpo:

| Campo | Tipo | Obligatorio | Significado |
|-------|------|-------------|-------------|
| `clientEventId` | UUID | sí | Clave de idempotencia generada por el cliente |
| `occurredAt` | instante ISO-8601 con zona | sí | Cuándo ocurrió **de verdad**, según el dispositivo |

Comportamiento:

1. `clientEventId` nuevo → se procesa y se guarda la respuesta.
2. `clientEventId` repetido **con el mismo cuerpo** → se devuelve la respuesta
   original tal cual, sin crear ni modificar nada. Misma forma y mismo código de
   estado que la primera vez.
3. `clientEventId` repetido **con cuerpo distinto** → `409`
   `CLIENT_EVENT_ID_REUTILIZADO`. El cliente reutilizó una clave para otra
   operación; resolverlo en silencio le devolvería la respuesta de otra cosa.

El servidor registra además `receivedAt`. **El cómputo de la jornada usa
`occurredAt`**, no `receivedAt` (FR-025).

Tolerancia de reloj (FR-026), configurable por entorno:

| Dirección | Límite por defecto | Si se excede |
|-----------|--------------------|--------------|
| Futuro | 5 minutos | `422 DESVIACION_RELOJ` |
| Pasado | 72 horas | `422 DESVIACION_RELOJ` |

### Tiempo

Todos los instantes viajan en ISO-8601 con zona (`2026-10-05T07:00:00Z`) y se
almacenan en UTC. Los parámetros `desde` y `hasta` son **fechas civiles**
(`2026-10-05`) e **se interpretan en `Europe/Madrid`**: `desde` es el inicio de
ese día en Madrid y `hasta` el fin del suyo, inclusive. El día del cambio de
hora tiene 23 o 25 horas y el rango lo refleja (research.md §6).
