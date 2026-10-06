# Research: Inicio de sesión real (auth)

**Fecha**: 2026-10-05 | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Todas las mediciones de este documento se tomaron en la máquina de desarrollo
(Mac mini, 10 núcleos, JDK 21) con los jar reales de Spring Security 7.0.0 y
BouncyCastle 1.79 que resuelve el proyecto. El programa de medición está en el
apartado [Cómo se midió](#cómo-se-midió) para que cualquiera pueda repetirlo en
el hardware de destino, que es donde los números importan.

---

## D-001: Argon2id en lugar de BCrypt

**Decisión**: `Argon2PasswordEncoder` (Argon2id), envuelto en un
`DelegatingPasswordEncoder` cuyo identificador por defecto es `{argon2}`.

**Motivo**: no es una preferencia, es un requisito. **FR-023b** prohíbe imponer
un máximo de longitud por debajo de 64 caracteres, y BCrypt no puede cumplirlo.
Medido:

```
64 caracteres 'á' = 128 bytes UTF-8
bcrypt  -> IllegalArgumentException: password cannot be more than 72 bytes
argon2  -> cifra correctamente, y no coincide con el prefijo de 72 bytes
           (es decir, no trunca en silencio)
```

Una frase de paso de 64 caracteres en español —perfectamente legítima bajo
FR-023b— rompe `BCryptPasswordEncoder.encode` con una excepción, que sin un
manejo específico sale por HTTP como un 500. No es un caso de laboratorio: 64
caracteres acentuados son 128 bytes, y basta con **36 caracteres acentuados**
para pasar de 72. La alternativa sería limitar la contraseña a 36 caracteres,
que incumple FR-023b de forma directa.

El segundo motivo lo pone la propia spec: la política de 8 caracteres con
composición deja un espacio de contraseñas pequeño, y la spec registra la
consecuencia —"el coste del hash adaptativo importa más"—. Argon2id es
**memory-hard**: encarece el ataque en GPU mucho más que BCrypt para el mismo
tiempo de CPU, que es exactamente la palanca que queda cuando la longitud
mínima es corta.

**Alternativas consideradas**:

| Opción | Por qué se descarta |
|--------|---------------------|
| BCrypt cost 12 | Rompe FR-023b (límite duro de 72 bytes). Sin dependencia extra y 217 ms medidos, pero el incumplimiento es insalvable. |
| BCrypt con máximo de 36 caracteres | Cumpliría técnicamente sin excepción, incumpliendo FR-023b explícitamente. Rechazado: la spec eligió 8 caracteres mínimos *a cambio* de no limitar por arriba. |
| PBKDF2 | Sin dependencia extra y sin límite de longitud, pero no es memory-hard: es la peor de las tres contra hardware dedicado, justo el escenario que importa aquí. |
| `Argon2Password4jPasswordEncoder` (nuevo en Spring Security 7) | Añade la dependencia `password4j` en lugar de BouncyCastle, sin ventaja. `Argon2PasswordEncoder` es la clase estable y documentada. |

**Coste aceptado**: Argon2 en Spring Security delega en BouncyCastle —
verificado con `javap`, la clase referencia
`org.bouncycastle.crypto.generators.Argon2BytesGenerator`—, así que hay que
añadir `org.bouncycastle:bcprov-jdk18on` como dependencia de runtime. Es una
librería nueva, no una sustitución de stack, así que no requiere enmienda a la
constitución; la necesidad está demostrada arriba, que es lo que exige la regla
de *simplicidad deliberada*.

**Hay que fijar la versión a mano.** Comprobado sobre
`spring-boot-dependencies-4.0.0-SNAPSHOT.pom`: el BOM de Boot 4 **no** gestiona
BouncyCastle, al contrario que casi todo lo demás del proyecto. Así que
`gradle/libs.versions.toml` necesita una entrada con versión explícita
(`bouncycastle = "1.79"`), y es además el único sitio donde puede ir: la
constitución prohíbe que un `build.gradle.kts` de módulo fije versiones a mano.

**`DelegatingPasswordEncoder`, no el encoder desnudo**: el principio VI ya lo
nombra. El hash se almacena con prefijo (`{argon2}$argon2id$v=19$m=...`), así
que cambiar de algoritmo o de parámetros en el futuro no necesita migración de
esquema ni una columna de versión: los hashes antiguos siguen verificándose con
sus propios parámetros, que viajan dentro del hash.

---

## D-002: Parámetros de Argon2id — m=64 MiB, t=3, p=1

**Decisión**: `Argon2PasswordEncoder(saltLength = 16, hashLength = 32,
parallelism = 1, memory = 65536, iterations = 3)`, configurables por entorno.

**Motivo**: los valores por defecto de Spring Security son demasiado baratos en
este hardware. Medido (mínimo / mediana / máximo de 7 ejecuciones, en ms):

| memoria | t | p | min | mediana | max |
|---------|---|---|-----|---------|-----|
| 16 MiB (`defaultsForSpringSecurity_v5_8`) | 2 | 1 | 16 | — | — |
| 19 MiB (recomendación OWASP) | 2 | 1 | 19 | 23 | 121 |
| 32 MiB | 3 | 1 | 52 | 54 | 56 |
| **64 MiB** | **3** | **1** | **110** | **113** | **118** |
| 64 MiB | 4 | 1 | 144 | 147 | 155 |
| 64 MiB | 5 | 1 | 181 | 182 | 189 |
| 96 MiB | 3 | 1 | 172 | 176 | 189 |
| 128 MiB | 3 | 1 | 236 | 239 | 251 |
| 64 MiB | 3 | 2 | 110 | 112 | 119 |
| *BCrypt cost 12, como referencia* | | | *217* | | |

Los valores por defecto de Spring Security (16 MiB, t=2) cuestan **16 ms**:
usarlos sería un **retroceso de más de 13×** respecto a BCrypt cost 12, con lo
que adoptar Argon2 "por ser mejor" y quedarse con sus defaults empeoraría
exactamente lo que la spec pide cuidar. De ahí que los parámetros vayan
elegidos y medidos, no heredados.

64 MiB / t=3 cuesta la mitad de tiempo que BCrypt(12) pero exige 64 MiB por
verificación, y es esa memoria la que no se paraleliza barato en GPU. Deja
además margen de sobra bajo **SC-011** (< 2 s): aun en una máquina 5 veces más
lenta serían ~550 ms, y el presupuesto HTTP completo seguiría dentro.

`p=1` y no 2: medido, no cambia el tiempo (110 ms en ambos) porque Argon2
reparte la misma memoria entre los carriles. Dos hilos por login solo
consumirían más CPU del pool bajo carga, sin encarecer el ataque.

**Los parámetros son configuración, no código**: `auth.password.argon2.*`. No
para tocarlos a la ligera, sino para poder recalibrarlos en el hardware real
—donde la tabla de arriba puede ser muy distinta— sin recompilar, y para que
los tests puedan bajarlos y no pagar 110 ms por cada login de prueba.

---

## D-003: Límite de verificaciones simultáneas (consecuencia de D-002)

**Decisión**: un semáforo acota cuántas verificaciones de contraseña corren a la
vez (por defecto 4, configurable). Al saturarse se espera un plazo corto y, si
no se libera, se responde `503` con `Retry-After`.

**Motivo**: esto lo **introduce D-002**, y sin resolverlo el plan abriría un
agujero que la spec no contempla. `POST /api/auth/login` es público y cada
llamada reserva 64 MiB. Tomcat admite por defecto 200 hilos: 200 logins
simultáneos son **12,8 GiB** de reserva de memoria. Es una amplificación de
memoria que cualquiera puede disparar sin credencial alguna, y el límite por
dirección de origen —la defensa natural— está **fuera de alcance** por decisión
de la spec (pertenece a la feature de endurecimiento).

Con el semáforo a 4, el techo es 4 × 64 MiB = **256 MiB** sea cual sea la
carga. Medido: 10 logins simultáneos sin acotar tardan 365 ms de pared en un
equipo de 10 núcleos, así que una cola de 4 no penaliza el uso real (una
plantilla pequeña no inicia sesión 4 veces en el mismo segundo) y convierte un
agotamiento de memoria en una espera.

**Alternativa considerada**: bajar la memoria a 19 MiB (OWASP) y subir las
iteraciones hasta igualar el tiempo. Haría falta t≈12, lo que cambia dureza de
memoria por dureza de tiempo — es decir, renuncia a la única ventaja por la que
se eligió Argon2. Rechazada.

**No oculta información**: la saturación no depende de la cuenta, así que no
introduce un canal lateral que distinga correos existentes (D-005).

---

## D-004: El token de renovación es opaco, no un JWT

**Decisión**: 32 bytes de `SecureRandom` en base64url. Se almacena **solo**
`SHA-256` del valor, en hexadecimal.

**Motivo**: **FR-011** exige que el valor no sea recuperable desde la base de
datos, y **FR-008** que cada token sirva una sola vez. Lo segundo obliga a
consultar la base de datos en cada renovación, y a partir de ahí un JWT firmado
no aporta nada: su autocontención —la única razón de ser de un JWT— deja de
usarse. Un valor opaco es además **imposible de confundir con un token de
acceso**: no se puede presentar en un `Authorization: Bearer` y pasar el filtro,
porque no tiene firma ni claims.

`JwtService.generateRefreshToken` queda sin uso en producción; sigue existiendo
para `POST /api/dev/token`, que esta feature no retira (FR-031 lo deja como
decisión aparte).

**Por qué SHA-256 aquí y nunca para una contraseña**: el principio VI prohíbe
los hashes rápidos "tipo MD5/SHA" — para contraseñas. La razón de esa
prohibición es que una contraseña tiene poca entropía y es adivinable por fuerza
bruta, así que el hash debe ser deliberadamente lento. Un token de renovación es
un valor aleatorio de 256 bits: no hay nada que adivinar, y un hash lento solo
encarecería cada renovación legítima. Lo que SHA-256 sí aporta es lo que pide
FR-011: quien lea la tabla no puede usar lo que ve. **Esta distinción se
documenta porque es exactamente la clase de decisión que una revisión futura
leería como un incumplimiento del principio VI si no se explica.**

---

## D-005: Rechazo uniforme, con hash siempre, también para cuentas bloqueadas

**Decisión**: todo inicio de sesión que no termine en éxito responde `401` con
el mismo cuerpo `{"code": "CREDENCIALES_INVALIDAS", ...}` — correo inexistente,
contraseña incorrecta, persona inactiva y cuenta bloqueada incluidas. En los
cuatro casos se ejecuta una verificación de Argon2 completa: contra el hash real
si la cuenta existe, y contra un **hash señuelo** si no.

**Motivo**: **FR-003** y **SC-002** exigen que un correo inexistente y una
contraseña incorrecta sean indistinguibles en código, cuerpo y tiempo. Sin el
señuelo, el camino "correo no existe" se salta los 110 ms de Argon2 y la
diferencia es trivial de medir: el inicio de sesión se convierte en un oráculo
que enumera qué correos están dados de alta.

El señuelo se cifra **al arrancar**, a partir de una contraseña aleatoria y con
los parámetros configurados. Es importante que lo haga el encoder configurado y
no una constante: `Argon2PasswordEncoder.matches` lee los parámetros **del hash
almacenado**, así que un señuelo con parámetros distintos tendría un tiempo
distinto y reabriría el canal lateral que viene a cerrar.

**Que el bloqueo también responda igual es una decisión del plan, no de la
spec.** La spec solo obliga a no revelar si la contraseña era correcta (US3,
escenario 5). Pero una respuesta específica de "cuenta bloqueada" revela que el
correo existe, y volvería a abrir el oráculo que FR-003 cierra por la puerta de
al lado. **Coste real y visible**: la persona que se equivoca cinco veces no ve
"vuelve a intentarlo en 1 minuto", ve el mismo mensaje genérico y no sabe cuánto
esperar. Es una pérdida de usabilidad en el momento más frustrante, y se recoge
aquí para que el responsable del producto pueda revocarla a cambio de aceptar la
enumeración de correos.

**Límite conocido**: la igualdad de tiempos es estadística, no criptográfica. Al
ser el mismo camino con el mismo coste dominante queda muy por debajo del ruido
de red, y el test lo comprueba por medianas, no exigiendo tiempo constante — una
promesa que no se podría cumplir sobre la JVM.

---

## D-006: Rotación de un solo uso con `UPDATE` condicional

**Decisión**: renovar ejecuta

```sql
UPDATE sesiones_renovacion
   SET usada_en = :ahora
 WHERE token_hash = :hash
   AND usada_en IS NULL
   AND revocada_en IS NULL
   AND expira_en > :ahora
```

y solo continúa si afectó a **1 fila**. La fila nueva se crea después.

**Motivo**: es la lección de la tarea T060 de la feature de jornada. Leer la
fila, comprobar en Kotlin que está sin usar y escribir después deja una ventana
en la que dos peticiones simultáneas con el mismo token pasan las dos la
comprobación y emiten dos pares de tokens. El `UPDATE` condicional hace que la
base de datos sea el árbitro: el perdedor recibe 0 filas afectadas y se rechaza.
`SC-004` pide el 100%, y el 100% solo lo da esto.

**Alternativa considerada**: `SELECT ... FOR UPDATE` y decidir en Kotlin.
Correcto también, pero más costoso y sin ventaja: aquí no hay lógica que
justifique traerse la fila para decidir.

---

## D-007: Reutilizar un token ya usado no revoca la cadena

**Decisión**: se rechaza la renovación y se anota un evento de seguridad. **No**
se revocan las demás sesiones de la cuenta.

**Motivo**: la práctica habitual (OAuth 2.0 Security BCP) es revocar la familia
completa al detectar la reutilización, porque indica robo. Aquí se descarta por
el producto: la causa muchísimo más frecuente de reutilización no es un robo, es
**un cliente móvil que reintenta una renovación cuya respuesta se perdió**. Con
revocación de cadena, una red mala deja a la persona fuera de todos sus
dispositivos; y en este producto quedarse fuera significa **no poder fichar**, y
un fichaje que no se hace es un hueco en un registro con valor legal.

El resultado para quien sufre un robo es casi el mismo sin revocar: el token
robado solo sirve si el legítimo no lo ha usado, y una sola vez. Y el escenario
que de verdad pide cortar todo —dispositivo perdido— ya tiene su vía en
**FR-025**: restablecer la contraseña invalida todas las sesiones.

Queda registrado como ampliación futura, no como deuda: sería un cambio de
alcance (haría falta un identificador de cadena), no un incumplimiento.

---

## D-008: Bloqueo creciente — máquina de estados pura y bloqueo de fila corto

**Decisión**: la transición es una función pura de Kotlin
(`PoliticaBloqueo`), y se aplica en una transacción corta que relee la fila con
`SELECT ... FOR UPDATE`. **La verificación de la contraseña ocurre antes, fuera
de esa transacción.**

```
duración por nivel:  1 -> 1 min   2 -> 5 min   3 -> 15 min   4+ -> 60 min

fallo con bloqueo activo  -> no se toca nada (FR-016c)
fallo sin bloqueo activo  -> intentos += 1
                             si intentos >= 5: nivel = min(nivel+1, 4)
                                               bloqueada_hasta = ahora + dur(nivel)
                                               intentos = 0
éxito                     -> intentos = 0, nivel = 0, bloqueada_hasta = NULL
```

**Motivos de cada pieza**:

- *Función pura*: el principio V exige test unitario con MockK de la lógica de
  negocio, y esta es la lógica de negocio de la feature. Escrita en SQL con
  `CASE`, las cuatro duraciones vivirían en el esquema y no habría forma de
  probarlas sin levantar Postgres — o habría que duplicarlas en Kotlin y
  mantener un test de que las dos copias concuerdan.
- *`FOR UPDATE`*: dos fallos simultáneos leyendo `intentos = 4` y escribiendo
  los dos `5` dejarían la cuenta sin bloquear. El bloqueo de fila es por cuenta
  y se mantiene microsegundos.
- *Hash fuera de la transacción*: mantener un bloqueo de fila durante los 110 ms
  de Argon2 serializaría los intentos contra una misma cuenta y convertiría el
  endurecimiento en el amplificador de una denegación de servicio —
  precisamente lo que **FR-016c** viene a evitar por otra vía.
- *Poner `intentos` a 0 al bloquear*: lo dice FR-016c literalmente ("tras otros
  5 fallos consecutivos una vez expirado el anterior"). Sin ello, el primer
  fallo tras expirar el bloqueo volvería a bloquear al instante.

**Ventana aceptada**: entre la lectura sin bloqueo (que decide el rechazo
rápido) y la transacción corta puede colarse un intento extra con la cuenta ya
bloqueada. No afecta a ninguna garantía: ese intento se rechaza igual, y FR-016c
impide que prolongue el bloqueo. El test de concurrencia asegura lo que importa
—que 5 fallos simultáneos acaban en bloqueo, nunca en ninguno—.

---

## D-009: `DirectorioEmpleados`, un contrato en `common`

**Decisión**: una interfaz en `common`
(`com.granatum.core.domain.contract.DirectorioEmpleados`), implementada por
`timetracking` y consumida por `auth`.

```kotlin
enum class EstadoEmpleado { ACTIVO, INACTIVO }

interface DirectorioEmpleados {
    /** `null` si no existe ninguna persona con ese identificador. */
    fun estado(empleadoId: EntityId): EstadoEmpleado?
    /** De los identificadores dados, los que sí existen. Para el barrido de huérfanas. */
    fun existentes(empleadoIds: Collection<EntityId>): Set<EntityId>
}
```

**Motivo**: `auth` necesita cuatro cosas de la persona empleada —que exista al
crear la cuenta (FR-029c), que esté activa al entrar (FR-002) y al renovar
(FR-010), y qué cuentas apuntan a nadie (FR-029c)— y el **principio I** le
prohíbe depender de `timetracking`. El mismo principio da la salida: "si dos
features necesitan compartir algo, ese algo DEBE subir a `common` o exponerse
como contrato explícito". Esto es el contrato explícito, y el compilador lo
sostiene: `auth/build.gradle.kts` no menciona `timetracking`.

`existentes(...)` en lote y no `estado(...)` en bucle: el barrido de huérfanas
recorre todas las cuentas, y una consulta por cuenta es un N+1 garantizado.

**Dependencia obligatoria, no opcional**: `auth` la recibe por constructor sin
valor por defecto. Si nadie la implementa, `app` **no arranca**. Es la avería
correcta: un `auth` que no puede comprobar si una persona existe aceptaría
cuentas huérfanas en silencio, que es justo lo que FR-029c prohíbe. En los tests
propios de `auth` la implementa un doble en el contexto de prueba.

**Alternativa considerada**: duplicar `activo` en la cuenta de acceso y
sincronizarlo. Evitaría el contrato, a cambio de dos fuentes de verdad sobre un
mismo hecho y de un camino para que divergieran — y la divergencia significaría
que alguien dado de baja sigue entrando. Rechazada.

**Coste aceptado y ya previsto por la spec**: sin clave ajena entre módulos, la
base de datos no puede impedir una cuenta huérfana (la persona podría dejar de
existir después). De ahí que FR-029c pida **detectarlas**, no solo prevenirlas.

---

## D-010: "Pendiente de cambio" viaja en el token y restringe la autoridad

**Decisión**: el token de acceso lleva un claim `pwd_change` cuando la cuenta
está pendiente de cambio. `JwtAuthFilter` (en `common`), al verlo, concede
**solo** la autoridad `PWD_CHANGE_ONLY` y **no** concede `ROLE_<rol>`.

**Motivo**: **FR-020** exige que una sesión pendiente de cambio no pueda hacer
nada salvo cambiar su contraseña, y eso incluye las rutas de `inventory` y
`timetracking`, que no deben saber que `auth` existe (principio I). Un claim
interpretado en el único filtro que ya comparten todos los módulos lo resuelve
en un sitio.

Lo que lo hace limpio es **no conceder el rol**: todas las reglas existentes son
`hasAnyRole(...)`, así que pasan a rechazar por sí solas sin tocarlas, y la
regla nueva en `SecurityConfig` es una **concesión** (`change-password` exige
`PWD_CHANGE_ONLY`), nunca una negación. El principio VI ya enseñó por qué una
negación es frágil: `@Profile("!prod")` dejaba el endpoint de desarrollo vivo
bajo cualquier perfil futuro.

**Con un cambio obligatorio**: hoy el comodín de `SecurityConfig` es
`anyRequest().authenticated()`, y un token pendiente de cambio **está**
autenticado. Cualquier ruta futura sin regla de rol explícita quedaría a su
alcance. El comodín pasa a exigir un rol real
(`anyRequest().hasAnyRole("ADMIN", "ENCARGADO", "EMPLEADO", "REPRESENTANTE")`),
con lo que el estado restringido vuelve a ser denegar por defecto. Hay un test
sobre esto, porque es un cambio de una línea que al revertirse no rompería nada
visible.

**Alternativas consideradas**: consultar la base de datos en cada petición
(la spec lo descarta: el token sigue sin estado); o un rol ficticio
`PENDIENTE_CAMBIO` (contaminaría `Role`, que el principio IV define como el
mapa de autorización del producto, con un estado de sesión).

**Compatibilidad**: los tokens sin el claim se comportan exactamente como hoy,
así que `inventory`, `timetracking` y `/api/dev/token` no cambian.

---

## D-011: La contraseña temporal la genera el sistema

**Decisión**: 16 caracteres de un CSPRNG con las cuatro clases garantizadas. Se
devuelve **una sola vez** en la respuesta de alta o de restablecimiento, no se
almacena en claro y no aparece en ningún log.

**Motivo**: FR-023a obliga a aplicar la misma política a la contraseña temporal
"que genera o acepta un `ADMIN`". Generarla cierra la mitad peligrosa de esa
frase: si la elige el administrador, nada impide que use la misma para toda la
plantilla, y el hash sería irrelevante porque la contraseña sería adivinable por
conocida. Generada, el `ADMIN` la transmite por el canal que use, pero no la
elige.

**El generador se valida con el mismo validador que la política.** Si fuesen dos
piezas distintas podrían divergir, y el día que divergieran el sistema generaría
contraseñas que él mismo rechaza — con el alta rota y sin forma de entrar. Hay
un test que genera un lote y las pasa por el validador.

---

## D-012: Política de contraseña como función pura, con máximo en 128

**Decisión**: `PoliticaPassword.validar(password): List<RequisitoIncumplido>`,
sin dependencias. Mínimo 8 con mayúscula, minúscula, dígito y símbolo (FR-023);
máximo **128** caracteres.

**Motivo del máximo**: FR-023b solo prohíbe un máximo por debajo de 64. 128 está
holgadamente por encima, permite una frase de paso larga, y evita que un cuerpo
de petición de megabytes se convierta en trabajo de Argon2 — que con D-002 cuesta
memoria, no solo CPU. Sin ningún máximo, el límite efectivo lo pondría el tamaño
máximo de cuerpo de Tomcat, que es un sitio accidental para una regla de negocio.

El resultado es la **lista** de requisitos incumplidos, no el primero: quien se
equivoca merece saber todo lo que falta en un intento, no descubrirlo de uno en
uno. Devuelve identificadores de requisito, nunca fragmentos de la contraseña
(FR-023).

Lo que **no** comprueba, por no estar en el alcance: que la nueva sea distinta de
la actual, historial de contraseñas, y listas de contraseñas filtradas. Lo último
es lo que de verdad compensaría la política de 8 caracteres y es la ampliación
natural de esta feature.

---

## D-013: El correo se normaliza en un solo sitio y nunca se registra

**Decisión**: `recortado y en minúsculas` (`trim().lowercase(Locale.ROOT)`) antes
de cualquier comparación o escritura, en una única función del dominio. Columna
`VARCHAR(254)` con restricción `UNIQUE`.

**Motivo**: FR-028 exige que dos escrituras del mismo correo no produzcan dos
cuentas, y es el mismo razonamiento que ya se aplicó al documento de identidad en
la feature de jornada. En un solo sitio porque normalizar en el controlador y
olvidarlo en el servicio de restablecimiento es precisamente cómo aparecen dos
cuentas para una persona.

`lowercase(Locale.ROOT)` y no `lowercase()`: con la configuración regional turca,
`I` en minúscula no es `i`, y la unicidad del correo dependería de la
configuración regional del servidor.

254 es el máximo real de una dirección de correo según el RFC 5321.

**El correo es un dato personal** (principio VI): no sale en ningún log, el
`toString()` de la entidad lo omite, y los eventos de seguridad no lo guardan
(D-014). Los dos *loggers* de Hibernate que imprimen valores por reflexión
—`EntityPrinter` y `orm.jdbc.bind`— están ya fijados en `WARN` en `app`, y hay
que fijarlos igual en la configuración de prueba de `auth`, que no lee la de
`app`. Esto no es teórico: así se descubrió la fuga del documento de identidad en
la feature de jornada.

---

## D-014: Eventos de seguridad append-only, sin correo y sin dirección de origen

**Decisión**: tabla `eventos_seguridad` con `cuenta_id` (nulo cuando el correo no
corresponde a ninguna cuenta), `tipo`, `ocurrido_en`. Repositorio que extiende
`Repository<T, ID>` y declara **solo** inserción y lectura.

**Motivo**: FR-017 limita lo registrable al identificador de la cuenta. Dos
consecuencias concretas:

- **Un intento con un correo desconocido guarda `cuenta_id = NULL`**, no el
  correo. Guardarlo sería almacenar un dato personal de alguien que no es
  usuario, por un intento que puede no ser suyo. Se pierde la capacidad de
  detectar enumeración de correos, que es trabajo de la feature de
  endurecimiento.
- **No se guarda la dirección de origen.** Es un dato personal, y el límite por
  origen está fuera de alcance; recogerla "por si acaso" es recoger sin
  finalidad.

El repositorio estrecho es la última regla del principio III y la lección de la
deuda declarada nº 2 (`HistorialMaterialRepository` extiende `JpaRepository` y
ofrece `delete` sobre una tabla append-only): no basta con no llamar al método,
porque una interfaz que lo ofrece acaba usándose.

**Deuda que esta feature declara**: estos eventos no tienen todavía plazo de
conservación. No son el registro de jornada, así que el principio III no les
aplica, pero el art. 5.1.e del RGPD sí: una tabla de auditoría que crece para
siempre es el mismo incumplimiento por el otro lado. Queda anotado y no se
resuelve aquí porque fijar el plazo es una decisión del responsable, no del plan.

---

## D-015: Purga de sesiones muertas (añadido del plan)

**Decisión**: un trabajo programado borra las filas de `sesiones_renovacion`
usadas, revocadas o caducadas hace más de 30 días.

**Motivo**: sin esto la tabla crece sin tope. Con renovación cada 15 minutos,
**una sola persona genera unas 35.000 filas al año**; es la tabla que más crece
del sistema y no contiene nada de valor probatorio — un token ya consumido no
prueba nada que no esté en `eventos_seguridad`.

**Esto no roza el principio III**: ese principio protege el registro de jornada.
Aun así se respeta su forma —solo un proceso automático borra, no hay endpoint ni
rol que pueda hacerlo— porque es el patrón del producto.

**La spec no lo pide.** Se declara aquí como añadido del plan para que
`/speckit-analyze` lo detecte como asimetría y el responsable pueda retirarlo.

---

## D-016: Dónde vive cada test

**Decisión**:

| Test | Módulo | Por qué ahí |
|------|--------|-------------|
| Política de contraseña, política de bloqueo, generador | `auth` (unitario, MockK) | Lógica pura; principio V exige unitarios sin Spring ni base de datos. |
| Servicios contra Postgres real, rotación, bloqueo, RLS, ausencia de datos personales en logs | `auth` (Testcontainers) | Necesitan las migraciones V12–V14 reales. |
| Contrato HTTP de `/api/auth/**` con JSON real | `auth` (puerto real) | La lección de `ContratoHttpIT`: ningún test cruzaba HTTP y eso escondió una caída total por Jackson 3. |
| **SC-001** (entrar y fichar), **SC-008** (token pendiente rechazado en una ruta de fichaje), comodín de `SecurityConfig`, barrido de huérfanas con el directorio real | **`app`** | Solo `app` tiene los dos módulos *y* `SecurityConfig`. En `auth` no existe la cadena de filtros global, así que el test sería vacuo: pasaría sin probar la autorización. |

**Esto es una restricción estructural, no una preferencia.** Las migraciones
V12–V14 son las únicas que ve el classpath de `auth`, igual que `timetracking`
solo ve V6–V11: por eso `RowLevelSecurityIT` de cada módulo no puede ser total y
`app` carga además `EsquemaCompletoRlsIT`.

---

## D-017: Vida de los tokens

**Decisión**: se conservan los valores vigentes —15 minutos el de acceso
(`JWT_EXPIRATION_MINUTES`), 30 días el de renovación
(`AUTH_REFRESH_EXPIRATION_DAYS`, nuevo)—, ambos por variable de entorno.

**Motivo**: son los que ya usa el emisor actual, así que adoptarlos no cambia
nada de lo existente. **Confirmado el 2026-10-06**: el responsable del producto pidió
igualarlos a Squadfy_Backend, que usa exactamente 15 min / 30 días. La única
diferencia que había era el perfil `dev`, que pasó de 1440 min fijos a
`${JWT_EXPIRATION_MINUTES:1000}` como en Squadfy.

El valor de 30 días estaba **escrito a fuego** en `JwtService`
(`refreshTokenValidityMs`). Pasa a configuración, porque con D-004 el plazo lo
decide la fila de `sesiones_renovacion`, no el token.

---

## D-018: Migraciones V12, V13 y V14

**Decisión**: tres migraciones en `auth/src/main/resources/db/migration`,
numeradas **V12–V14**, cada una con `ENABLE ROW LEVEL SECURITY` en la misma
migración que crea su tabla.

**Motivo**: el historial de Flyway es compartido (`classpath:db/migration` en
todos los módulos), así que la numeración es **global**: `inventory` tiene V1–V5
y `timetracking` V6–V11. Reusar un número rompería el arranque con un conflicto
de versión.

RLS en la misma migración lo exige el principio VII, y `RowLevelSecurityIT` lo
comprueba de forma genérica, así que el olvido rompe el build. Nunca
`FORCE ROW LEVEL SECURITY`: el backend conecta como propietario de las tablas y
se quedaría sin acceso a sus propios datos.

Para estas tablas RLS importa especialmente: `cuentas_acceso` contiene correos y
hashes de contraseña, y la API de datos de Supabase publicaría la tabla con la
clave anónima, que es pública por diseño.

---

## Cómo se midió

```bash
# Los jar son los que ya resuelve el proyecto; no hace falta compilarlo.
CRYPTO=$(find ~/.gradle/caches/modules-2/files-2.1/org.springframework.security/spring-security-crypto \
          -name '*.jar' ! -name '*sources*' | head -1)
BC=$(find ~/.gradle/caches/modules-2/files-2.1/org.bouncycastle -name 'bcprov-jdk18on-*.jar' \
      ! -name '*sources*' | head -1)
CL=$(find ~/.gradle/caches/modules-2/files-2.1/commons-logging -name 'commons-logging-1.3*.jar' | head -1)
javac -cp "$CRYPTO:$BC:$CL" Medir.java && java -cp "$CRYPTO:$BC:$CL:." Medir
```

`Medir.java` recorre la rejilla de parámetros de D-002, cronometra 7
repeticiones de cada combinación y comprueba el comportamiento de BCrypt y
Argon2 con una contraseña de 64 caracteres acentuados (D-001). **Repítelo en el
hardware de destino antes de dar por buenos los parámetros**: la tabla de D-002
describe un Mac mini de 10 núcleos, y un contenedor pequeño dará números muy
distintos.

---

## D-019: Colisión de nombres de excepción entre módulos

**Decisión**: las excepciones de `auth` llevan nombres únicos en todo el
producto (`CuentaDeEmpleadoInactivoException`,
`EmpleadoNoEncontradoEnDirectorioException`), y un test en `app` comprueba que
ninguna clase de `auth` existe dos veces en el classpath.

**Motivo**: lo encontró este plan, verificado sobre el código. La constitución
hace que **todos los módulos compartan el namespace `com.granatum.core`**, y
`timetracking` ya declara en `com.granatum.core.domain.exception`:

```
class EmpleadoInactivoException  : InvalidOperationException(...)   // -> 400
class EmpleadoNotFoundException  : NotFoundException(...)           // -> 404
```

`auth` necesita las dos ideas con semántica HTTP distinta —la baja laboral es
`401` al renovar, no `400`—. Cada módulo se empaqueta en su propio jar
(`timetracking-0.0.1-SNAPSHOT.jar`, comprobado) y los dos están en el classpath
de `app`. Con el mismo nombre cualificado **solo se carga una clase**:

- `auth` compila contra la suya, así que el build pasa;
- en ejecución se resuelve la de `timetracking`, y el rechazo por baja laboral
  responde `400 INVALID_OPERATION` en lugar de `401 EMPLEADO_INACTIVO` — o
  lanza `NoSuchMethodError` si los constructores difieren;
- **ningún test de módulo lo ve**, porque en el classpath de `auth` solo está su
  propia clase. Es el mismo patrón de fallo que la caída de Jackson 3: suite en
  verde, aplicación rota.

**Por qué nombres únicos y no un subpaquete**: `com.granatum.core.domain.exception.auth`
también resolvería la colisión, pero la constitución fija el layout de paquetes
de cada feature y meter un nivel extra solo en un módulo lo rompería sin
arreglar el caso general — `inventory` y `timetracking` siguen pudiendo chocar
entre sí mañana.

**Por qué hace falta el test y no basta la disciplina**: la unicidad de nombres
es una convención, y una convención sin test es una intención (principio V). El
test enumera las clases de `auth` y, para cada una, pide
`ClassLoader.getResources("<ruta>.class")`: más de una URL significa que otro
módulo declara la misma clase. Cuesta unas líneas y cubre también las
colisiones futuras entre features, no solo esta.

**Deuda que se declara, no se resuelve aquí**: el riesgo simétrico entre
`inventory` y `timetracking` sigue existiendo (hoy no colisionan, verificado).
Extender el test a todos los módulos pertenece a la feature de endurecimiento
del contrato de la API, no a esta.
