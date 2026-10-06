# Implementation Plan: Inicio de sesión real (auth)

**Branch**: `auth-feature` | **Date**: 2026-10-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-auth/spec.md`

## Summary

Sustituir `POST /api/dev/token` —que hoy emite un JWT con cualquier rol y sin
credencial alguna— por un inicio de sesión real, de modo que **cada fichaje sea
atribuible de forma fiable a una persona**. Hasta que esto exista, el registro
de jornada que la feature 001 implementó con tanto cuidado no sostiene su propio
valor probatorio.

**Enfoque técnico**: un módulo Gradle `auth` nuevo que depende únicamente de
`common`, con tres tablas propias (V12–V14), contraseñas con **Argon2id** de
parámetros medidos, tokens de renovación **opacos** guardados como SHA-256 y
rotados de un solo uso mediante `UPDATE` condicional, y bloqueo creciente como
máquina de estados pura. La dependencia sobre la persona empleada —que vive en
`timetracking`— se resuelve con un **contrato explícito en `common`**, que es la
vía que el principio I autoriza.

El plan toma cuatro decisiones que la spec no pide y que marca como tales para
que puedan retirarse: [respuesta uniforme también al bloquear](#decisiones-que-añade-el-plan),
[revocar las demás sesiones al cambiar la contraseña](#decisiones-que-añade-el-plan),
[purgar sesiones muertas](#decisiones-que-añade-el-plan) y
[acotar las verificaciones simultáneas](#decisiones-que-añade-el-plan). Y
encontró dos problemas en el terreno que la spec no podía ver: una
**colisión de nombres de clase entre módulos** que habría devuelto el código
HTTP equivocado en producción con la suite en verde, y una **amplificación de
memoria** que introduce la propia elección de Argon2.

`/speckit-analyze` encontró después dos huecos más, ya corregidos en
`tasks.md`: **FR-006 no tenía ninguna tarea** —un token de acceso caducado
respondía un `401` sin cuerpo, indistinguible de un token falso, así que la
aplicación móvil no podía saber si renovar o pedir la contraseña— y **el
invariante de hash salado del principio VI no tenía test**, de modo que cambiar
el encoder por uno rápido no habría roto nada.

## Technical Context

**Language/Version**: Kotlin 2.2 sobre JVM 21

**Primary Dependencies**: Spring Boot 4.0.0-SNAPSHOT (Web, Security, Data JPA,
Validation), `spring-security-crypto` 7.0.0, **`org.bouncycastle:bcprov-jdk18on`
(nueva)**, jjwt 0.12.6, Flyway, Jackson 3 (`tools.jackson`) en el borde HTTP

**Storage**: PostgreSQL. Tres tablas nuevas, migraciones **V12–V14** en la
numeración global compartida (`inventory` V1–V5, `timetracking` V6–V11)

**Testing**: JUnit 5 + MockK (dominio) + Testcontainers con Postgres real
(integración) + `RestClient` sobre puerto real (contrato HTTP)

**Target Platform**: servidor Linux; Postgres local por Docker Compose o Supabase

**Project Type**: servicio web multi-módulo

**Performance Goals**: inicio de sesión < 2 s extremo a extremo (SC-011),
del que ~110 ms son coste deliberado de Argon2id en el hardware medido

**Constraints**:
- Correo inexistente y contraseña incorrecta indistinguibles en código, cuerpo y
  tiempo (FR-003, SC-002)
- Un token de renovación sirve **una sola vez**, al 100% y bajo concurrencia
  (FR-008, SC-004)
- Un fallo durante un bloqueo activo **no** lo prolonga (FR-016c): prolongarlo
  sería una denegación de servicio gratuita, y en este producto equivale a
  impedir fichar
- `auth` **no** puede depender de `timetracking` (principio I), aunque necesite
  saber si una persona existe y está activa
- Secretos solo por variable de entorno (principio VI); ningún dato personal en
  logs

**Scale/Scope**: plantilla pequeña (decenas de personas), varias sesiones por
persona, 7 endpoints, 3 tablas

## Constitution Check

*GATE: comprobado antes de la Fase 0 y revisado tras la Fase 1.*

| Principio | Cómo lo cumple este plan | Estado tras el diseño |
|-----------|--------------------------|-----------------------|
| **I. Features independientes** | Módulo `auth` nuevo, `implementation(projects.common)` y nada más. La necesidad de consultar a la persona empleada se cubre con el contrato `DirectorioEmpleados` en `common`, implementado por `timetracking` ([D-009](./research.md#d-009-directorioempleados-un-contrato-en-common)). El compilador lo sostiene: `auth` no menciona `timetracking`. | ✅ |
| **II. Esquema versionado con Flyway** | V12–V14, nunca se editan. `ddl-auto: validate` también en los tests. Las migraciones corren en Testcontainers contra Postgres real. | ✅ |
| **III. Inmutabilidad del registro horario** | No aplica al registro de jornada: `auth` no lo toca. Se respeta su **forma** donde procede: `eventos_seguridad` es append-only con repositorio estrecho, y la purga de sesiones solo la ejecuta un proceso automático ([D-014](./research.md#d-014-eventos-de-seguridad-append-only-sin-correo-y-sin-dirección-de-origen), [D-015](./research.md#d-015-purga-de-sesiones-muertas-añadido-del-plan)). | ✅ |
| **IV. Autorización por roles** | Reutiliza los cuatro roles de `common` sin añadir ninguno. Rutas declaradas **solo** en `SecurityConfig`. La cuenta sale del sujeto del JWT, nunca del cuerpo. El comodín se endurece a exigir un rol real ([D-010](./research.md#d-010-pendiente-de-cambio-viaja-en-el-token-y-restringe-la-autoridad)). | ✅ |
| **V. Tests por feature y CI en verde** | Unitarios con MockK de `PoliticaPassword`, `PoliticaBloqueo` y el generador; integración con Testcontainers; tests HTTP con JSON real. Cada invariante con su test, incluidos los de los principios IV, VI y VII ([D-016](./research.md#d-016-dónde-vive-cada-test)). | ✅ |
| **VI. Seguridad y secretos** | Argon2id vía `DelegatingPasswordEncoder` — el principio lo nombra expresamente. Ningún secreto nuevo con valor por defecto. `/api/dev/token` intacto con `@Profile("dev")`. Correo y contraseña fuera de logs, con los *loggers* de Hibernate fijados también en la configuración de prueba de `auth`. | ✅ |
| **VII. RLS en todas las tablas** | Las tres tablas activan RLS **en la misma migración que las crea**. Nunca `FORCE`. `RowLevelSecurityIT` propio del módulo + `EsquemaCompletoRlsIT` en `app`. Importa más aquí que en ninguna otra tabla: `cuentas_acceso` guarda correos y hashes de contraseña. | ✅ |
| **VIII. Contrato de la API REST** | Todo bajo `/api`. Error único `{code, message}` desde `AuthExceptionHandler` con `@Order(HIGHEST_PRECEDENCE)`, y **también en el rechazo de un token de acceso**, que hasta ahora devolvía un `401` sin cuerpo. DTO separados de entidades. `Controller → Service → Repository`. `@Valid` en el borde. | ⚠️ con una desviación declarada (`requisitos`, nota 4) |
| **IX. Documentación** | `specs/002-auth/` completa. `README.md` y `docs/ARCHITECTURE.md` se actualizan: módulo nuevo y **patrón nuevo** (contrato entre features vía `common`). | ✅ pendiente de ejecución |

**Ninguna violación que justificar**, así que la sección de *Complexity
Tracking* queda vacía. Tres puntos merecen quedar por escrito porque un revisor
podría leerlos como incumplimientos:

1. **SHA-256 para el token de renovación** parece chocar con la prohibición de
   hashes rápidos del principio VI. No choca: esa prohibición es para
   contraseñas, que tienen poca entropía. Un token de 256 bits aleatorios no
   tiene nada que adivinar ([D-004](./research.md#d-004-el-token-de-renovación-es-opaco-no-un-jwt)).
2. **Se modifica `common`** (`JwtService`, `JwtAuthFilter`, un contrato nuevo).
   `common` es infraestructura compartida, no una feature: el principio I
   gobierna las dependencias *entre features*, y manda subir a `common`
   justamente lo que se comparte. Los cambios son aditivos y los tokens sin el
   claim nuevo se comportan exactamente como hoy.
3. **Se añade BouncyCastle**. Es una librería nueva, no una sustitución de
   ninguna pieza del stack, así que no requiere enmienda; y la necesidad está
   demostrada con una medición, que es lo que exige la regla de simplicidad
   deliberada ([D-001](./research.md#d-001-argon2id-en-lugar-de-bcrypt)).
4. **`PASSWORD_DEBIL` lleva un tercer campo `requisitos`**, y el principio VIII
   fija el formato de error en exactamente `{code, message}`. Es una
   **desviación declarada**, no una reinterpretación: FR-023 obliga a explicar
   qué requisito falla, y un cliente que quiera marcar los campos del formulario
   necesita identificadores, no una frase. Queda registrada aquí, en el contrato
   y en el `README.md` (T118) para que no pase en silencio, que es lo que la
   constitución pide de un incumplimiento aceptado.

## Project Structure

### Documentation (this feature)

```text
specs/002-auth/
├── plan.md              # Este fichero
├── research.md          # Fase 0: 19 decisiones con su por qué
├── data-model.md        # Fase 1: 3 tablas, transiciones, dominio
├── quickstart.md        # Fase 1: validación extremo a extremo
├── contracts/
│   └── README.md        # Fase 1: 7 rutas, 11 códigos de error
├── checklists/
│   └── requirements.md  # 16/16 (de /speckit-specify)
└── tasks.md             # Fase 2 (/speckit-tasks) - NO lo crea este comando
```

### Source Code (repository root)

```text
auth/                                   # MÓDULO NUEVO
├── build.gradle.kts                    # implementation(projects.common) y nada más
└── src/
    ├── main/
    │   ├── kotlin/com/granatum/core/
    │   │   ├── api/
    │   │   │   ├── controllers/        # AuthController, CuentaAccesoController
    │   │   │   ├── dto/                # Login/Refresh/CambioPassword/AltaCuenta
    │   │   │   ├── mappers/
    │   │   │   └── exception_handling/ # AuthExceptionHandler @Order(HIGHEST_PRECEDENCE)
    │   │   ├── domain/
    │   │   │   ├── model/              # CuentaAcceso, EstadoBloqueo, ParTokens
    │   │   │   ├── exception/          # AuthExceptions.kt (nombres únicos, D-019)
    │   │   │   ├── service/            # PoliticaPassword, PoliticaBloqueo (puros)
    │   │   │   └── type/               # TipoEventoSeguridad, RequisitoIncumplido
    │   │   ├── infrastructure/
    │   │   │   ├── crypto/             # PasswordEncoderConfig, GeneradorPassword,
    │   │   │   │                       #   VerificadorAcotado (semáforo, D-003)
    │   │   │   └── database/
    │   │   │       ├── entities/
    │   │   │       ├── mappers/
    │   │   │       └── repositories/   # CuentaAccesoRepository, SesionRenovacionRepository,
    │   │   │                           #   EventoSeguridadRepository (Repository<T,ID> estrecho)
    │   │   ├── scheduling/             # PurgaSesionesJob, DeteccionHuerfanasJob
    │   │   └── service/                # AutenticacionService, SesionService,
    │   │                               #   CuentaAccesoService, RegistradorEventosSeguridad
    │   └── resources/db/migration/
    │       ├── V12__create_cuentas_acceso_table.sql
    │       ├── V13__create_sesiones_renovacion_table.sql
    │       └── V14__create_eventos_seguridad_table.sql
    └── test/
        ├── kotlin/com/granatum/core/   # AuthTestApplication + unitarios + IT
        └── resources/application.yml   # jwt.* y loggers de Hibernate fijados

