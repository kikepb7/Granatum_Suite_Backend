# Contrato de la API: auth

**Spec**: [../spec.md](../spec.md) | **Decisiones**: [../research.md](../research.md)

Todas las rutas bajo `/api` (principio VIII). Errores en el formato único
`{ "code": "<CODIGO_ESTABLE>", "message": "<texto legible>" }`, emitido desde
`AuthExceptionHandler` y **nunca** construido en un controlador.

`AuthExceptionHandler` lleva `@Order(Ordered.HIGHEST_PRECEDENCE)`. No es
decorativo: las excepciones de este módulo extienden las de `common`, y
`CommonExceptionHandler` tiene un manejador de `InvalidOperationException` que
encaja con todas ellas. Spring consulta los `@RestControllerAdvice` por orden y
se queda con el primero que encaje, así que sin precedencia explícita **cada
conflicto saldría como `400 INVALID_OPERATION`** en lugar de su código. Ya pasó
en la feature de jornada.

## Mezcla de idiomas en las rutas

`login`, `refresh`, `logout` y `change-password` están en inglés porque así las
pidió el responsable del producto de forma explícita; el resto del proyecto usa
nombres en español (`/api/materiales`, `/api/fichajes`, `/api/empleados`) y las
rutas nuevas lo siguen (`/api/auth/cuentas`). Se deja constancia de la
inconsistencia: es deliberada, no un descuido.

---

## Tabla de rutas y autorización

Declaradas en un único sitio, `SecurityConfig` de `app` (principio IV).

