# Data Model: Inicio de sesión real (auth)

**Fecha**: 2026-10-05 | **Spec**: [spec.md](./spec.md) | **Decisiones**: [research.md](./research.md)

Tres tablas, todas nuevas, todas propiedad del módulo `auth`. Migraciones
**V12–V14** (numeración global compartida; ver [D-018](./research.md#d-018-migraciones-v12-v13-y-v14)).

Sin clave ajena hacia `empleados`: está en otro módulo y el **principio I** lo
prohíbe. La comprobación se hace por el contrato
[`DirectorioEmpleados`](./research.md#d-009-directorioempleados-un-contrato-en-common),
y la imposibilidad de que la base de datos la garantice es la razón de ser de
FR-029c.

---

## `cuentas_acceso` (V12)

Las credenciales de una persona. Mutable, pero solo por las transiciones
enumeradas más abajo.

| Columna | Tipo | Nulo | Regla |
|---------|------|------|-------|
| `id` | `UUID` | no | PK. Identificador de la cuenta, **distinto** del de la persona empleada. |
| `empleado_id` | `UUID` | no | Identificador de la persona cuya jornada se registra (FR-029). **`UNIQUE`** (FR-029d). Sin clave ajena, a propósito. |
| `email` | `VARCHAR(254)` | no | **`UNIQUE`** (FR-028). Ya normalizado al escribir: recortado y en minúsculas con `Locale.ROOT` ([D-013](./research.md#d-013-el-correo-se-normaliza-en-un-solo-sitio-y-nunca-se-registra)). 254 es el máximo del RFC 5321. Dato personal: fuera de logs y de `toString()`. |
| `rol` | `VARCHAR(20)` | no | `CHECK` sobre los cuatro roles del principio IV. **Lo encontró la implementación, no la spec**: FR-005 exige que el token lleve el rol, `timetracking` lo lee del token para autorizar de verdad, y no había columna de rol **en ninguna tabla** — hoy lo aporta `/api/dev/token` como parámetro de la petición, que es justo por lo que ese endpoint es una suplantación total. Vive aquí y no en `empleados` porque es una propiedad de la credencial, no del empleo: `puesto` dice qué hace alguien, `rol` dice qué puede leer y escribir. `REPRESENTANTE` lo hace evidente — la representación legal es destinataria del registro por el art. 34.9, lo que no tiene nada que ver con su puesto. Además mantiene limpio el principio I: `auth` decide la autorización por su cuenta y `DirectorioEmpleados` sigue estrecho. |
| `password_hash` | `VARCHAR(255)` | no | Salida de `DelegatingPasswordEncoder`, con prefijo: `{argon2}$argon2id$v=19$m=65536,t=3,p=1$...`. 255 deja sitio a un algoritmo futuro más verboso sin migrar. |
| `requiere_cambio_password` | `BOOLEAN` | no | `DEFAULT TRUE`. FR-019. El valor por defecto es `TRUE` porque **toda** cuenta nace de una contraseña temporal: no existe vía de alta que no lo exija. |
| `intentos_fallidos` | `SMALLINT` | no | `DEFAULT 0`. Fallos consecutivos desde el último éxito o bloqueo. Vuelve a 0 al bloquear ([D-008](./research.md#d-008-bloqueo-creciente--máquina-de-estados-pura-y-bloqueo-de-fila-corto)). |
| `nivel_bloqueo` | `SMALLINT` | no | `DEFAULT 0`. 0 = nunca bloqueada. Topa en 4. `CHECK (nivel_bloqueo BETWEEN 0 AND 4)`. |
| `bloqueada_hasta` | `TIMESTAMPTZ` | sí | Nulo = no bloqueada. FR-016: el bloqueo se levanta por comparación con el reloj, sin proceso que lo limpie. |
| `created_at` | `TIMESTAMPTZ` | no | Auditoría JPA. |
| `updated_at` | `TIMESTAMPTZ` | no | Auditoría JPA. |

**Índices**: PK, `uk_cuentas_acceso_email`, `uk_cuentas_acceso_empleado`.
Ningún índice más: las tres consultas de la feature (por correo, por
`empleado_id`, por `id`) los usan todos.

**Por qué `nivel_bloqueo` y `bloqueada_hasta` son columnas y no un cálculo**:
el nivel sobrevive a la expiración del bloqueo —es lo que hace que el siguiente
dure 5 minutos y no 1 (FR-016a)— y un cálculo sobre `eventos_seguridad` tendría
que recorrer el histórico en cada intento de inicio de sesión.

**Por qué no hay columna `activo`**: la actividad es un hecho laboral y vive en
`empleados`. Duplicarla aquí serían dos fuentes de verdad, y su divergencia
significaría que alguien dado de baja sigue entrando
([D-009](./research.md#d-009-directorioempleados-un-contrato-en-common)).

### Transiciones permitidas

Ninguna escritura fuera de esta lista:

| Transición | Disparador | Efecto |
|------------|-----------|--------|
| Alta | `ADMIN` da acceso (FR-018, FR-029b) | Crea la fila con contraseña temporal y `requiere_cambio_password = TRUE`. |
| Fallo contado | Inicio de sesión fallido **sin** bloqueo activo | `intentos_fallidos += 1`; al llegar a 5, sube `nivel_bloqueo`, fija `bloqueada_hasta` y pone `intentos_fallidos = 0`. |
| Fallo ignorado | Inicio de sesión fallido **con** bloqueo activo (FR-016c) | **Ninguno.** Se anota el evento y nada más. |
| Éxito | Inicio de sesión correcto (FR-015, FR-016b) | `intentos_fallidos = 0`, `nivel_bloqueo = 0`, `bloqueada_hasta = NULL`. |
| Cambio | La persona cambia su contraseña (FR-021) | Nuevo `password_hash`, `requiere_cambio_password = FALSE`. |
| Restablecimiento | `ADMIN` restablece (FR-024, FR-026) | Nuevo `password_hash` temporal, `requiere_cambio_password = TRUE`, contadores y bloqueo a cero. |

**No existe borrado.** El repositorio extiende `Repository<T, ID>` y no declara
`delete` (principio III, última regla; y la deuda nº 2 de la constitución es la
lección de qué pasa cuando se declara). Quitar el acceso a alguien se hace
dando de baja a la persona en `empleados`, que es donde vive ese hecho.

---

## `sesiones_renovacion` (V13)

Una sesión abierta en un dispositivo. Una cuenta puede tener varias (FR-012).

| Columna | Tipo | Nulo | Regla |
|---------|------|------|-------|
| `id` | `UUID` | no | PK. |
| `cuenta_id` | `UUID` | no | **Clave ajena** a `cuentas_acceso(id)`, `ON DELETE RESTRICT`. Misma feature, misma migración: aquí la clave ajena sí procede. |
| `token_hash` | `VARCHAR(64)` | no | **`UNIQUE`**, con `CHECK (char_length(token_hash) = 64)`. SHA-256 en hexadecimal del valor opaco; el valor en claro no se almacena nunca (FR-011). Era `CHAR(64)` y `ddl-auto: validate` lo rechazó: Postgres informa de `CHAR` como `bpchar` y una columna `String` de JPA mapea a `varchar`, así que el contexto no arrancaba. El `CHECK` conserva la garantía de anchura fija sin el relleno con espacios de `bpchar`, que haría comparar iguales dos valores que no lo son. |
| `expira_en` | `TIMESTAMPTZ` | no | Creación + `AUTH_REFRESH_EXPIRATION_DAYS` (30 por defecto). |
| `creada_en` | `TIMESTAMPTZ` | no | |
| `usada_en` | `TIMESTAMPTZ` | sí | No nulo = ya se rotó. Es la marca de un solo uso (FR-008). |
| `revocada_en` | `TIMESTAMPTZ` | sí | No nulo = invalidada sin usarse. |
| `motivo_revocacion` | `VARCHAR(20)` | sí | `CHECK (... IN ('LOGOUT', 'RESET', 'CAMBIO_PASSWORD'))`. Nulo si y solo si `revocada_en` es nulo. |

**Índices**: PK, `uk_sesiones_renovacion_token`,
`idx_sesiones_renovacion_cuenta (cuenta_id)` — lo usan la revocación masiva de
FR-025 y la purga de [D-015](./research.md#d-015-purga-de-sesiones-muertas-añadido-del-plan).

**Una sesión sirve si y solo si**
`usada_en IS NULL AND revocada_en IS NULL AND expira_en > ahora`.
Esa condición completa viaja en el `WHERE` del `UPDATE` de rotación, que es lo
que convierte el uso único en una garantía y no en una comprobación
([D-006](./research.md#d-006-rotación-de-un-solo-uso-con-update-condicional)).

**`usada_en` y `revocada_en` separados, y no un único estado**: distinguen
"rotada con normalidad" de "cortada por un cierre de sesión o un
restablecimiento". La segunda es la que importa en una investigación, y
colapsarlas la borraría.

### Transiciones

| Transición | Disparador | Efecto |
|------------|-----------|--------|
| Apertura | Inicio de sesión correcto (FR-001) | Fila nueva. |
| Rotación | Renovación (FR-007, FR-008) | `usada_en` en la vieja **y** fila nueva, en la misma transacción. |
| Cierre | Cierre de sesión (FR-009) | `revocada_en`, motivo `LOGOUT`, **solo esa fila** (FR-012, SC-006). |
| Revocación masiva | Restablecimiento (FR-025) / cambio de contraseña | `revocada_en` en todas las vivas de la cuenta. |
| Purga | Trabajo programado ([D-015](./research.md#d-015-purga-de-sesiones-muertas-añadido-del-plan)) | Borra filas muertas desde hace más de 30 días. Única vía de borrado, sin endpoint ni rol. |

---

## `eventos_seguridad` (V14)

**APPEND-ONLY.** Constancia de los intentos de acceso y de los cambios de
credenciales (FR-017).

| Columna | Tipo | Nulo | Regla |
|---------|------|------|-------|
| `id` | `UUID` | no | PK. |
| `cuenta_id` | `UUID` | sí | **Nulo cuando el correo no corresponde a ninguna cuenta.** Sin clave ajena: nada debe poder arrastrar un borrado hasta la tabla inmutable, igual que en `fichaje_eventos`. |
| `tipo` | `VARCHAR(40)` | no | `CHECK` sobre la lista de abajo. |
| `ocurrido_en` | `TIMESTAMPTZ` | no | |

**Sin `updated_at`, a propósito: no hay nada que actualizar.**

**Tipos**: `LOGIN_CORRECTO`, `LOGIN_FALLIDO`, `LOGIN_CUENTA_DESCONOCIDA`,
`LOGIN_CUENTA_BLOQUEADA`, `LOGIN_EMPLEADO_INACTIVO`, `CUENTA_BLOQUEADA`,
`RENOVACION_CORRECTA`, `RENOVACION_RECHAZADA`, `RENOVACION_TOKEN_REUTILIZADO`,
`CIERRE_SESION`, `PASSWORD_CAMBIADA`, `PASSWORD_RESTABLECIDA`, `CUENTA_CREADA`.

`RENOVACION_TOKEN_REUTILIZADO` es distinto de `RENOVACION_RECHAZADA` porque
significa algo distinto: un token que **existió y se usó** vuelve a presentarse.
Es la única señal de posible robo que deja la feature
([D-007](./research.md#d-007-reutilizar-un-token-ya-usado-no-revoca-la-cadena)).

**Índices**: PK, `idx_eventos_seguridad_cuenta (cuenta_id, ocurrido_en)`.

**Lo que esta tabla NO contiene, y por qué**
([D-014](./research.md#d-014-eventos-de-seguridad-append-only-sin-correo-y-sin-dirección-de-origen)):

- Ni contraseñas ni tokens, en ninguna forma — ni siquiera su hash (FR-017, SC-009).
- **Ni el correo**, tampoco en un intento con correo desconocido: sería guardar un
  dato personal de quien no es usuario, por un intento que puede no ser suyo.
- **Ni la dirección de origen**: dato personal, y el límite por origen está fuera
  de alcance. Recogerla "por si acaso" es recoger sin finalidad.

**Repositorio**: `Repository<EventoSeguridadEntity, UUID>` con `save` y las
lecturas necesarias. Sin `delete`, sin `deleteById`, sin `saveAll` sobre filas
existentes.

**Deuda declarada**: sin plazo de conservación. Fijarlo es decisión del
responsable del producto; ver D-014.

---

## Modelo de dominio (sin JPA, sin HTTP)

```
CuentaAcceso(id, empleadoId, email, rol, requiereCambioPassword, estadoBloqueo)
EstadoBloqueo(intentosFallidos, nivel, bloqueadaHasta)
  └── estaBloqueada(ahora): Boolean

ParTokens(accessToken, refreshToken, expiraEnSegundos, requiereCambioPassword)

PoliticaPassword.validar(password): List<RequisitoIncumplido>
  RequisitoIncumplido ∈ { LONGITUD_MINIMA, LONGITUD_MAXIMA, FALTA_MAYUSCULA,
                          FALTA_MINUSCULA, FALTA_DIGITO, FALTA_SIMBOLO }

PoliticaBloqueo.trasFallo(estado, ahora): EstadoBloqueo
PoliticaBloqueo.trasExito(): EstadoBloqueo
PoliticaBloqueo.duracion(nivel): Duration   // 1, 5, 15, 60 minutos
```

`PoliticaPassword` y `PoliticaBloqueo` son `object` puros, sin Spring y sin base
de datos: son la lógica de negocio que el **principio V** exige probar con
tests unitarios. El hash **no** entra en el dominio: es infraestructura, detrás
de `PasswordEncoder`.

**`estadoBloqueo` es un objeto empotrado y no tres campos sueltos** porque las
tres columnas solo tienen sentido juntas, y la transición las escribe de una
pieza: dejarlas sueltas invita a actualizar `bloqueada_hasta` sin tocar
`nivel_bloqueo`, que es el error que haría que todos los bloqueos duraran 1
minuto.

**Entidades JPA**: clases normales, nunca `data class`, con identidad por `id` y
`hashCode` por clase. `toString()` omite `email` y `password_hash`.
