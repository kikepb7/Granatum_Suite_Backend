---

description: "Tareas de implementación: inicio de sesión real (auth)"
---

# Tasks: Inicio de sesión real (auth)

**Input**: Documentos de diseño de `/specs/002-auth/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/README.md](./contracts/README.md), [quickstart.md](./quickstart.md)

**Tests**: **obligatorios**, no opcionales. El principio V de la constitución
exige unitarios con MockK, al menos un test de integración con Testcontainers
contra Postgres real, y un test que falle si se rompe cualquier invariante de los
principios III, IV, VI y VII. Una regla sin test es una intención, no una
garantía.

**Organization**: por historia de usuario, en el orden de prioridad de la spec.
Cada fase deja el build en verde y es comprobable por separado.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: paralelizable (ficheros distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece (US1…US5)
- **➕**: marca los cuatro **añadidos del plan** que la spec no pide, para que se
  puedan retirar sin desmontar nada
  ([plan.md](./plan.md#decisiones-que-añade-el-plan))
- **🔍**: marca lo que cierra los **dos huecos que encontró el plan**
  ([plan.md](./plan.md#huecos-que-el-plan-encontró))

## Path Conventions

Proyecto multi-módulo Gradle. Namespace compartido `com.granatum.core` en todos
los módulos (decisión de la constitución). Rutas desde la raíz del repositorio:

- `auth/src/main/kotlin/com/granatum/core/…` — módulo nuevo
- `auth/src/test/kotlin/com/granatum/core/…`
- `common/src/main/kotlin/com/granatum/core/…` — cambios **aditivos**
- `timetracking/src/main/kotlin/com/granatum/core/…` — solo se **añade** un fichero
- `app/src/…` — cableado y los tests que solo pueden vivir ahí

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: que exista el módulo y compile vacío, antes de escribir lógica.

- [X] T001 Añadir `include("auth")` en `settings.gradle.kts`, después de `include("timetracking")`
- [X] T002 Añadir BouncyCastle a `gradle/libs.versions.toml`: `bouncycastle = "1.79"` en `[versions]` y `bouncycastle-provider = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }` en `[libraries]`. **La versión va explícita**: comprobado que `spring-boot-dependencies-4.0.0-SNAPSHOT.pom` NO gestiona BouncyCastle, y la constitución prohíbe que un `build.gradle.kts` de módulo fije versiones ([D-001](./research.md#d-001-argon2id-en-lugar-de-bcrypt))
- [X] T003 Crear `auth/build.gradle.kts` aplicando `java-library`, `granatum.spring-boot-service` y `kotlin("plugin.jpa")`, con `implementation(projects.common)` y **ninguna otra dependencia de módulo**; `runtimeOnly(libs.bouncycastle.provider)`; starters de data-jpa, validation y security; `testImplementation` de kotlin-test, spring-boot-starter-test, mockk, flyway (core, postgresql, spring-boot-flyway) y el BOM de Testcontainers con junit-jupiter y postgresql. Copiar la estructura de `timetracking/build.gradle.kts`
- [X] T004 Añadir `implementation(projects.auth)` en `app/build.gradle.kts`
- [X] T005 [P] Crear `auth/src/test/kotlin/com/granatum/core/AuthTestApplication.kt` como `@SpringBootApplication @EnableJpaAuditing`, con KDoc explicando que lleva `@EnableJpaAuditing` porque el punto de entrada de producción lo lleva y sin él los campos `@CreatedDate` nunca se poblarían (es el fallo que ya se dio en `timetracking`)
- [X] T006 [P] Crear `auth/src/test/resources/application.yml` con `jwt.secret: ${JWT_SECRET_BASE64}` sin valor literal, `jwt.expiration-minutes: 15`, los dos loggers de Hibernate (`org.hibernate.internal.util.EntityPrinter: WARN` y `org.hibernate.orm.jdbc.bind: WARN`) y parámetros de Argon2 **rebajados** para los tests (`auth.password.argon2.memory-kb: 1024`, `iterations: 1`), con comentario de por qué: 110 ms por login multiplicado por la suite es tiempo de CI tirado, y lo que los tests prueban es la lógica, no el coste
- [X] T007 Añadir el bloque `auth:` a `app/src/main/resources/application.yml` con `refresh-expiration-days: ${AUTH_REFRESH_EXPIRATION_DAYS:30}`, `password.argon2.memory-kb: ${AUTH_ARGON2_MEMORY_KB:65536}`, `password.argon2.iterations: ${AUTH_ARGON2_ITERATIONS:3}`, `password.argon2.parallelism: ${AUTH_ARGON2_PARALLELISM:1}`, `hash.concurrencia: ${AUTH_HASH_CONCURRENCIA:4}`, `hash.espera-ms: ${AUTH_HASH_ESPERA_MS:1000}` y `purga-sesiones.dias: ${AUTH_PURGA_SESIONES_DIAS:30}`. **Ningún secreto nuevo**, así que todos pueden llevar valor por defecto; el único secreto sigue siendo `JWT_SECRET_BASE64`, que no lo lleva
- [X] T008 [P] Añadir las siete variables `AUTH_*` a `.env.example` con sus valores de desarrollo y un comentario de que no son secretos
- [X] T009 Verificar `./gradlew :auth:build` en verde con el módulo todavía vacío, antes de escribir una línea de lógica

**Checkpoint**: el módulo existe, compila y `app` lo agrega.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: esquema, contrato entre features, criptografía y dominio puro.

**⚠️ CRITICAL**: ninguna historia puede empezar hasta terminar esta fase.

### Migraciones (V12–V14, numeración global compartida)

- [X] T010 Crear `auth/src/main/resources/db/migration/V12__create_cuentas_acceso_table.sql`: `id UUID PRIMARY KEY`, `empleado_id UUID NOT NULL`, `email VARCHAR(254) NOT NULL`, `password_hash VARCHAR(255) NOT NULL`, `requiere_cambio_password BOOLEAN NOT NULL DEFAULT TRUE`, `intentos_fallidos SMALLINT NOT NULL DEFAULT 0`, `nivel_bloqueo SMALLINT NOT NULL DEFAULT 0`, `bloqueada_hasta TIMESTAMPTZ`, `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`; `CONSTRAINT uk_cuentas_acceso_email UNIQUE (email)`, `CONSTRAINT uk_cuentas_acceso_empleado UNIQUE (empleado_id)`, `CONSTRAINT ck_cuentas_acceso_nivel CHECK (nivel_bloqueo BETWEEN 0 AND 4)`; cerrar con `ALTER TABLE cuentas_acceso ENABLE ROW LEVEL SECURITY`. Comentar en el fichero **por qué no hay clave ajena a `empleados`** (otro módulo, principio I) y **por qué `requiere_cambio_password` nace en `TRUE`** (no existe vía de alta sin contraseña temporal). Cubre FR-029 y FR-029a: el correo vive aquí y no en los datos laborales
- [X] T011 Crear `auth/src/main/resources/db/migration/V13__create_sesiones_renovacion_table.sql`: `id UUID PRIMARY KEY`, `cuenta_id UUID NOT NULL REFERENCES cuentas_acceso(id) ON DELETE RESTRICT`, `token_hash CHAR(64) NOT NULL`, `expira_en TIMESTAMPTZ NOT NULL`, `creada_en TIMESTAMPTZ NOT NULL`, `usada_en TIMESTAMPTZ`, `revocada_en TIMESTAMPTZ`, `motivo_revocacion VARCHAR(20)`; `CONSTRAINT uk_sesiones_renovacion_token UNIQUE (token_hash)`, `CONSTRAINT ck_sesiones_renovacion_motivo CHECK (motivo_revocacion IS NULL OR motivo_revocacion IN ('LOGOUT', 'RESET', 'CAMBIO_PASSWORD'))`, `CONSTRAINT ck_sesiones_renovacion_revocacion CHECK ((revocada_en IS NULL) = (motivo_revocacion IS NULL))`; `CREATE INDEX idx_sesiones_renovacion_cuenta ON sesiones_renovacion (cuenta_id)`; `ALTER TABLE … ENABLE ROW LEVEL SECURITY`. Comentar que `CHAR(64)` es fijo porque un SHA-256 en hexadecimal siempre mide 64, y que `usada_en` y `revocada_en` están separados a propósito para distinguir "rotada" de "cortada"
- [X] T012 Crear `auth/src/main/resources/db/migration/V14__create_eventos_seguridad_table.sql`: `id UUID PRIMARY KEY`, `cuenta_id UUID` (**nulable**), `tipo VARCHAR(40) NOT NULL`, `ocurrido_en TIMESTAMPTZ NOT NULL`; `CHECK` sobre los 13 tipos de [data-model.md](./data-model.md#eventos_seguridad-v14); `CREATE INDEX idx_eventos_seguridad_cuenta ON eventos_seguridad (cuenta_id, ocurrido_en)`; `ALTER TABLE … ENABLE ROW LEVEL SECURITY`. **APPEND-ONLY**: sin `updated_at` a propósito, sin clave ajena (nada debe poder arrastrar un borrado a la tabla inmutable), y comentario explícito de que no se guarda el correo ni la dirección de origen y por qué ([D-014](./research.md#d-014-eventos-de-seguridad-append-only-sin-correo-y-sin-dirección-de-origen))

### Contrato entre features (`common` + `timetracking`)

- [X] T013 [P] Crear `common/src/main/kotlin/com/granatum/core/domain/type/EstadoEmpleado.kt` con `enum class EstadoEmpleado { ACTIVO, INACTIVO }`
- [X] T014 Crear `common/src/main/kotlin/com/granatum/core/domain/contract/DirectorioEmpleados.kt` con `fun estado(empleadoId: EntityId): EstadoEmpleado?` (null = no existe) y `fun existentes(empleadoIds: Collection<EntityId>): Set<EntityId>`. KDoc con el *por qué*: `auth` necesita saber si una persona existe y está activa, y el principio I le prohíbe depender de `timetracking`; este es el contrato explícito que el propio principio autoriza. **Es estructura nueva del producto** (primer `domain/contract/`), así que T117 la documenta
- [X] T015 Añadir a `timetracking/src/main/kotlin/com/granatum/core/infrastructure/database/repositories/EmpleadoRepository.kt` el método derivado que necesite `existentes` (p. ej. `findAllByIdIn(ids: Collection<UUID>): List<EmpleadoEntity>`), **sin** convertir la interfaz en `JpaRepository`: sigue extendiendo `Repository<T, ID>` porque la tabla no admite borrado (principio III)
- [X] T016 Crear `timetracking/src/main/kotlin/com/granatum/core/infrastructure/directorio/DirectorioEmpleadosJpa.kt` implementando `DirectorioEmpleados` sobre `EmpleadoRepository`. `existentes` en **una sola consulta** (`findAllById` o derivada), nunca un bucle de `estado`: el barrido de huérfanas recorre todas las cuentas y un bucle sería un N+1 garantizado
- [X] T017 Crear `timetracking/src/test/kotlin/com/granatum/core/DirectorioEmpleadosIT.kt` (Testcontainers): `estado` devuelve `ACTIVO`, `INACTIVO` y `null` según el caso, y `existentes` filtra en una sola consulta — contado con las estadísticas de Hibernate, igual que los tests de N+1 de la feature 001

### JWT compartido (`common`, cambios aditivos)

- [X] T018 Modificar `common/src/main/kotlin/com/granatum/core/service/JwtService.kt`: `generateAccessToken(subject, role, requiereCambioPassword: Boolean = false)` que añade el claim `pwd_change` **solo cuando es `true`**, y `fun requiereCambioPassword(token: String): Boolean`. El valor por defecto es lo que garantiza que `inventory`, `timetracking` y `/api/dev/token` no cambian de comportamiento
- [X] T019 Modificar `JwtService` para leer la vida del token de renovación de `${auth.refresh-expiration-days:30}` en lugar de la constante `refreshTokenValidityMs` escrita a fuego ([D-017](./research.md#d-017-vida-de-los-tokens))
- [X] T020 Modificar `common/src/main/kotlin/com/granatum/core/api/config/JwtAuthFilter.kt`: si el token lleva `pwd_change`, conceder **únicamente** `SimpleGrantedAuthority("PWD_CHANGE_ONLY")` y **no** `ROLE_<rol>`. KDoc con el razonamiento: así todas las reglas `hasAnyRole(...)` existentes rechazan por sí solas y la regla nueva es una **concesión**, no una negación — el principio VI ya enseñó con `@Profile("!prod")` por qué una negación es frágil
- [X] T021 Modificar `common/src/main/kotlin/com/granatum/core/service/JwtService.kt` para distinguir **token caducado de token inválido** —`fun estadoToken(token): EstadoToken` con `VALIDO`, `CADUCADO` e `INVALIDO`, capturando `ExpiredJwtException` por separado— y `common/src/main/kotlin/com/granatum/core/api/config/JwtAuthFilter.kt` para dejar marcado en la petición (`request.setAttribute`) que el rechazo fue por caducidad. **Lo exige FR-006**: hoy `parseAllClaims` devuelve `null` ante cualquier excepción, así que caducado e inválido son indistinguibles desde dentro, y sin esa distinción el punto de entrada no puede informar de nada
- [X] T022 [P] Ampliar `common/src/test/kotlin/com/granatum/core/service/JwtServiceTest.kt`: un token sin el claim se comporta exactamente como antes; un token con `pwd_change` lo expone; `requiereCambioPassword` sobre un token inválido no revienta; y **`estadoToken` distingue `CADUCADO` de `INVALIDO`** sobre un token con firma válida pero caducado frente a uno con firma falsa (FR-006)

### Entidades JPA y repositorios

- [X] T023 [P] Crear `auth/src/main/kotlin/com/granatum/core/infrastructure/database/entities/EstadoBloqueoEmbeddable.kt` como `@Embeddable` con `intentosFallidos`, `nivelBloqueo` y `bloqueadaHasta`. Juntos y no sueltos porque las tres columnas solo tienen sentido a la vez: sueltas invitan a escribir `bloqueada_hasta` sin tocar `nivel_bloqueo`, que haría que todos los bloqueos durasen 1 minuto
- [X] T024 Crear `auth/src/main/kotlin/com/granatum/core/infrastructure/database/entities/CuentaAccesoEntity.kt` como **clase normal, nunca `data class`**, con identidad por `id` y `hashCode` por clase, auditoría `@CreatedDate`/`@LastModifiedDate`, y `toString()` que **omite `email` y `passwordHash`**
- [X] T025 [P] Crear `auth/…/entities/SesionRenovacionEntity.kt`, clase normal, con `@ManyToOne(fetch = LAZY)` a la cuenta y `toString()` sin `tokenHash`
- [X] T026 [P] Crear `auth/…/entities/EventoSeguridadEntity.kt`, clase normal, sin `updated_at` y sin relación JPA a la cuenta (solo el UUID): una clave ajena aquí sería un camino para que un borrado llegase a la tabla inmutable
- [X] T027 Crear `auth/…/repositories/CuentaAccesoRepository.kt` extendiendo **`Repository<CuentaAccesoEntity, UUID>`** (no `JpaRepository`): `save`, `findById`, `findByEmail`, `existsByEmail`, `existsByEmpleadoId`, `findByEmpleadoId`, `findAll`, y `findByIdParaActualizar(id)` con `@Lock(PESSIMISTIC_WRITE)` para la transición de bloqueo. **Sin `delete` ni `deleteById`**: la tabla no admite borrado y una interfaz que lo ofrece acaba usándose — es la deuda nº 2 de la constitución
- [X] T028 Crear `auth/…/repositories/SesionRenovacionRepository.kt` extendiendo `Repository<…>` con: `save`, `findByTokenHash`, `@Modifying @Query` de **rotación condicional** (`UPDATE … SET usada_en = :ahora WHERE token_hash = :hash AND usada_en IS NULL AND revocada_en IS NULL AND expira_en > :ahora`, devolviendo el número de filas), `revocarPorTokenHash`, `revocarTodasDeCuenta(cuentaId, motivo, ahora)` y `purgarMuertasAntesDe(corte)`. KDoc: el `UPDATE` condicional **es** la garantía de un solo uso; leer-comprobar-escribir deja una ventana en la que dos peticiones simultáneas emiten dos pares de tokens, que es exactamente el fallo T060 de la feature 001 ([D-006](./research.md#d-006-rotación-de-un-solo-uso-con-update-condicional))
- [X] T029 Crear `auth/…/repositories/EventoSeguridadRepository.kt` extendiendo `Repository<…>` con **solo** `save`, `findAllByCuentaIdOrderByOcurridoEnDesc` y `count`. Sin ninguna operación de mutación ni de borrado (principio III, última regla)
- [X] T030 [P] Crear los modelos de dominio en `auth/src/main/kotlin/com/granatum/core/domain/model/`: `CuentaAcceso`, `EstadoBloqueo` (con `estaBloqueada(ahora): Boolean`) y `ParTokens`, sin anotaciones de JPA ni de HTTP
- [X] T031 [P] Crear `auth/src/main/kotlin/com/granatum/core/infrastructure/database/mappers/` con `Entity → Model` para las tres entidades

### Criptografía (infraestructura, no dominio)

- [X] T032 Crear `auth/src/main/kotlin/com/granatum/core/infrastructure/crypto/PasswordEncoderConfig.kt`: un `DelegatingPasswordEncoder` con `{argon2}` como identificador por defecto, construido con `Argon2PasswordEncoder(16, 32, parallelism, memoryKb, iterations)` leídos de `auth.password.argon2.*`. KDoc con los números medidos (64 MiB/t=3 → 110 ms; los defaults de Spring Security → 16 ms, un retroceso de 13× frente a BCrypt(12)) y el aviso de recalibrar en el hardware de destino ([D-002](./research.md#d-002-parámetros-de-argon2id--m64-mib-t3-p1)). Cubre FR-004
- [X] T033 Añadir en `PasswordEncoderConfig` un bean `hashSenuelo` cifrado **al arrancar** a partir de una contraseña aleatoria **con el encoder configurado**. KDoc: `Argon2PasswordEncoder.matches` lee los parámetros *del hash almacenado*, así que un señuelo con otros parámetros tendría otro tiempo y reabriría el canal lateral que viene a cerrar ([D-005](./research.md#d-005-rechazo-uniforme-con-hash-siempre-también-para-cuentas-bloqueadas))
- [X] T034 Crear `auth/src/test/kotlin/com/granatum/core/PasswordEncoderTest.kt` sobre el bean configurado: `encode` devuelve un valor que empieza por `{argon2}`; **cifrar dos veces la misma contraseña da resultados distintos** (hay sal por cifrado); y `matches` acepta la original y rechaza otra. **Es el invariante del principio VI que faltaba** (SC-003): el principio exige un hash adaptativo y salado, y sin este test cambiar el encoder por uno rápido o sin sal no rompería nada — y una regla sin test es una intención, no una garantía (principio V)
- [X] T035 🔍 ➕ Crear `auth/src/main/kotlin/com/granatum/core/infrastructure/crypto/VerificadorAcotado.kt`: envuelve `encode`/`matches` en un `Semaphore(auth.hash.concurrencia)` con `tryAcquire` y un plazo **derivado de SC-011**: `auth.hash.espera-ms`, por defecto **1000 ms**, que deja un segundo entero de margen sobre el presupuesto de 2 s del inicio de sesión. Va en configuración y no como constante porque es lo único que decide si un login en cola incumple SC-011; al agotarse lanza `VerificacionSaturadaException`. KDoc con el motivo: `POST /api/auth/login` es público y cada verificación reserva 64 MiB; con los 200 hilos por defecto de Tomcat son **12,8 GiB** que cualquiera puede provocar sin credencial, y el límite por IP está fuera de alcance por decisión de la spec. Con el semáforo el techo es 256 MiB ([D-003](./research.md#d-003-límite-de-verificaciones-simultáneas-consecuencia-de-d-002))
- [X] T036 Crear `auth/src/test/kotlin/com/granatum/core/VerificadorAcotadoTest.kt` (MockK): con el semáforo a 1 y una verificación en curso, la segunda lanza `VerificacionSaturadaException`; y al liberarse vuelve a admitir trabajo (un semáforo que no devuelve los permisos es una avería peor que la que evita)
- [X] T037 [P] Crear `auth/src/main/kotlin/com/granatum/core/infrastructure/crypto/GeneradorPasswordTemporal.kt`: 16 caracteres de `SecureRandom` con las cuatro clases garantizadas y posiciones mezcladas

### Dominio puro

- [X] T038 [P] Crear `auth/src/main/kotlin/com/granatum/core/domain/type/RequisitoIncumplido.kt` con `LONGITUD_MINIMA`, `LONGITUD_MAXIMA`, `FALTA_MAYUSCULA`, `FALTA_MINUSCULA`, `FALTA_DIGITO`, `FALTA_SIMBOLO`
- [X] T039 Crear `auth/src/main/kotlin/com/granatum/core/domain/service/PoliticaPassword.kt` como `object` puro: `validar(password): List<RequisitoIncumplido>` con **mínimo 8** caracteres y mayúscula, minúscula, dígito y símbolo (FR-023), y **máximo 128** (FR-023b solo prohíbe por debajo de 64). Devuelve **todos** los requisitos incumplidos, no el primero, y **nunca** fragmentos de la contraseña
- [X] T040 Crear `auth/src/test/kotlin/com/granatum/core/PoliticaPasswordTest.kt`: los cuatro incumplimientos por separado y combinados; `Granatum1!` válida; **una frase de 64 caracteres acentuados (128 bytes UTF-8) válida** —el caso que descarta BCrypt ([D-001](./research.md#d-001-argon2id-en-lugar-de-bcrypt))—; 129 caracteres rechazada; y que ningún valor devuelto contiene parte de la contraseña
- [X] T041 Crear `auth/src/test/kotlin/com/granatum/core/GeneradorPasswordTemporalTest.kt`: **1000 contraseñas generadas pasan `PoliticaPassword.validar` sin un solo incumplimiento**. Si generador y política divergieran, el sistema generaría contraseñas que él mismo rechaza y el alta quedaría rota sin forma de entrar. **Va después de la política y no junto al generador**: la política es su único juez, así que colocarlo antes sería escribirlo contra nada ([D-011](./research.md#d-011-la-contraseña-temporal-la-genera-el-sistema))
- [X] T042 Crear `auth/src/main/kotlin/com/granatum/core/domain/service/PoliticaBloqueo.kt` como `object` puro: `duracion(nivel): Duration` con **1, 5, 15 y 60 minutos** (nivel 4 y superiores, 60), `trasFallo(estado, ahora): EstadoBloqueo` y `trasExito(): EstadoBloqueo`. Reglas exactas de [data-model.md](./data-model.md#transiciones-permitidas): con bloqueo activo **no cambia nada**; sin bloqueo activo suma 1 y al llegar a 5 sube nivel (topando en 4), fija `bloqueadaHasta` y **pone los intentos a 0**; el éxito lo deja todo a cero (FR-016a)
- [X] T043 Crear `auth/src/test/kotlin/com/granatum/core/PoliticaBloqueoTest.kt`: la escalada 1 → 5 → 15 → 60 y que se queda en 60; que 4 fallos no bloquean y el 5.º sí; que el éxito pone nivel e intentos a cero (FR-016b); y **el test que sostiene la feature (FR-016c)**: un fallo con bloqueo activo devuelve un estado **idéntico** al de entrada, `bloqueadaHasta` incluido. Sin esa regla cualquiera deja a una persona fuera indefinidamente sin conocer su contraseña, y aquí eso significa impedirle fichar y abrir un hueco en un registro con valor legal
- [X] T044 [P] Crear `auth/src/main/kotlin/com/granatum/core/domain/type/TipoEventoSeguridad.kt` con los 13 tipos de [data-model.md](./data-model.md#eventos_seguridad-v14), y comentario de por qué `RENOVACION_TOKEN_REUTILIZADO` es distinto de `RENOVACION_RECHAZADA`: es la única señal de posible robo que deja la feature
- [X] T045 Crear `auth/src/main/kotlin/com/granatum/core/service/RegistradorEventosSeguridad.kt` con `Clock` inyectado y valor por defecto `Clock.systemUTC()`, siguiendo el patrón de `RegistradorEventos` en `timetracking`. Inserta y nada más

### Errores

- [X] T046 🔍 Crear `auth/src/main/kotlin/com/granatum/core/domain/exception/AuthExceptions.kt` con `CredencialesInvalidasException`, `TokenRenovacionInvalidoException`, `CuentaDeEmpleadoInactivoException`, `EmpleadoNoEncontradoEnDirectorioException`, `CuentaNoEncontradaException`, `CuentaYaExisteException`, `EmailYaRegistradoException`, `PasswordDebilException(requisitos)` y `VerificacionSaturadaException`. **Los dos nombres largos son obligatorios, no estilísticos**: `timetracking` ya declara `EmpleadoInactivoException` y `EmpleadoNotFoundException` en el **mismo paquete** `com.granatum.core.domain.exception`, y con el mismo nombre cualificado solo se carga una clase — `auth` compilaría y en ejecución se resolvería la de `timetracking`, devolviendo 400 donde toca 401 ([D-019](./research.md#d-019-colisión-de-nombres-de-excepción-entre-módulos)). Ningún mensaje contiene correo, contraseña ni token
- [X] T047 Crear `auth/src/main/kotlin/com/granatum/core/api/exception_handling/AuthExceptionHandler.kt` con **`@Order(Ordered.HIGHEST_PRECEDENCE)`** y el mapeo completo de [contracts/README.md](./contracts/README.md#resumen-de-códigos-de-error). KDoc con el motivo de la precedencia: estas excepciones extienden las de `common`, cuyo manejador de `InvalidOperationException` encaja con todas, y Spring se queda con el primer advice que encaje — sin precedencia explícita **cada conflicto saldría como 400 INVALID_OPERATION**. Ya pasó en la feature 001
- [X] T048 Añadir a `auth/src/main/kotlin/com/granatum/core/api/exception_handling/AuthExceptionHandler.kt` el manejador de `PasswordDebilException` que emite el **único error de la feature con campo extra**: `{code, message, requisitos}` (FR-023 obliga a explicar qué requisito falla, y un cliente que marque campos necesita identificadores, no una frase)

### Invariantes de esquema

- [X] T049 Crear `auth/src/test/kotlin/com/granatum/core/RowLevelSecurityIT.kt` copiando el patrón del de `timetracking`: (1) las **tres tablas esperadas existen** por nombre —si las migraciones no corrieran, la aserción de RLS pasaría sobre un esquema vacío y no probaría nada, que es el fallo por el que ya pasó este proyecto cuando Flyway no estaba cableado—; (2) ninguna tabla del esquema `public` sin RLS, excluyendo `flyway_schema_history`; (3) un rol no propietario con `SELECT` no ve ni una fila mientras el propietario sí ve las suyas
- [X] T050 Verificar `./gradlew :auth:build` en verde: migraciones, entidades, repositorios, dominio y sus unitarios

**Checkpoint**: esquema con RLS, contrato entre features operativo, criptografía elegida y medida, dominio puro con sus tests. Las historias pueden empezar.

---

## Phase 3: User Story 1 - Iniciar sesión y usar la aplicación (Priority: P1) 🎯 MVP

**Goal**: una persona activa entra con correo y contraseña, recibe un par de
tokens, y con el de acceso ficha de verdad.

**Independent Test**: iniciar sesión con credenciales válidas y usar el token en
una operación real de fichaje. Sustituye por completo a `/api/dev/token`.

### Tests for User Story 1 ⚠️

> Escribir primero y comprobar que **fallan** antes de implementar.

- [X] T051 [P] [US1] Crear `auth/src/test/kotlin/com/granatum/core/LoginIT.kt` (Testcontainers): credenciales válidas devuelven par de tokens y `expiresIn` en segundos; contraseña incorrecta → `CREDENCIALES_INVALIDAS`; correo inexistente → **el mismo código y el mismo cuerpo**; persona inactiva → también el mismo (FR-002, FR-003); el token de acceso lleva sujeto y rol (FR-005). Cubre también FR-001 y SC-010
- [X] T052 [P] [US1] Crear `auth/src/test/kotlin/com/granatum/core/IndistinguibilidadLoginIT.kt`: 30 intentos con correo inexistente y 30 con contraseña incorrecta, comparando **medianas** de tiempo, no medidas sueltas. KDoc: la igualdad es estadística, no criptográfica; prometer tiempo constante sobre la JVM sería mentir, y lo que importa es quedar por debajo del ruido de red (SC-002). **Afirmar además SC-011**: todo inicio de sesión, correcto o rechazado, termina en menos de 2 segundos, incluido el coste deliberado del hash
- [X] T053 [P] [US1] Crear `auth/src/test/kotlin/com/granatum/core/ContratoHttpAuthIT.kt` con `@SpringBootTest(webEnvironment = RANDOM_PORT)` y `RestClient`, cubriendo `POST /api/auth/login` con **JSON real sobre un socket real**. KDoc: es la lección de `ContratoHttpIT` en la feature 001 — ningún test cruzaba el borde HTTP y eso escondió una caída total (Jackson 3) con la suite entera en verde. Ni MockMvc ni `TestRestTemplate`: Boot 4 los retiró y además nunca abren un socket
- [X] T054 [US1] Crear `app/src/test/kotlin/com/granatum/core/TokenCaducadoIT.kt` con puerto real: un token de acceso **caducado** devuelve `401` con `code: TOKEN_ACCESO_EXPIRADO`; **sin** cabecera `Authorization` devuelve `401` con `code: NO_AUTENTICADO`; y los dos cuerpos cumplen el formato `{code, message}`. Juntos acreditan lo que pide FR-006: que los dos casos sean distinguibles. Vive en `app` porque el punto de entrada forma parte de la cadena de filtros global, que solo existe ahí ([D-016](./research.md#d-016-dónde-vive-cada-test))

### Implementation for User Story 1

- [X] T055 [P] [US1] Crear `auth/src/main/kotlin/com/granatum/core/api/dto/LoginRequest.kt` con `@NotBlank @Email` y máx. 254 en `email`, y `@NotBlank` con máx. 128 en `password`; y `ParTokensResponse` con `accessToken`, `refreshToken`, `expiresIn` y `requiereCambioPassword`
- [X] T056 [US1] Crear `auth/src/main/kotlin/com/granatum/core/service/NormalizadorEmail.kt`: `trim().lowercase(Locale.ROOT)` en **una sola función**. `Locale.ROOT` y no `lowercase()` a secas: con la configuración regional turca `I` no baja a `i`, y la unicidad del correo dependería del idioma del servidor ([D-013](./research.md#d-013-el-correo-se-normaliza-en-un-solo-sitio-y-nunca-se-registra))
- [X] T057 [US1] Crear `auth/src/main/kotlin/com/granatum/core/service/AutenticacionService.kt` con `Clock` inyectado: normaliza el correo, busca la cuenta, **verifica siempre un hash** —el real si existe, el señuelo si no—, consulta `DirectorioEmpleados.estado(empleadoId)` y exige `ACTIVO`, y emite el par. Todo fallo sale como `CredencialesInvalidasException` (FR-001, FR-002)
- [X] T058 [US1] Implementar en `AutenticacionService` la emisión del token de renovación **opaco**: 32 bytes de `SecureRandom` en base64url, guardando **solo** su SHA-256 en hexadecimal. KDoc con la distinción que un revisor leería como incumplimiento del principio VI si no se explica: SHA-256 está prohibido para contraseñas porque tienen poca entropía y hay que encarecer la fuerza bruta; un token de 256 bits aleatorios no tiene nada que adivinar, y un hash lento solo encarecería cada renovación legítima ([D-004](./research.md#d-004-el-token-de-renovación-es-opaco-no-un-jwt)). Cubre FR-011
- [X] T059 [US1] Crear `auth/src/main/kotlin/com/granatum/core/api/controllers/AuthController.kt` con `POST /api/auth/login` (`@Valid`), delegando en el servicio y sin construir errores a mano (principio VIII). Cubre FR-001
- [X] T060 [US1] Registrar los eventos `LOGIN_CORRECTO`, `LOGIN_FALLIDO`, `LOGIN_CUENTA_DESCONOCIDA` y `LOGIN_EMPLEADO_INACTIVO`, **sin correo, sin contraseña, sin token y sin dirección de origen** (FR-017), desde `auth/src/main/kotlin/com/granatum/core/service/AutenticacionService.kt` a través de `RegistradorEventosSeguridad`
- [X] T061 [US1] Añadir a `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt` la regla `permitAll` para `POST /api/auth/login`, con comentario de que la credencial va en el cuerpo y no en la cabecera
- [X] T062 [US1] Crear `app/src/main/kotlin/com/granatum/core/api/security/EntryPointJson.kt` como `AuthenticationEntryPoint` que emite el formato único `{code, message}` distinguiendo `TOKEN_ACCESO_EXPIRADO` de `NO_AUTENTICADO` según la marca que deja el filtro, y sustituir con él el `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` de `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt`. **Cierra FR-006**: hoy un token de acceso caducado devuelve un `401` con **cuerpo vacío**, que ni indica que la sesión expiró ni lleva `code` —así que además incumple el principio VIII—, y la aplicación móvil no puede saber si debe renovar o pedir la contraseña. Añadir en la misma tarea un `AccessDeniedHandler` con el mismo formato, para que el `403` no quede como la única respuesta sin cuerpo

**Checkpoint**: se inicia sesión de verdad; US1 funciona y se prueba sola.

---

## Phase 4: User Story 2 - Mantener y cerrar la sesión (Priority: P1)

**Goal**: la aplicación renueva en segundo plano y el cierre de sesión invalida
ese token de renovación en el acto.

**Independent Test**: renovar y obtener un par nuevo; cerrar sesión y comprobar
que el mismo token de renovación ya no sirve.

### Tests for User Story 2 ⚠️

- [X] T063 [P] [US2] Crear `auth/src/test/kotlin/com/granatum/core/RenovacionIT.kt`: renovar devuelve par nuevo; **el token presentado ya no sirve** (FR-008); un token caducado se rechaza; un token revocado se rechaza; renovar con la persona inactiva se rechaza con `EMPLEADO_INACTIVO` (FR-010, SC-010). Cubre también FR-007
- [X] T064 [P] [US2] Crear `auth/src/test/kotlin/com/granatum/core/RotacionConcurrenteIT.kt`: **N hilos renuevan con el mismo token a la vez y exactamente uno gana** (SC-004 pide el 100%). Es el test que distingue el `UPDATE` condicional de un leer-comprobar-escribir, y el que habría pillado el agujero T060 de la feature 001
- [X] T065 [P] [US2] Crear `auth/src/test/kotlin/com/granatum/core/CierreSesionIT.kt`: con dos sesiones abiertas, cerrar una deja la otra funcionando (FR-012, SC-006); cerrar dos veces devuelve `204` y no un error
- [X] T066 [P] [US2] Ampliar `auth/src/test/kotlin/com/granatum/core/ContratoHttpAuthIT.kt` con `refresh` y `logout` sobre JSON real, comprobando que `logout` responde `204 No Content` sin cuerpo

### Implementation for User Story 2

- [X] T067 [P] [US2] Crear `auth/src/main/kotlin/com/granatum/core/api/dto/RefreshRequest.kt` y `LogoutRequest.kt`, con `@NotBlank` en `refreshToken`
- [X] T068 [US2] Crear `auth/src/main/kotlin/com/granatum/core/service/SesionService.kt` con `Clock` inyectado: `rotar(refreshToken)` ejecuta el **`UPDATE` condicional** y continúa solo si afectó a **1 fila**; con 0 filas distingue si el token existía y ya estaba usado para emitir `RENOVACION_TOKEN_REUTILIZADO` en lugar de `RENOVACION_RECHAZADA`. Cubre FR-007 y FR-008
- [X] T069 [US2] Implementar en `SesionService` que la rotación **no revoca la cadena** al detectar reutilización, con KDoc del por qué: la causa habitual no es un robo sino un cliente móvil reintentando una renovación cuya respuesta se perdió; revocar la cadena dejaría a la persona fuera de todos sus dispositivos por una red mala, y aquí quedarse fuera es **no poder fichar**. El caso de dispositivo perdido tiene su vía en FR-025 ([D-007](./research.md#d-007-reutilizar-un-token-ya-usado-no-revoca-la-cadena))
- [X] T070 [US2] Implementar `cerrar(refreshToken)` en `auth/src/main/kotlin/com/granatum/core/service/SesionService.kt`: revoca **solo esa fila** con motivo `LOGOUT`, e **idempotente** — un token desconocido o ya revocado también devuelve éxito, porque un `404` aquí distinguiría tokens existentes de inexistentes. Cubre FR-009
- [X] T071 [US2] Comprobar en la rotación el estado de la persona vía `DirectorioEmpleados` y rechazar con `CuentaDeEmpleadoInactivoException` (FR-010). **Aquí sí se distingue del rechazo genérico**, al contrario que en `login`: quien presenta un token de renovación válido ya demostró ser titular, así que no hay existencia que ocultarle — en `auth/src/main/kotlin/com/granatum/core/service/SesionService.kt`
- [X] T072 [US2] Añadir `POST /api/auth/refresh` y `POST /api/auth/logout` a `auth/src/main/kotlin/com/granatum/core/api/controllers/AuthController.kt`, el segundo con `@ResponseStatus(NO_CONTENT)`
- [X] T073 [US2] Añadir las dos rutas como `permitAll` en `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt`, con comentario de por qué `logout` es pública: lo normal es cerrar sesión cuando el token de acceso **ya caducó**, y exigirlo haría imposible la operación justo cuando se necesita
- [X] T074 [US2] Registrar `RENOVACION_CORRECTA`, `RENOVACION_RECHAZADA`, `RENOVACION_TOKEN_REUTILIZADO` y `CIERRE_SESION`, desde `auth/src/main/kotlin/com/granatum/core/service/SesionService.kt` a través de `RegistradorEventosSeguridad`

**Checkpoint**: sesión completa — entrar, renovar y salir. US1 y US2 funcionan por separado.

---

## Phase 5: User Story 3 - Resistir un ataque de fuerza bruta (Priority: P2)

**Goal**: tras 5 fallos seguidos la cuenta se bloquea temporalmente, y el
bloqueo crece si se repite.

**Independent Test**: fallar 5 veces y comprobar que el sexto intento se rechaza
**con la contraseña correcta**, y que tras el plazo vuelve a funcionar.

### Tests for User Story 3 ⚠️

- [X] T075 [P] [US3] Crear `auth/src/test/kotlin/com/granatum/core/BloqueoIT.kt`: 5 fallos bloquean (FR-013); el 6.º con la contraseña **correcta** se rechaza (FR-014); cumplido el plazo entra sin intervención (FR-016); 4 fallos y un acierto dejan el contador a cero (FR-015). Cubre SC-005
- [X] T076 [P] [US3] Crear `auth/src/test/kotlin/com/granatum/core/BloqueoCrecienteIT.kt`: la escalada 1 → 5 → 15 → 60 sobre la base de datos (SC-012), y **FR-016c end-to-end**: un fallo durante un bloqueo activo deja `nivel_bloqueo`, `intentos_fallidos` y `bloqueada_hasta` **idénticos**
- [X] T077 [P] [US3] Crear `auth/src/test/kotlin/com/granatum/core/BloqueoConcurrenteIT.kt`: 5 fallos **simultáneos** acaban en bloqueo. Sin el `FOR UPDATE`, dos lecturas de `intentos = 4` escribirían las dos `5` y la cuenta quedaría sin bloquear
- [X] T078 [P] [US3] Añadir a `auth/src/test/kotlin/com/granatum/core/BloqueoIT.kt` la comprobación de que una cuenta bloqueada responde **exactamente igual** que una contraseña incorrecta: mismo código, mismo cuerpo (➕ decisión del plan, D-005)

### Implementation for User Story 3

- [X] T079 [US3] Implementar en `auth/src/main/kotlin/com/granatum/core/service/AutenticacionService.kt` el rechazo rápido por `bloqueada_hasta > ahora` sobre la lectura **sin bloqueo de fila**, antes de verificar el hash
- [X] T080 [US3] Implementar la transición en una **transacción corta** que relee con `findByIdParaActualizar` y aplica `PoliticaBloqueo`. KDoc del orden: la verificación de Argon2 ocurre **antes y fuera** de esa transacción, porque mantener un bloqueo de fila durante 110 ms serializaría los intentos contra una misma cuenta y convertiría el endurecimiento en el amplificador de una denegación de servicio — justo lo que FR-016c evita por otra vía. Documentar la ventana aceptada: entre la lectura sin bloqueo y la transacción puede colarse un intento extra, que se rechaza igual y no prolonga nada ([D-008](./research.md#d-008-bloqueo-creciente--máquina-de-estados-pura-y-bloqueo-de-fila-corto))
- [X] T081 [US3] ➕ Hacer que el rechazo por bloqueo emita `CredencialesInvalidasException`, idéntico al de credenciales, y que **también verifique el señuelo** para que el tiempo coincida. KDoc con el coste que se acepta: quien se equivoca cinco veces no ve "vuelve a intentarlo en 1 minuto" y no sabe cuánto esperar; a cambio, una respuesta específica de "bloqueada" revelaría que el correo existe y reabriría el oráculo que FR-003 cierra — en `auth/src/main/kotlin/com/granatum/core/service/AutenticacionService.kt`
- [X] T082 [US3] Aplicar `PoliticaBloqueo.trasExito()` en el inicio de sesión correcto, poniendo nivel e intentos a cero (FR-015, FR-016b) — en `auth/src/main/kotlin/com/granatum/core/service/AutenticacionService.kt`
- [X] T083 [US3] Registrar `LOGIN_CUENTA_BLOQUEADA` y `CUENTA_BLOQUEADA` como tipos distintos: el primero es un intento contra una cuenta ya bloqueada, el segundo el instante en que se bloquea, desde `auth/src/main/kotlin/com/granatum/core/service/AutenticacionService.kt` a través de `RegistradorEventosSeguridad`
- [X] T084 [US3] 🔍 Enganchar `VerificadorAcotado` (T035) en todas las rutas de verificación de `auth/src/main/kotlin/com/granatum/core/service/AutenticacionService.kt`, y mapear `VerificacionSaturadaException` a `503` con cabecera `Retry-After`

**Checkpoint**: la fuerza bruta sale caro y el bloqueo no se puede usar para dejar a nadie fuera.

---

## Phase 6: User Story 4 - Alta con contraseña temporal y primer acceso (Priority: P2)

**Goal**: un `ADMIN` da acceso en **una sola operación**, y en el primer acceso
la persona no puede hacer nada salvo cambiar su contraseña.

**Independent Test**: dar acceso, entrar con la temporal, comprobar que todo lo
demás se rechaza, cambiarla, y comprobar que entonces sí se opera.

### Tests for User Story 4 ⚠️

- [X] T085 [P] [US4] Crear `auth/src/test/kotlin/com/granatum/core/AltaCuentaIT.kt`: el alta crea la cuenta pendiente de cambio en una sola llamada (FR-029b); `empleadoId` inexistente → `404 EMPLEADO_NO_ENCONTRADO` (FR-029c); segunda cuenta para la misma persona → `409 CUENTA_YA_EXISTE` (FR-027, FR-029d); correo ya usado → `409 EMAIL_YA_REGISTRADO` (FR-028); y que la contraseña temporal **generada cumple la política** (FR-023a). Cubre SC-013, y comprueba además que el `password_hash` almacenado empieza por `{argon2}` — el 100% de los valores guardados son hashes salados (SC-003)
- [X] T086 [P] [US4] Crear `auth/src/test/kotlin/com/granatum/core/CambioPasswordIT.kt`: el cambio aportando la actual retira la marca de pendiente (FR-021); contraseña actual incorrecta → `401` (FR-022); contraseña débil → `422 PASSWORD_DEBIL` con la **lista de requisitos** y sin reproducir la contraseña (FR-023); **una frase de 64 caracteres acentuados se acepta** (FR-023b). Cubre SC-014
- [X] T087 [P] [US4] Crear `auth/src/test/kotlin/com/granatum/core/PendienteDeCambioIT.kt`: el token emitido a una cuenta pendiente lleva el claim, concede `PWD_CHANGE_ONLY` y **no** `ROLE_*`, y `requiereCambioPassword` viaja en la respuesta de login (US4 escenario 2, FR-020)
- [X] T088 [P] [US4] Ampliar `auth/src/test/kotlin/com/granatum/core/ContratoHttpAuthIT.kt` con `change-password` y `POST /api/auth/cuentas` sobre JSON real, comprobando el cuerpo de `PASSWORD_DEBIL` con su campo `requisitos`

### Implementation for User Story 4

- [X] T089 [P] [US4] Crear `auth/src/main/kotlin/com/granatum/core/api/dto/AltaCuentaRequest.kt` (`empleadoId` `@NotNull`, `email` `@NotBlank @Email` máx. 254), `AltaCuentaResponse` (con `passwordTemporal`) y `CambioPasswordRequest` (`passwordActual` y `passwordNueva`, `@NotBlank`, máx. 128)
- [X] T090 [US4] Crear `auth/src/main/kotlin/com/granatum/core/service/CuentaAccesoService.kt` con `darAcceso(empleadoId, email)`: comprueba la persona en `DirectorioEmpleados`, rechaza duplicados de persona y de correo, genera la temporal, la cifra y crea la cuenta con `requiereCambioPassword = true` (FR-019)
- [X] T091 [US4] Crear `auth/src/main/kotlin/com/granatum/core/api/controllers/CuentaAccesoController.kt` con `POST /api/auth/cuentas` → `201`. **La contraseña temporal aparece en esa respuesta y en ningún otro sitio jamás**: no se almacena en claro, no se registra y no se puede volver a consultar; si se pierde, la vía es restablecer
- [X] T092 [US4] Implementar `cambiarPassword(cuentaId, actual, nueva)` en `auth/src/main/kotlin/com/granatum/core/service/CuentaAccesoService.kt`: valida con `PoliticaPassword` **antes** de verificar la actual —rechazar una contraseña débil no requiere comprobar credenciales y ahorra una verificación de 110 ms—, exige la actual, cifra la nueva y retira la marca
- [X] T093 [US4] Añadir `POST /api/auth/change-password` a `auth/src/main/kotlin/com/granatum/core/api/controllers/AuthController.kt`, tomando la cuenta **del sujeto del token** y nunca del cuerpo (principio IV), y devolviendo un par de tokens nuevo con `requiereCambioPassword: false`
- [X] T094 [US4] ➕ Hacer que un cambio correcto revoque **las demás** sesiones de la cuenta con motivo `CAMBIO_PASSWORD`. La spec solo lo exige al restablecer (FR-025); el motivo es que cambiar la contraseña suele ser la reacción a una sospecha de robo y dejar los otros dispositivos dentro vacía el gesto. **Coste**: cambiarla en el móvil cierra la sesión del navegador — en `auth/src/main/kotlin/com/granatum/core/service/CuentaAccesoService.kt`
- [X] T095 [US4] Añadir a `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt`: `POST /api/auth/change-password` exige `hasAnyAuthority("PWD_CHANGE_ONLY", "ROLE_ADMIN", "ROLE_ENCARGADO", "ROLE_EMPLEADO", "ROLE_REPRESENTANTE")`, y `/api/auth/cuentas/**` exige `hasRole("ADMIN")`. Siempre **concesiones**, nunca negaciones. Cubre FR-018 y FR-024: solo `ADMIN` crea o restablece credenciales
- [X] T096 [US4] Endurecer el comodín de `app/src/main/kotlin/com/granatum/core/api/security/SecurityConfig.kt`: `anyRequest().authenticated()` → `anyRequest().hasAnyRole("ADMIN", "ENCARGADO", "EMPLEADO", "REPRESENTANTE")`. Un token pendiente de cambio **está** autenticado, así que con el comodín anterior alcanzaría cualquier ruta futura sin regla de rol explícita. Comentario en el fichero advirtiendo de que T111 lo vigila, porque es un cambio de una línea que al revertirse no rompería nada visible
- [X] T097 [US4] Registrar `CUENTA_CREADA` y `PASSWORD_CAMBIADA`, desde `auth/src/main/kotlin/com/granatum/core/service/CuentaAccesoService.kt` a través de `RegistradorEventosSeguridad`

**Checkpoint**: se puede incorporar personal, y una contraseña conocida por el administrador no se queda como permanente.

---

## Phase 7: User Story 5 - Restablecer una contraseña olvidada (Priority: P3)

**Goal**: un `ADMIN` asigna una temporal nueva, cierra todas las sesiones y
levanta el bloqueo.

**Independent Test**: restablecer, entrar con la temporal, que se exija el
cambio, y que las sesiones anteriores ya no sirvan.

### Tests for User Story 5 ⚠️

- [X] T098 [P] [US5] Crear `auth/src/test/kotlin/com/granatum/core/RestablecimientoIT.kt`: deja la cuenta con temporal pendiente de cambio (FR-024); **invalida el 100% de las sesiones abiertas** —dos sesiones, las dos muertas— (FR-025, SC-007); levanta el bloqueo por intentos fallidos (FR-026); quien no es `ADMIN` recibe `403` (US5 escenario 4); una persona sin cuenta → `404 CUENTA_NO_ENCONTRADA`
- [X] T099 [P] [US5] Ampliar `auth/src/test/kotlin/com/granatum/core/ContratoHttpAuthIT.kt` con `POST /api/auth/cuentas/{empleadoId}/restablecer` sobre JSON real

### Implementation for User Story 5

- [X] T100 [US5] Implementar `restablecer(empleadoId)` en `auth/src/main/kotlin/com/granatum/core/service/CuentaAccesoService.kt`: genera y cifra una temporal nueva, marca pendiente de cambio, pone intentos y nivel a cero y `bloqueadaHasta` a nulo, y revoca **todas** las sesiones vivas con motivo `RESET`
- [X] T101 [US5] Añadir `POST /api/auth/cuentas/{empleadoId}/restablecer` a `auth/src/main/kotlin/com/granatum/core/api/controllers/CuentaAccesoController.kt`. **Indexado por `empleadoId` y no por `cuentaId`**: es el identificador que el `ADMIN` tiene a mano en la ficha de la persona, y evita una consulta previa
- [X] T102 [US5] Registrar `PASSWORD_RESTABLECIDA`, desde `auth/src/main/kotlin/com/granatum/core/service/CuentaAccesoService.kt` a través de `RegistradorEventosSeguridad`

**Checkpoint**: existe camino de vuelta sin recuperación por correo. Las cinco historias funcionan.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: cerrar los huecos del plan, los invariantes transversales, los
tests que solo pueden vivir en `app`, y la documentación.

### Cuentas huérfanas y purga

- [X] T103 [P] Crear `auth/src/main/kotlin/com/granatum/core/service/DeteccionHuerfanasService.kt`: pagina las cuentas y usa `DirectorioEmpleados.existentes(...)` **en lote** para hallar las que apuntan a nadie
- [X] T104 Añadir `GET /api/auth/cuentas/huerfanas` a `auth/src/main/kotlin/com/granatum/core/api/controllers/CuentaAccesoController.kt` (`ADMIN`), devolviendo `total` y una lista de `{cuentaId, empleadoId, creadaEn}` **sin el correo**: para limpiar basta el identificador, y el correo sería un dato personal innecesario en un listado de diagnóstico
- [X] T105 [P] Crear `auth/src/main/kotlin/com/granatum/core/scheduling/DeteccionHuerfanasJob.kt` que registre **solo el recuento**, nunca identificadores (principio VI), con `cron` configurable
- [X] T106 [P] ➕ Crear `auth/src/main/kotlin/com/granatum/core/scheduling/PurgaSesionesJob.kt` que borre las filas de `sesiones_renovacion` muertas desde hace más de `auth.purga-sesiones.dias`. KDoc: sin esto la tabla crece sin tope —con renovación cada 15 minutos, ~35.000 filas al año **por persona**— y un token consumido no prueba nada que no esté en `eventos_seguridad`. Respeta la forma del principio III: **solo un proceso automático borra**, sin endpoint ni rol que pueda hacerlo. Marcado como añadido del plan ([D-015](./research.md#d-015-purga-de-sesiones-muertas-añadido-del-plan))
- [X] T107 [P] Crear `auth/src/test/kotlin/com/granatum/core/PurgaSesionesIT.kt`: una sesión muerta hace 31 días se purga; una muerta hace 29 **sobrevive**; una sesión **viva** no se toca por antigua que sea
- [X] T108 [P] Crear `auth/src/test/kotlin/com/granatum/core/CuentasHuerfanasIT.kt` con un doble de `DirectorioEmpleados`: una cuenta cuya persona no existe aparece en el listado y una normal no

### Invariantes transversales

- [X] T109 🔍 Crear `app/src/test/kotlin/com/granatum/core/SinColisionDeClasesIT.kt`: para cada clase del paquete de `auth`, pedir `ClassLoader.getResources("<ruta>.class")` y exigir **exactamente una** URL. KDoc: los módulos comparten el namespace `com.granatum.core` por decisión de la constitución y cada uno va en su propio jar, así que dos clases con el mismo nombre cualificado significan que solo se carga una — `auth` compila contra la suya y en ejecución se resuelve la otra. **Ningún test de módulo lo detecta**, porque en el classpath de `auth` solo está su propia clase; es la misma forma de fallo que la caída de Jackson 3 ([D-019](./research.md#d-019-colisión-de-nombres-de-excepción-entre-módulos))
- [X] T110 Crear `auth/src/test/kotlin/com/granatum/core/SinDatosPersonalesEnLogsIT.kt` siguiendo el de `timetracking`: captura los logs con un `ListAppender`, **sube el logger raíz a DEBUG a propósito** —el estado exacto de una sesión de diagnóstico en producción— y recorre un ciclo completo de alta, login, cambio y restablecimiento comprobando que ni el correo, ni la contraseña, ni ningún token aparecen. También que `CuentaAccesoEntity.toString()` omite correo y hash (SC-009). En la feature 001 este test descubrió que `EntityPrinter` filtraba el documento de identidad **esquivando** el `toString()` de la entidad
- [X] T111 Crear `app/src/test/kotlin/com/granatum/core/ComodinSecurityConfigIT.kt`: un token con `pwd_change` recibe `403` en una ruta arbitraria no declarada, demostrando que el comodín endurecido de T096 deniega por defecto. Solo puede vivir en `app`: es el único módulo con la cadena de filtros global
- [X] T112 Crear `app/src/test/kotlin/com/granatum/core/LoginYFichajeIT.kt` — **SC-001**, el criterio que da sentido a la feature: dar de alta persona y acceso, cambiar la temporal, iniciar sesión y completar una jornada de fichaje **sin tocar `/api/dev/token`**, comprobando que el fichaje se atribuye a esa persona sin que el cliente enviara ningún identificador. Solo puede vivir en `app`: es el único módulo que tiene `auth` y `timetracking` a la vez
- [X] T113 Crear `app/src/test/kotlin/com/granatum/core/PendienteDeCambioBloqueaFichajeIT.kt` — **SC-008**: un token pendiente de cambio recibe `403` en `POST /api/fichajes/entrada` y `200` en `change-password`. Solo en `app`, por el mismo motivo que T112: en `auth` no existe la cadena de filtros y el test pasaría sin probar la autorización ([D-016](./research.md#d-016-dónde-vive-cada-test))
- [X] T114 Actualizar `app/src/test/kotlin/com/granatum/core/EsquemaCompletoRlsIT.kt` para que cubra las tres tablas nuevas: es el único test que ve las 14 migraciones juntas, porque el classpath de cada módulo solo contiene las suyas
- [X] T115 [P] Ampliar `app/src/test/kotlin/com/granatum/core/ContratoHttpIT.kt` con un `POST /api/auth/login` sobre JSON real, para que la caída que ese test existe para detectar quede cubierta también en las rutas nuevas

### Documentación y cierre

- [X] T116 [P] Actualizar `README.md`: marcar el login real como **implementado** (hoy la línea 24 dice "pendiente"), documentar las siete variables `AUTH_*`, y **avisar de recalibrar los parámetros de Argon2 en el hardware de destino** con el procedimiento de medición de `research.md`
- [X] T117 [P] Actualizar `docs/ARCHITECTURE.md` con el módulo `auth` y, sobre todo, con el **patrón nuevo**: `domain/contract/` en `common` como vía para que dos features colaboren sin depender la una de la otra. Lo exige el principio IX porque es una decisión estructural del producto, no de esta feature
- [X] T118 [P] Registrar en `README.md` la **deuda declarada** de esta feature: `eventos_seguridad` no tiene plazo de conservación. No es el registro de jornada, así que el principio III no le aplica, pero el art. 5.1.e del RGPD sí: una tabla de auditoría que crece para siempre es el mismo incumplimiento por el otro lado. Fijar el plazo es decisión del responsable del producto, no del plan. Registrar también la **excepción al principio VIII** que introduce esta feature: `PASSWORD_DEBIL` añade un tercer campo `requisitos` al formato `{code, message}` que la constitución fija como único. Lo exige FR-023 —hay que decir qué requisito falla, y un cliente que marque campos necesita identificadores, no una frase—, pero es una desviación de un MUST y tiene que quedar escrita, no pasar en silencio
- [X] T119 Recorrer `quickstart.md` **contra la aplicación en marcha**, apartado por apartado, y corregir cualquier instrucción que falle. No es una formalidad: en la feature 001 así se encontró una caída total que la suite entera no veía, y el propio quickstart traía fechas fijas que incumplían la tolerancia de reloj
- [X] T120 `./gradlew build` en verde de punta a punta, y confirmar que `DevAuthControllerProfileTest` sigue pasando: esta feature **no retira** `/api/dev/token` (FR-030, FR-031 solo piden que deje de ser necesario)

**Checkpoint**: feature completa, invariantes con test, documentación al día.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias.
- **Foundational (Phase 2)**: depende de Setup. **Bloquea todas las historias.**
- **US1 (Phase 3)**: depende de Phase 2.
- **US2 (Phase 4)**: depende de Phase 2. Su test independiente necesita un token
  de renovación, que **solo emite US1**, así que en la práctica va después.
- **US3 (Phase 5)**: depende de Phase 2 y de que exista `login` (US1).
- **US4 (Phase 6)**: depende de Phase 2. Independiente de US3.
- **US5 (Phase 7)**: depende de Phase 2 y de US4 (comparte el generador y el
  flujo de pendiente de cambio).
- **Polish (Phase 8)**: depende de las historias que se quieran cerrar.

### Dependencias concretas que no se pueden saltar

- T010–T012 (migraciones) antes de cualquier entidad: `ddl-auto: validate`
  revienta el contexto si el esquema no existe.
- T014 (contrato) antes de T016 (implementación) y antes de T057 (uso).
- **T015 (el método de `EmpleadoRepository`) antes de T016**, que lo usa.
  Estuvieron al revés hasta que `/speckit-analyze` lo detectó.
- T018–T021 (`common`) antes de T087 y T113: sin el claim y la autoridad no hay
  nada que probar.
- **T021 (`estadoToken` y la marca del filtro) antes de T054 y T062**: el punto
  de entrada no puede distinguir caducado de inválido si el filtro no se lo dice.
- T032–T033 (encoder y señuelo) antes de T034 y de T052: medir indistinguibilidad
  sin señuelo mide el agujero, no la defensa.
- **T039 (`PoliticaPassword`) antes de T041** (test del generador): la política es
  su único juez. Ya están en ese orden —estuvieron al revés hasta que
  `/speckit-analyze` lo detectó— e invertirlo volvería a romperlo.
- T042 (`PoliticaBloqueo`) antes de T080.
- T096 (comodín endurecido) antes de T111 y T113.
- T109 **no puede ir antes de T046**: necesita las clases para comprobarlas.

### Within Each User Story

Tests primero y en rojo → entidades/DTO → servicios → endpoints → reglas de
`SecurityConfig` → eventos de seguridad.

### Parallel Opportunities

- Phase 1: T005, T006, T008 en paralelo.
- Phase 2: los tres ficheros de migración; T013 con T023/T025/T026; los pares
  política+test (T039/T040, T042/T043) son dos ficheros cada uno.
- Todos los tests marcados `[P]` dentro de una historia.
- Phase 8: T103–T108 y T116–T118 en paralelo; T109–T115 tocan ficheros distintos
  pero **conviene secuenciarlos** porque varios comparten el arranque del
  contexto de `app`.

---

## Parallel Example: Phase 2

```bash
# Las tres migraciones son ficheros independientes:
Task: "V12__create_cuentas_acceso_table.sql"
Task: "V13__create_sesiones_renovacion_table.sql"
Task: "V14__create_eventos_seguridad_table.sql"

# El dominio puro no depende de nada del esquema:
Task: "PoliticaPassword.kt + PoliticaPasswordTest.kt"
Task: "PoliticaBloqueo.kt + PoliticaBloqueoTest.kt"
Task: "GeneradorPasswordTemporal.kt"   # su test necesita PoliticaPassword
```

---

## Implementation Strategy

### MVP (US1 + US2)

1. Phase 1 → Phase 2 → Phase 3 (US1) → Phase 4 (US2).
2. **Parar y validar**: apartados 1 a 4 de `quickstart.md`.
3. En ese punto `/api/dev/token` ya **no es necesario** para ejercitar la
   aplicación, que es lo que piden FR-030 y FR-031.

US1 y US2 van juntas en el MVP porque la spec marca las dos P1 y porque un token
de acceso de 15 minutos sin renovación obligaría a escribir la contraseña cada
pocos minutos — lo que en la práctica empuja a alargar su vida, que es justo lo
que se quiere evitar.

### Entrega incremental

| Incremento | Fases | Qué añade |
|------------|-------|-----------|
| 1 (MVP) | 1–4 | Entrar, renovar, salir; fichajes atribuibles de verdad |
| 2 | 5 | La fuerza bruta sale caro — **no debe llegar a producción sin esto** |
| 3 | 6 | Se puede incorporar personal |
| 4 | 7 | Camino de vuelta a una contraseña olvidada |
| 5 | 8 | Huérfanas, purga, invariantes y documentación |

### Qué NO está en estas tareas

- **Retirar `/api/dev/token`**: decisión aparte; `DevAuthControllerProfileTest`
  sigue vigilándolo (T120).
- **Plazo de conservación de `eventos_seguridad`**: deuda declarada (T118).
- **Contraseñas contra listas de filtradas**, cierre de sesión global, límite por
  dirección de origen y MFA: fuera de alcance por decisión de la spec.
- **Revocar la cadena de tokens al detectar reutilización**: T069 lo implementa
  explícitamente **como no-acción**, con su motivo.

---

## Notes

- `[P]` = ficheros distintos, sin dependencias pendientes.
- ➕ = añadido del plan que la spec no pide (T035, T078, T081, T094, T106); se pueden
  retirar sin desmontar nada.
- 🔍 = cierra uno de los dos huecos que encontró el plan (T035, T046, T084,
  T109).
- **Seis tareas vienen de `/speckit-analyze`, no del plan**: T021, T034, T054 y
  T062 cubren los dos huecos que el análisis encontró —FR-006 no tenía ninguna
  tarea, y el invariante de hash salado del principio VI no tenía test—, y los
  pares T015/T016 y T039/T041 corrigen dos inversiones de orden. Queda anotado
  porque el valor de la fase de análisis solo se ve si se sabe qué encontró.
- Marcar cada tarea `[X]` al terminarla.
- Comprobar que cada test falla antes de implementar lo que prueba. Un test que
  pasa desde el principio no prueba lo que crees.
- **Un test no se reescribe para que pase** si el fallo revela un comportamiento
  incorrecto del código (principio V).
- Nada se fusiona con la CI en rojo.