common/src/main/kotlin/com/granatum/core/          # CAMBIOS ADITIVOS
├── domain/contract/DirectorioEmpleados.kt         # NUEVO (D-009)
├── domain/type/EstadoEmpleado.kt                  # NUEVO
├── service/JwtService.kt                          # claim pwd_change, vida configurable
└── api/config/JwtAuthFilter.kt                    # autoridad PWD_CHANGE_ONLY (D-010)

timetracking/src/main/kotlin/com/granatum/core/
└── infrastructure/directorio/DirectorioEmpleadosJpa.kt   # NUEVO: implementa el contrato

app/
├── build.gradle.kts                               # implementation(projects.auth)
├── src/main/kotlin/.../api/security/SecurityConfig.kt     # rutas de auth + comodín con rol
├── src/main/kotlin/.../api/security/EntryPointJson.kt     # NUEVO: 401 con {code, message} (FR-006)
├── src/main/resources/application.yml             # auth.* (sin defaults para secretos)
└── src/test/kotlin/com/granatum/core/             # tests que solo pueden vivir aquí (D-016)

settings.gradle.kts                                # include("auth")
gradle/libs.versions.toml                          # bouncycastle
```

**Structure Decision**: módulo Gradle propio, replicando el layout que la
constitución fija y que `timetracking` ya sigue. Dos carpetas se salen de la
plantilla y conviene justificarlas:

- **`infrastructure/crypto/`**: el hash es infraestructura, no dominio. Dejar
  `PasswordEncoder` fuera del dominio es lo que permite que `PoliticaPassword` y
  `PoliticaBloqueo` sean objetos puros con tests unitarios sin Spring, como pide
  el principio V.
- **`domain/contract/` en `common`**: estructura nueva en el producto, no solo en
  esta feature — primer contrato explícito entre features. Por eso
  `docs/ARCHITECTURE.md` **debe** recogerlo (principio IX).

## Fases de entrega

Ordenadas por las prioridades de la spec, cada una dejando el build en verde.

| Fase | Contenido | Historias | Entregable comprobable |
|------|-----------|-----------|------------------------|
| **1. Setup** | Módulo `auth`, `settings.gradle.kts`, BouncyCastle, `AuthTestApplication`, `application.yml` de prueba | — | `./gradlew :auth:build` en verde con el módulo vacío |
| **2. Fundación** | V12–V14 con RLS, entidades, repositorios estrechos, `DirectorioEmpleados` + implementación, `PasswordEncoderConfig`, `PoliticaPassword`, `PoliticaBloqueo`, `GeneradorPassword`, `RegistradorEventosSeguridad`, `AuthExceptionHandler` | — | `RowLevelSecurityIT` y los unitarios de las dos políticas en verde |
| **3. US1 + US2** | `login`, `refresh`, `logout`; claim `pwd_change` y autoridad restringida; rutas en `SecurityConfig` | P1 | **SC-001**: entrar y completar una jornada de fichaje sin `/api/dev/token` |
| **4. US3** | Bloqueo creciente, respuesta uniforme, señuelo, semáforo | P2 | **SC-005**, **SC-012** |
| **5. US4** | Alta con contraseña temporal, cambio obligatorio, cumplimiento de la política | P2 | **SC-008**, **SC-013**, **SC-014** |
| **6. US5** | Restablecimiento: revoca todo y levanta el bloqueo | P3 | **SC-007** |
| **7. Pulido** | Huérfanas (ruta + trabajo nocturno), purga de sesiones, test de colisión de clases, test de ausencia de datos personales en logs, `README.md`, `docs/ARCHITECTURE.md` | — | **SC-009**, suite completa en verde |

US1 y US2 van juntas porque la spec las marca las dos P1 y porque US2 no se
puede probar sin un token de renovación que solo emite US1.

## Decisiones que añade el plan

Cuatro, todas señaladas para que el responsable del producto pueda retirarlas.
Van aquí y no escondidas en `research.md` porque **cambian comportamiento
observable que la spec no describe**.

1. **El rechazo por cuenta bloqueada es idéntico al de credenciales inválidas**
   ([D-005](./research.md#d-005-rechazo-uniforme-con-hash-siempre-también-para-cuentas-bloqueadas)).
   La spec solo prohíbe revelar si la contraseña era correcta. Un cuerpo
   específico de "bloqueada" revelaría que el correo existe y reabriría el
   oráculo que FR-003 cierra. **Coste**: quien se equivoca cinco veces no ve
   "vuelve a intentarlo en 1 minuto" y no sabe cuánto esperar. Es una pérdida de
   usabilidad en el peor momento.
2. **Cambiar la contraseña revoca las demás sesiones** de la cuenta. La spec solo
   lo exige al restablecer (FR-025). **Coste**: cambiarla en el móvil cierra la
   sesión del navegador.
3. **Purga de sesiones muertas a los 30 días**
   ([D-015](./research.md#d-015-purga-de-sesiones-muertas-añadido-del-plan)). Sin
   ella la tabla crece sin tope: ~35.000 filas al año **por persona**.
4. **Semáforo sobre las verificaciones simultáneas**
   ([D-003](./research.md#d-003-límite-de-verificaciones-simultáneas-consecuencia-de-d-002)).
   No es un extra: lo introduce la elección de Argon2 y sin él el plan abriría un
   agujero nuevo. **Coste**: bajo carga extrema, `503` en lugar de una espera
   indefinida.

## Huecos que el plan encontró

Tres problemas reales, ninguno visible desde la spec. El tercero no apareció hasta escribir el código.

### 1. Colisión de nombres de clase entre módulos (verificado)

`timetracking` ya declara `EmpleadoInactivoException` y
`EmpleadoNotFoundException` en `com.granatum.core.domain.exception`, el mismo
paquete que usaría `auth` — todos los módulos comparten el namespace por
decisión de la constitución, y cada uno se empaqueta en su propio jar.

`auth` necesita las dos ideas con **semántica HTTP distinta**: la baja laboral
es `401` al renovar, no `400`. Con el mismo nombre cualificado solo se carga una
clase: `auth` compila contra la suya y el build pasa, pero en ejecución se
resuelve la de `timetracking` y el error sale con el código equivocado — o
estalla en `NoSuchMethodError`. **Ningún test de módulo lo detecta**, porque en
el classpath de `auth` solo está su propia clase. Es la misma forma de fallo que
la caída de Jackson 3 en la feature 001: suite en verde, aplicación rota.

Se cierra con nombres únicos **y** con un test en `app` que, para cada clase de
`auth`, comprueba que el classpath la declara una sola vez — así cubre también
las colisiones futuras ([D-019](./research.md#d-019-colisión-de-nombres-de-excepción-entre-módulos)).

### 3. El rol no estaba almacenado en ninguna parte (lo encontró la implementación)

FR-005 exige que el token de acceso lleve el rol, y `timetracking` lo lee del
token para autorizar de verdad —aprobar correcciones—. Pero **ninguna tabla
tenía columna de rol**: ni `cuentas_acceso` según este plan, ni `empleados` en
la feature 001. Hoy lo aporta `/api/dev/token` como parámetro de la petición,
que es exactamente por lo que ese endpoint es una suplantación total de `ADMIN`.

No lo vio la spec, no lo vio este plan y no lo vio `/speckit-analyze`: los tres
daban por hecho que el rol venía de algún sitio. Apareció al implementar US1,
cuando hubo que emitir un token y no había rol que poner.

Se resuelve con una columna `rol` en `cuentas_acceso`, no en `empleados`, porque
es una propiedad de la credencial y no del empleo: `puesto` dice qué hace
alguien, `rol` dice qué puede leer y escribir. `REPRESENTANTE` lo hace evidente.
Y mantiene limpio el principio I: no hay que ensanchar `DirectorioEmpleados`.
La operación de dar acceso gana un campo `rol` opcional cuyo defecto es
`EMPLEADO`, que es mínimo privilegio.

### 2. Amplificación de memoria en un endpoint público

Es consecuencia de elegir Argon2 con 64 MiB. `POST /api/auth/login` es público y
Tomcat admite 200 hilos por defecto: **12,8 GiB** de reserva que cualquiera puede
provocar sin credencial. La defensa natural —límite por dirección de origen— está
**fuera de alcance por decisión de la spec**. Se resuelve dentro del alcance con
el semáforo de D-003, que fija el techo en 256 MiB.

## Lo que este plan NO hace

- **No retira `POST /api/dev/token`.** FR-030 y FR-031 solo piden que deje de ser
  necesario y que siga siendo imposible en `prod`; retirarlo es decisión aparte.
  `DevAuthControllerProfileTest` sigue vigilándolo.
- **No toca `inventory` ni la lógica de `timetracking`.** De `timetracking` solo
  se **añade** la implementación del contrato; ninguna clase existente cambia.
- **No fija plazo de conservación para `eventos_seguridad`**: se declara como
  deuda ([D-014](./research.md#d-014-eventos-de-seguridad-append-only-sin-correo-y-sin-dirección-de-origen)).
  Es una decisión del responsable, no del plan.
- **No comprueba contraseñas contra listas de filtradas.** Fuera de alcance, y es
  la ampliación que de verdad compensaría la política de 8 caracteres
  ([D-012](./research.md#d-012-política-de-contraseña-como-función-pura-con-máximo-en-128)).
- **No revoca la cadena de tokens al detectar reutilización**
  ([D-007](./research.md#d-007-reutilizar-un-token-ya-usado-no-revoca-la-cadena)).
- **No implementa cierre de sesión global**, ni límite por origen, ni MFA, ni
  recuperación por correo: la spec los deja fuera.

## Complexity Tracking

Sin violaciones de la constitución que justificar. Las tres lecturas que podrían
confundirse con incumplimientos están explicadas en
[Constitution Check](#constitution-check).