| Método | Ruta | Autorización | Requisitos |
|--------|------|--------------|-----------|
| `POST` | `/api/auth/login` | pública | FR-001..FR-006, FR-013..FR-016c |
| `POST` | `/api/auth/refresh` | pública | FR-007..FR-012 |
| `POST` | `/api/auth/logout` | pública | FR-009, FR-012 |
| `POST` | `/api/auth/change-password` | autenticada (**incluye** `PWD_CHANGE_ONLY`) | FR-020..FR-023b |
| `POST` | `/api/auth/cuentas` | `ADMIN` | FR-018, FR-027, FR-029b..FR-029d |
| `POST` | `/api/auth/cuentas/{empleadoId}/restablecer` | `ADMIN` | FR-024..FR-026 |
| `GET` | `/api/auth/cuentas/huerfanas` | `ADMIN` | FR-029c |
| *(cualquier ruta protegida)* | — | token de acceso válido | FR-006, ver [el rechazo de un token de acceso](#rechazo-de-un-token-de-acceso-fr-006) |

**`login`, `refresh` y `logout` son públicas porque la credencial va en el
cuerpo**, no en la cabecera `Authorization`. `logout` en particular: lo normal
es cerrar sesión cuando el token de acceso ya caducó, y exigirlo haría
imposible la operación justo cuando se necesita.

**Cambio en el comodín de `SecurityConfig`**:
`anyRequest().authenticated()` pasa a
`anyRequest().hasAnyRole("ADMIN", "ENCARGADO", "EMPLEADO", "REPRESENTANTE")`.
Un token pendiente de cambio **está** autenticado, así que con el comodín
anterior alcanzaría cualquier ruta futura sin regla de rol explícita
([D-010](../research.md#d-010-pendiente-de-cambio-viaja-en-el-token-y-restringe-la-autoridad)).

---

## `POST /api/auth/login`

**Petición**

```json
{ "email": "ana@granatum.es", "password": "Granatum1!" }
```

`email`: `@NotBlank @Email`, máx. 254. `password`: `@NotBlank`, máx. 128.
Validación en el borde con `@Valid` (principio VIII).

**`200 OK`**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "9rJ2xQ...",
  "expiresIn": 900,
  "requiereCambioPassword": false
}
```

`expiresIn` en segundos, para que el cliente programe la renovación sin
interpretar el JWT. `requiereCambioPassword` es lo que US4 escenario 2 exige
que la respuesta indique.

**Errores**

| Estado | `code` | Cuándo |
|--------|--------|--------|
| `400` | `VALIDACION` | Cuerpo mal formado o campos vacíos. |
| `401` | `CREDENCIALES_INVALIDAS` | **Correo inexistente, contraseña incorrecta, persona inactiva o cuenta bloqueada.** |
| `503` | `SERVICIO_SATURADO` | Semáforo de hash agotado; con `Retry-After`. |

**Los cuatro casos de `401` comparten código, cuerpo y coste de tiempo.** Es
FR-003 y SC-002, y la razón de que el bloqueo no se distinga está en
[D-005](../research.md#d-005-rechazo-uniforme-con-hash-siempre-también-para-cuentas-bloqueadas),
junto con el coste de usabilidad que se acepta a cambio. Un cuerpo propio para
"cuenta bloqueada" revelaría que el correo existe y reabriría el oráculo de
enumeración por la puerta de al lado.

---

## Rechazo de un token de acceso (FR-006)

No es un endpoint: es lo que responde **cualquier ruta protegida** cuando el
token de acceso no sirve. Lo emite un `AuthenticationEntryPoint` propio, no un
`@RestControllerAdvice`, porque el rechazo ocurre en la cadena de filtros y
ningún controlador llega a ejecutarse — pero usa el **mismo formato**, que es lo
que el principio VIII exige.

| Estado | `code` | Cuándo |
|--------|--------|--------|
| `401` | `TOKEN_ACCESO_EXPIRADO` | Firma válida, pero el token ya caducó. |
| `401` | `NO_AUTENTICADO` | No hay cabecera `Authorization`, o el token es ilegible o su firma no cuadra. |
| `403` | `FORBIDDEN` | Autenticado, pero sin autoridad para esa ruta. |

**La distinción es el requisito, no un detalle de presentación.** FR-006 existe
para que la aplicación sepa **renovar** en lugar de pedir la contraseña: con una
sola respuesta para los dos casos, un token caducado y un token falso son lo
mismo para el cliente, y la única salida que le queda es mandar a la persona a
escribir su contraseña cada quince minutos — que es justo lo que el token de
renovación viene a evitar.

Hasta esta feature, `SecurityConfig` respondía con
`HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`: un `401` **sin cuerpo**, sin
`code` y sin mensaje. Así que esto cierra dos cosas a la vez — FR-006 y un
incumplimiento del principio VIII que ya estaba ahí.

El `403` entra en la tabla por coherencia: si solo el `401` llevara cuerpo, el
`403` sería la única respuesta muda de toda la API.

---

## `POST /api/auth/refresh`

**Petición**: `{ "refreshToken": "9rJ2xQ..." }`

**`200 OK`**: el mismo cuerpo que `login`, con un `refreshToken` **nuevo**. El
presentado queda inservible en el acto (FR-008).

| Estado | `code` | Cuándo |
|--------|--------|--------|
| `401` | `TOKEN_RENOVACION_INVALIDO` | Desconocido, caducado, ya usado o revocado. |
| `401` | `EMPLEADO_INACTIVO` | La persona está inactiva ahora (FR-010). |

`EMPLEADO_INACTIVO` **sí** se distingue aquí, al contrario que en `login`: quien
presenta un token de renovación válido ya demostró ser titular de la cuenta, así
que no hay existencia que ocultarle — y saber que el rechazo es por baja laboral
y no por una sesión caducada le evita intentar entrar con la contraseña una y
otra vez.

---

## `POST /api/auth/logout`

**Petición**: `{ "refreshToken": "9rJ2xQ..." }` → **`204 No Content`**.

**Idempotente**: un token ya revocado o desconocido también devuelve `204`.
Cerrar sesión dos veces no es un error, y un `404` aquí distinguiría tokens
existentes de inexistentes, que es información que no toca dar.

Afecta **solo** a esa sesión (FR-009, FR-012, SC-006). El token de acceso
sigue siendo criptográficamente válido hasta que caduca; esa ventana es su vida
y la spec lo deja fuera de alcance explícitamente.

---

## `POST /api/auth/change-password`

Requiere `Authorization: Bearer <accessToken>`. Única operación permitida a una
sesión pendiente de cambio (FR-020).

**Petición**

```json
{ "passwordActual": "Temp0ral!", "passwordNueva": "Mi-Clave-2026!" }
```

La cuenta sale **del token**, nunca del cuerpo: el principio IV prohíbe aceptar
un identificador de usuario en el cuerpo o la query.

**`200 OK`**: un par de tokens nuevo, con `requiereCambioPassword: false`, para
que el cliente continúe sin volver a iniciar sesión.

| Estado | `code` | Cuándo |
|--------|--------|--------|
| `401` | `CREDENCIALES_INVALIDAS` | `passwordActual` incorrecta (FR-022). |
| `422` | `PASSWORD_DEBIL` | Incumple la política (FR-023). |

El cuerpo de `PASSWORD_DEBIL` enumera los requisitos incumplidos por
identificador y **nunca** reproduce la contraseña:

```json
{
  "code": "PASSWORD_DEBIL",
  "message": "La contraseña no cumple la política",
  "requisitos": ["FALTA_SIMBOLO", "LONGITUD_MINIMA"]
}
```

Es el único error de la feature con un campo extra, y por tanto una
**desviación declarada del principio VIII**, que fija el formato en exactamente
`{code, message}`. Se justifica porque FR-023
obliga a explicar **qué** requisito falla, y un cliente que quiera marcar los
campos necesita identificadores, no una frase.

**Añadido del plan**: un cambio correcto revoca **las demás** sesiones de la
cuenta (motivo `CAMBIO_PASSWORD`). La spec solo lo exige al restablecer
(FR-025). El motivo: cambiar la contraseña es a menudo la reacción a una
sospecha de robo, y dejar los otros dispositivos dentro vacía el gesto de
contenido. **Marcado como añadido para que el responsable pueda retirarlo**, ya
que tiene un coste visible: cambiar la contraseña en el móvil cierra la sesión
del navegador.

---

## `POST /api/auth/cuentas`

`ADMIN`. **Una sola operación** para dar acceso (FR-029b).

**Petición**: `{ "empleadoId": "…uuid…", "email": "ana@granatum.es", "rol": "EMPLEADO" }`

`rol` es opcional y por defecto `EMPLEADO`, que es lo que necesita la inmensa
mayoría de las altas. **Este campo no estaba en la spec**: lo destapó la
implementación de US1, porque FR-005 exige que el token lleve el rol y no había
dónde guardarlo — ver [data-model.md](../data-model.md#cuentas_acceso-v12). El
valor por defecto es el mínimo privilegio, así que un `ADMIN` que se olvide del
campo crea una cuenta de empleado y no un administrador.

**`201 Created`**

```json
{
  "cuentaId": "…uuid…",
  "empleadoId": "…uuid…",
  "email": "ana@granatum.es",
  "rol": "EMPLEADO",
  "passwordTemporal": "7xK-pQ2m!Rvt9Lab"
}
```

**`passwordTemporal` aparece aquí y en ningún otro sitio jamás**: no se
almacena en claro, no se registra, no se puede volver a consultar. Si se pierde,
la vía es restablecer. La genera el sistema y no la elige el `ADMIN`
([D-011](../research.md#d-011-la-contraseña-temporal-la-genera-el-sistema)).

| Estado | `code` | Cuándo |
|--------|--------|--------|
| `404` | `EMPLEADO_NO_ENCONTRADO` | `empleadoId` no corresponde a nadie (FR-029c). |
| `409` | `CUENTA_YA_EXISTE` | Esa persona ya tiene cuenta (FR-027, FR-029d). |
| `409` | `EMAIL_YA_REGISTRADO` | El correo ya es de otra cuenta (FR-028). |

`409` y no `400` en los dos conflictos: el estado del sistema es lo que impide
la operación, no la petición, y los códigos se distinguen porque la salida del
`ADMIN` es distinta — restablecer en un caso, usar otro correo en el otro.

---

## `POST /api/auth/cuentas/{empleadoId}/restablecer`

`ADMIN`. Mismo cuerpo de respuesta que el alta. Efectos (FR-024..FR-026):

1. Nueva contraseña temporal, `requiereCambioPassword = TRUE`.
2. **Todas** las sesiones vivas revocadas, motivo `RESET` (FR-025, SC-007).
3. Bloqueo e intentos a cero (FR-026).

Indexada por `empleadoId` y no por `cuentaId`: es el identificador que el
`ADMIN` tiene a mano desde la ficha de la persona, y hace la ruta usable sin
una consulta previa.

| Estado | `code` | Cuándo |
|--------|--------|--------|
| `404` | `CUENTA_NO_ENCONTRADA` | Esa persona no tiene cuenta. |
| `403` | `FORBIDDEN` | Quien lo pide no es `ADMIN` (US5 escenario 4). |

---

## `GET /api/auth/cuentas/huerfanas`

`ADMIN`. Cuentas cuyo `empleado_id` no corresponde a nadie (FR-029c).

```json
{ "total": 1, "cuentas": [ { "cuentaId": "…", "empleadoId": "…", "creadaEn": "2026-01-15T09:00:00Z" } ] }
```

**Sin correo en la respuesta.** Para limpiar basta el identificador, y el correo
sería un dato personal innecesario en un listado de diagnóstico.

Existe porque **la base de datos no puede prevenir estas filas**: sin clave
ajena entre módulos, una persona puede dejar de existir después de crearse la
cuenta. FR-029c pide detectarlas, y esta ruta es la forma consultable; un
trabajo nocturno registra además **solo el recuento** (nunca identificadores,
principio VI).

---

## Resumen de códigos de error

| `code` | HTTP | Excepción |
|--------|------|-----------|
| `VALIDACION` | 400 | `MethodArgumentNotValidException` |
| `CREDENCIALES_INVALIDAS` | 401 | `CredencialesInvalidasException` |
| `TOKEN_ACCESO_EXPIRADO` | 401 | — (lo emite `EntryPointJson`, no una excepción) |
| `NO_AUTENTICADO` | 401 | — (lo emite `EntryPointJson`, no una excepción) |
| `TOKEN_RENOVACION_INVALIDO` | 401 | `TokenRenovacionInvalidoException` |
| `EMPLEADO_INACTIVO` | 401 | `CuentaDeEmpleadoInactivoException` |
| `FORBIDDEN` | 403 | `ForbiddenException` (de `common`) |
| `EMPLEADO_NO_ENCONTRADO` | 404 | `EmpleadoNoEncontradoEnDirectorioException` |
| `CUENTA_NO_ENCONTRADA` | 404 | `CuentaNoEncontradaException` |
| `CUENTA_YA_EXISTE` | 409 | `CuentaYaExisteException` |
| `EMAIL_YA_REGISTRADO` | 409 | `EmailYaRegistradoException` |
| `PASSWORD_DEBIL` | 422 | `PasswordDebilException` |
| `SERVICIO_SATURADO` | 503 | `VerificacionSaturadaException` |

Ningún `message` de esta tabla contiene un correo, una contraseña ni un token:
los cuerpos de error llegan por igual a clientes y a logs (principio VI).

## Por qué dos nombres de excepción son tan largos

`CuentaDeEmpleadoInactivoException` y
`EmpleadoNoEncontradoEnDirectorioException` se llaman así para no chocar con
`EmpleadoInactivoException` y `EmpleadoNotFoundException`, que **ya existen** en
`timetracking` dentro del **mismo paquete**, `com.granatum.core.domain.exception`.

No es una cuestión de estilo. Todos los módulos comparten el namespace
`com.granatum.core` por decisión de la constitución, y cada uno se empaqueta en
su propio jar; en el classpath de `app` conviven los dos. Dos clases con el
mismo nombre cualificado significan que **solo una se carga** y la otra queda
tapada. Concretamente, con `EmpleadoInactivoException`:

- la de `timetracking` extiende `InvalidOperationException` → **400**;
- la que `auth` necesita extiende `UnauthorizedException` → **401**.

El código de `auth` compila contra la suya, pero en ejecución cargaría la de
`timetracking`: el rechazo por baja laboral saldría como `400
INVALID_OPERATION` en lugar de `401 EMPLEADO_INACTIVO` — o directamente como
`NoSuchMethodError` si las firmas no coinciden. **Ningún test de módulo lo
detectaría**, porque en el classpath de `auth` solo está su propia clase. Ver
[D-019](../research.md#d-019-colisión-de-nombres-de-excepción-entre-módulos).
