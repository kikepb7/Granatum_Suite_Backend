# Granatum Suite Backend

Backend de gestión para Granatum: inventario de material de trabajo (flores/eventos),
registro de jornada del personal conforme al RD-ley 8/2019 con su exportación,
acceso con correo y contraseña, y facturación con reconocimiento automático de
facturas.

Extraído a partir de [Spring_Boot_Skeleton](https://github.com/kikepb7/Spring_Boot_Skeleton),
adaptando el kernel compartido (JWT, manejo de errores) al dominio de Granatum y
sustituyendo la feature de ejemplo por el módulo `inventory` real. Ver
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) para el detalle.

## Stack

- Kotlin 2.2 / JVM 21
- Spring Boot 4 (Web, Security, Data JPA, Validation, Actuator)
- Gradle multi-módulo con convention plugins propios (`build-logic/`)
- PostgreSQL + Flyway
- JWT (jjwt) con roles `ADMIN` / `ENCARGADO` / `EMPLEADO` / `REPRESENTANTE`
- Argon2 (Spring Security) para las contraseñas
- SDK de Anthropic para Java (lectura de facturas) y PDFBox (PDF)
- JUnit 5 + MockK; Testcontainers para tests de integración con Postgres real

## Estado del proyecto

- ✅ `inventory` (Material, Categoria, HistorialMaterial) — implementado y con migraciones Flyway.
- ✅ `timetracking` (Empleado, Fichaje, Pausa, SolicitudCorreccionFichaje) — implementado. Registro de jornada conforme al RD-ley 8/2019: entrada, pausas, salida, correcciones con aprobación, consulta por rango, resumen mensual, modo sin conexión idempotente y depuración a los 4 años. Especificado en [`specs/001-timetracking/`](specs/001-timetracking/).
- ✅ `auth` (CuentaAcceso, SesionRenovacion, EventoSeguridad) — implementado. Inicio de sesión real con correo y contraseña, renovación de un solo uso, cierre de sesión, bloqueo creciente por fuerza bruta, alta con contraseña temporal y cambio obligatorio, y restablecimiento por un `ADMIN`. Especificado en [`specs/002-auth/`](specs/002-auth/). `POST /api/dev/token` sigue existiendo en el perfil `dev`, pero ya no es necesario para usar la aplicación.
- ✅ Registro del personal — implementado. Cada persona se registra con su correo, la contraseña que elige, su nombre y su DNI/NIE; un `ADMIN` aprueba la solicitud con el código de verificación que la persona le dice en persona, elige el rol y la vincula a su ficha (o la crea). El primer `ADMIN` entra con un código de arranque fijado al desplegar. Especificado en [`specs/005-staff-registration/`](specs/005-staff-registration/) (ver [El primer administrador](#el-primer-administrador) y [Registro](#registro-del-personal)).
- ✅ Exportación del registro de jornada — implementada. Cada persona descarga su registro, la representación legal y quien gestiona la plantilla el de todos, y para cada persona la descarga mensual con su total. CSV para hoja de cálculo española, una huella SHA-256 por fichero y un registro de quién exportó qué. Especificada en [`specs/003-timetracking-export/`](specs/003-timetracking-export/). Con ella, la depuración a los 4 años queda **desbloqueada pero desactivada** (ver [Exportación](#exportación-del-registro-de-jornada)).
- ✅ Endurecimiento y despliegue — implementado. Límite de peticiones por dirección de origen en inicio de sesión, renovación, cierre de sesión, registro y en toda la API; perfil `prod` por defecto; errores sin trazas; cabeceras de seguridad; CORS explícito; imagen de contenedor sin privilegios y CI en todas las ramas. Especificado en [`specs/006-hardening-deploy/`](specs/006-hardening-deploy/) (ver [Despliegue](#despliegue)).
- ✅ `absences` (Ausencia, DerechoVacaciones) — implementado. Vacaciones y permisos retribuidos que la persona pide y un `ENCARGADO` o `ADMIN` aprueba o rechaza; bajas médicas que registran ellos, sin ningún dato de salud; sin solapamientos por persona; saldo anual en días naturales (30 por defecto, ajustable por el `ADMIN`); nada se borra. Especificado en [`specs/007-absences/`](specs/007-absences/) (ver [Ausencias](#ausencias-y-vacaciones)).
- ✅ `notifications` (Notificacion) — implementado. Avisos dentro de la aplicación: salida de fichaje olvidada, fichaje incompleto, correcciones y ausencias pendientes (a quien las resuelve), sus resoluciones (a quien las pidió) y registros pendientes (a los `ADMIN`). Cada persona ve solo los suyos; sin datos personales; los antiguos se borran solos. Especificado en [`specs/008-notifications/`](specs/008-notifications/) (ver [Notificaciones](#notificaciones)).
- ✅ `invoices` (Factura, desglose de IVA, originales, trimestres) — implementado. Solo el `ADMIN` sube fotos, capturas o PDF de facturas; Claude las lee y propone sus datos; el `ADMIN` las revisa y las confirma; y con las confirmadas salen reportes mensuales, trimestrales y anuales en pantalla, CSV y PDF. Los trimestres se cierran al declararlos y desde entonces no cambian. Sin clave de API funciona en modo manual. Especificado en [`specs/004-invoices/`](specs/004-invoices/) (ver [Facturación](#facturación)).

## Arranque rápido

Requisitos: JDK 21, Docker.

```bash
# 1. Levanta Postgres
docker compose up -d

# 2. Copia las variables de entorno de ejemplo
cp .env.example .env

# 3. Genera tu clave de firma JWT y ponla en .env (JWT_SECRET_BASE64)
openssl rand -base64 32

# 4. Arranca la app (bootRun usa el perfil "dev"; aplica las migraciones Flyway al arrancar)
./gradlew :app:bootRun
```

> **El paso 3 no es opcional.** `application.yml` **no tiene valor por defecto**
> para `JWT_SECRET_BASE64` ni para `DB_PASSWORD`, así que la app se niega a
> arrancar si faltan. Es deliberado: un valor por defecto significa que, si la
> variable no está puesta en producción, la aplicación arranca con una clave
> committeada en este repositorio — y quien conozca esa clave puede emitir un
> token con cualquier rol, lo que equivale a no tener autenticación.
>
> `.env` **sí se carga**: `bootRun` y las tareas de test lo leen y lo pasan como
> variables de entorno de verdad (ver `build-logic/src/main/kotlin/DotEnv.kt`).
> Spring Boot no lee `.env` por su cuenta, y Gradle tampoco. Los tests no
> necesitan que pongas ninguna clave: se genera una nueva en cada ejecución.

La app queda escuchando en `http://localhost:8080`.

### Probar el módulo de inventario

Lo de abajo usa el emisor de desarrollo por brevedad. Con una cuenta real, el
token sale de `POST /api/auth/login` (ver [Autenticación](#autenticación)).

```bash
# Consigue un token de desarrollo con rol ENCARGADO
TOKEN=$(curl -s -X POST "http://localhost:8080/api/dev/token?role=ENCARGADO" | jq -r .accessToken)

# Crea una categoría
CATEGORIA_ID=$(curl -s -X POST http://localhost:8080/api/categorias \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"nombre": "Cilindros", "descripcion": "Cilindros de cristal"}' | jq -r .id)

# Crea un material
curl -X POST http://localhost:8080/api/materiales \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{
    \"nombre\": \"Cilindro alto 40cm\",
    \"categoriaId\": \"$CATEGORIA_ID\",
    \"cantidadDisponible\": 10,
    \"cantidadTotal\": 10,
    \"tamano\": {\"alto\": 40, \"ancho\": 15, \"diametro\": 15, \"unidadMedida\": \"CM\"},
    \"color\": \"Transparente\",
    \"materialFisico\": \"Cristal\",
    \"estado\": \"NUEVO\",
    \"ubicacion\": \"Almacen A\",
    \"precioUnitario\": 12.5,
    \"proveedor\": \"Cristaleria Sur\",
    \"fotos\": []
  }"
```

## Autenticación

El ciclo completo, con comandos que se pueden copiar, está en
[`specs/002-auth/quickstart.md`](specs/002-auth/quickstart.md); el contrato de
cada ruta, en [`specs/002-auth/contracts/README.md`](specs/002-auth/contracts/README.md).
En resumen:

| Ruta | Quién | Qué hace |
|------|-------|----------|
| `POST /api/auth/login` | pública | Correo y contraseña → token de acceso (15 min) y de renovación (30 días) |
| `POST /api/auth/refresh` | pública | Renueva; el token presentado deja de servir en el acto |
| `POST /api/auth/logout` | pública | Cierra **esa** sesión; las de otros dispositivos siguen |
| `POST /api/auth/change-password` | autenticada | Única operación permitida mientras la contraseña es temporal |
| `POST /api/auth/cuentas` | `ADMIN` | Da acceso a una persona ya registrada; devuelve la contraseña temporal **una sola vez** |
| `POST /api/auth/cuentas/{empleadoId}/restablecer` | `ADMIN` | Nueva temporal, cierra todas las sesiones, levanta el bloqueo |
| `GET /api/auth/cuentas/huerfanas` | `ADMIN` | Cuentas cuya persona ya no existe |
| `POST /api/auth/registro` | pública | Registro con correo, contraseña, nombre y DNI/NIE → solicitud pendiente y código de verificación (feature 005) |
| `GET /api/auth/registros` | `ADMIN` | Solicitudes pendientes, de la más antigua a la más reciente |
| `POST /api/auth/registros/{id}/aprobar` | `ADMIN` | Aprueba con el código, el rol y, si no hay ficha con ese DNI, los datos para crearla |
| `POST /api/auth/registros/{id}/rechazar` | `ADMIN` | Rechaza; la solicitud deja de guardar datos personales |

Cinco fallos seguidos bloquean la cuenta 1, 5, 15 y 60 minutos de forma
creciente. Mientras dura el bloqueo, la respuesta es **idéntica** a la de una
contraseña incorrecta: así el inicio de sesión no revela qué correos existen, a
costa de que quien se equivoca no sepa cuánto esperar.

### Variables de entorno

Salvo `JWT_SECRET_BASE64` y `AUTH_CODIGO_ARRANQUE`, ninguna es un secreto, así que
todas las demás tienen valor por defecto.

| Variable | Defecto | Para qué |
|----------|---------|----------|
| `JWT_EXPIRATION_MINUTES` | 15 | Vida del token de acceso (igual que Squadfy_Backend; en `dev` el defecto es 1000) |
| `AUTH_REFRESH_EXPIRATION_DAYS` | 30 | Vida del token de renovación |
| `AUTH_ARGON2_MEMORY_KB` | 65536 | Memoria por verificación de contraseña |
| `AUTH_ARGON2_ITERATIONS` | 3 | Iteraciones de Argon2id |
| `AUTH_ARGON2_PARALLELISM` | 1 | Carriles de Argon2id |
| `AUTH_HASH_CONCURRENCIA` | 4 | Verificaciones simultáneas como máximo |
| `AUTH_HASH_ESPERA_MS` | 1000 | Espera en cola antes de responder `503` |
| `AUTH_PURGA_SESIONES_DIAS` | 30 | Antigüedad para purgar sesiones ya muertas |
| `AUTH_CODIGO_ARRANQUE` | *(vacío)* | **Secreto.** Código para crear el primer `ADMIN`; vacío lo desactiva |
| `AUTH_REGISTRO_MAX_PENDIENTES` | 50 | Solicitudes de registro pendientes a la vez como máximo |
| `AUTH_REGISTRO_CADUCIDAD_DIAS` | 7 | Días tras los que caduca una solicitud sin resolver |

> **Recalibra Argon2 en el hardware de destino antes de desplegar.** Los valores
> por defecto cuestan ~110 ms en un Mac mini de 10 núcleos; en un contenedor
> pequeño pueden costar mucho más. El programa de medición está en
> [`specs/002-auth/research.md`](specs/002-auth/research.md#cómo-se-midió).
> Si subes la memoria, recuerda que es memoria **por login simultáneo**.

> **`DB_POOL_MAX_SIZE` tiene que ser al menos el doble de
> `AUTH_HASH_CONCURRENCIA`.** Una petición de autenticación puede usar dos
> conexiones a la vez (su transacción y la del registro de seguridad), y con un
> pool menor las peticiones simultáneas se bloquean entre sí hasta el timeout.
> La aplicación **se niega a arrancar** si no se cumple, con un mensaje que dice
> cuál de las dos variables tocar.

### El primer administrador

Se crea con el **código de arranque** (feature 005, research.md D-002):

1. Al desplegar, fija `AUTH_CODIGO_ARRANQUE` con un valor largo y aleatorio
   (`openssl rand -base64 24`) y dáselo solo a quien va a ser el primer `ADMIN`.
2. Esa persona se registra con su correo, su contraseña, su nombre, su DNI y el
   código:

   ```bash
   curl -s -X POST https://<host>/api/auth/registro -H 'Content-Type: application/json' \
     -d '{"email":"jefe@granatum.es","password":"...","nombre":"...","documentoIdentidad":"...","codigoArranque":"..."}'
   ```

   Responde `201` y ya puede iniciar sesión como `ADMIN`. Si no había ficha de
   personal con ese DNI, se crea con puesto `Dirección`, jornada completa y alta
   de hoy; se corrige con `PUT /api/empleados/{id}`.
3. **Quita `AUTH_CODIGO_ARRANQUE` del despliegue.** Ya no sirve —el código deja
   de funcionar en cuanto existe cualquier `ADMIN`—, pero no hay por qué tener
   un secreto que no se usa.

Un código incorrecto, uno que no está configurado o uno que llega cuando ya hay
`ADMIN` responden igual, `403 CODIGO_ARRANQUE_INVALIDO`: nadie puede averiguar
así si una instalación ya tiene administrador.

**Aprueba pronto un segundo `ADMIN`.** No hay recuperación por correo, así que si
el único `ADMIN` olvida su contraseña, la única salida pasa por la base de datos
(research.md D-010): registrarse con un correo cualquiera y la contraseña nueva,
copiar el `password_hash` de esa solicitud pendiente a la cuenta del `ADMIN` en
`cuentas_acceso` (con `requiere_cambio_password = false`), y dejar caducar la
solicitud. Así la aplicación genera el hash y nunca viaja una contraseña en claro.

### Registro del personal

El contrato está en
[`specs/005-staff-registration/contracts/README.md`](specs/005-staff-registration/contracts/README.md)
y el recorrido completo en
[`specs/005-staff-registration/quickstart.md`](specs/005-staff-registration/quickstart.md).

1. La persona se registra (`POST /api/auth/registro`) y recibe un **código de
   verificación** de 8 caracteres. Todavía no puede entrar.
2. Se lo dice al `ADMIN` **en persona**. Es lo que demuestra que quien tiene
   delante hizo esa solicitud y no otra con su nombre: no hay correo saliente.
3. El `ADMIN` la ve en `GET /api/auth/registros` y la aprueba con el código y el
   rol. Si ya hay ficha de personal con ese DNI, la cuenta se vincula a ella; si
   no, el `ADMIN` aporta puesto, tipo de contrato y fecha de alta y se crea.
4. La persona entra con la contraseña que eligió, sin tener que cambiarla.

Garantías:

- **No delata correos.** Registrarse con un correo que ya tiene cuenta da la
  misma respuesta, con un código de la misma forma, y en el mismo tiempo (se
  mide en `IndistinguibilidadRegistroIT`). No se guarda nada aprobable.
- **Cinco códigos incorrectos anulan la solicitud.**
- **Nada personal se queda sin motivo.** Al aprobar, rechazar, caducar (7 días)
  o anular, la solicitud pierde correo, nombre, DNI y huellas; la base de datos
  rechaza lo contrario.
- **Volumen acotado.** Como máximo 50 pendientes a la vez; el ritmo por origen
  lo limita la feature de endurecimiento.

El alta por un `ADMIN` con contraseña temporal (`POST /api/auth/cuentas`) sigue
existiendo y funciona igual.

### Deuda declarada de esta feature

- **`eventos_seguridad` no tiene plazo de conservación.** No es el registro de
  jornada, así que el principio III no le aplica, pero el art. 5.1.e del RGPD sí:
  una tabla de auditoría que crece para siempre es el mismo incumplimiento por el
  otro lado. Fijar el plazo es decisión del responsable del producto.
- **Desviación declarada del principio VIII.** El error `PASSWORD_DEBIL` lleva un
  tercer campo, `requisitos`, además de `code` y `message`. Lo exige FR-023 —hay
  que decir qué requisito falla, y un cliente que marque campos necesita
  identificadores, no una frase—, pero el principio fija el formato en esos dos
  campos exactamente, así que queda escrito aquí en lugar de pasar en silencio.

## Exportación del registro de jornada

El ciclo completo está en
[`specs/003-timetracking-export/quickstart.md`](specs/003-timetracking-export/quickstart.md);
el contrato, en
[`specs/003-timetracking-export/contracts/README.md`](specs/003-timetracking-export/contracts/README.md).

| Ruta | Quién | Qué hace |
|------|-------|----------|
| `GET /api/fichajes/export?desde=&hasta=[&empleadoId=]` | los cuatro roles | El registro de un rango. `EMPLEADO`: solo el suyo. El resto: una persona o, sin `empleadoId`, toda la plantilla (incluidas las personas dadas de baja) |
| `GET /api/fichajes/empleado/{id}/resumen/descarga?anio=&mes=` | la propia persona, `ENCARGADO`, `ADMIN`, `REPRESENTANTE` | El mes natural con su total, tipo de contrato y si el mes ha terminado. Es el resumen del art. 12.4.c para contratos a tiempo parcial |
| `GET /api/exportaciones` | `ADMIN` | Quién exportó qué. Filtros: `empleadoId` (incluye las exportaciones de toda la plantilla), `generadaDesde`/`generadaHasta`, `cubreDesde`/`cubreHasta` (por solapamiento) |
| `POST /api/exportaciones/verificar` | `ADMIN` | Envía un fichero como cuerpo (`text/csv`) y dice si salió de aquí y cuándo. No se guarda nada del fichero |

```bash
curl -H "Authorization: Bearer $TOKEN" -OJ \
  "http://localhost:8080/api/fichajes/export?desde=2026-10-01&hasta=2026-10-31"
```

El fichero es CSV en UTF-8 con BOM, separador `;` y fin de línea CRLF, para que se
abra sin asistente en una hoja de cálculo con configuración española. Ninguna celda
se ejecuta como fórmula. La ubicación no sale nunca, y con rol `REPRESENTANTE` la
columna del documento de identidad no existe. Dos exportaciones iguales sin cambios
entre medias dan el mismo fichero byte a byte.

### La depuración a los 4 años: desbloqueada, no activada

La feature 001 dejó la depuración apagada hasta que el registro se pudiera
descargar. Esta feature cumple esa condición, **pero no la activa**: borrar
registros con valor legal es una decisión de cada entorno. Para activarla:

```yaml
timetracking:
  retencion:
    habilitada: true
```

> **Es irreversible.** Lo que la depuración borra no se recupera. Antes de
> activarla en un entorno, asegúrate de que la descarga mensual se ha puesto a
> disposición de cada persona.

La depuración borra también las exportaciones anotadas cuyo periodo cubierto ya
ha salido entero del plazo, y cuenta cuántas en `depuraciones_retencion`.

### Variables de entorno

Ninguna es un secreto.

| Variable | Defecto | Para qué |
|----------|---------|----------|
| `TIMETRACKING_EXPORTACION_CONCURRENCIA` | 2 | Exportaciones simultáneas como máximo; cada una ocupa una conexión mientras se descarga |
| `TIMETRACKING_EXPORTACION_ESPERA_MS` | 1000 | Espera antes de responder `503 EXPORTACION_SATURADA` con `Retry-After` |
| `TIMETRACKING_EXPORTACION_TIMEOUT_SEGUNDOS` | 120 | Tiempo máximo de la lectura de una exportación |
| `TIMETRACKING_EXPORTACION_TAMANO_LOTE` | 500 | Fichajes por lote al recorrer el registro |
| `TIMETRACKING_VERIFICACION_MAX_BYTES` | 104857600 | Tamaño máximo de un fichero a verificar (`413` por encima) |
| `SERVER_TOMCAT_CONNECTION_TIMEOUT` | 10s | Además, lo que puede retener una conexión a la base de datos un cliente que deja de leer una descarga |

### Errores de validación

Todos los `400` de validación de la API, de cualquier módulo, responden ahora
`{"code": "VALIDACION", "message": …}`, sin traza y sin el valor rechazado.
Antes salían con el cuerpo por defecto de Spring, que en `dev` incluía la traza y
el valor enviado (un correo, o una contraseña demasiado larga).

## Facturación

El ciclo completo, con comandos que se pueden copiar, está en
[`specs/004-invoices/quickstart.md`](specs/004-invoices/quickstart.md); el contrato,
en [`specs/004-invoices/contracts/README.md`](specs/004-invoices/contracts/README.md).
**Todas las rutas son solo `ADMIN`**, bajo `/api/facturacion`.

| Ruta | Qué hace |
|------|----------|
| `PUT /api/facturacion/empresa` | Razón social y NIF de la empresa: deciden qué es emitido y qué recibido |
| `POST /api/facturacion/facturas` | Sube una o varias (JPEG, PNG, WebP o PDF, hasta 10 MB cada una); `202` y se reconocen en segundo plano |
| `GET /api/facturacion/facturas[?desde&hasta&parte&tipo&estado]` | Listado con filtros |
| `GET / PUT /api/facturacion/facturas/{id}` | Ver una con sus avisos, o corregirla |
| `POST …/{id}/confirmar`, `…/descartar`, `…/reconocer` | Confirmar (si no tiene avisos bloqueantes), descartar (no borra), reintentar el reconocimiento |
| `GET …/{id}/original`, `…/{id}/historial` | El fichero tal cual se subió; lo que propuso el reconocimiento y cada cambio |
| `GET /api/facturacion/reportes?periodo=MENSUAL\|TRIMESTRAL\|ANUAL&anio=…[&formato=csv\|pdf]` | Totales de emitidas y recibidas, IVA por tipo, retenciones |
| `POST /api/facturacion/trimestres/{anio}/{t}/cerrar`, `…/reabrir` | Cerrar un trimestre declarado; reabrirlo exige un motivo |

### El reconocimiento con Claude

Cada factura se envía **una vez** a la API de Claude (`claude-opus-5-5`), que
devuelve sus datos en un esquema cerrado. Lo que no lee con seguridad lo deja
vacío; nunca se confirma nada sin la revisión del `ADMIN`. El texto de una
factura no puede dar órdenes: la llamada no tiene herramientas y lo peor que
puede pasar es un borrador con campos equivocados, que no se confirma si no
cuadra.

- **Coste**: unos 0,05 $ por factura con Opus 5.5 (unos 10 $ al mes con 200
  facturas). Los tokens reales de cada reconocimiento quedan guardados en
  `factura_reconocimientos`.
- **Sin `ANTHROPIC_API_KEY` funciona en modo manual**: las facturas se guardan y
  se rellenan a mano; no se envía nada a ningún sitio.
- **Protección de datos**: las facturas de autónomos llevan su DNI. Anthropic
  actúa como encargado del tratamiento; **antes de poner la clave en producción,
  la empresa tiene que aceptar sus condiciones de tratamiento de datos**.
- **Medir la precisión** con facturas reales (que no se versionan):

```bash
ANTHROPIC_API_KEY=... INVOICES_REFERENCIA_DIR=/ruta/a/facturas ./gradlew :features:invoices:claudeRealTest
```

> **Supabase**: los originales se guardan en Postgres. El plan gratuito de
> Supabase (500 MB) se llenaría en unos meses de facturas; en producción hace
> falta un plan de pago o pasar los originales a Supabase Storage (la interfaz
> `AlmacenDocumentos` está para eso).

### Variables de entorno

Solo `ANTHROPIC_API_KEY` es un secreto, y nunca va en un fichero versionado.
**Los tests nunca la usan**, aunque esté en tu `.env`: los contextos de test fijan
la clave vacía y un test lo comprueba.

| Variable | Defecto | Para qué |
|----------|---------|----------|
| `ANTHROPIC_API_KEY` | (vacía) | Clave de la API de Claude. Vacía = modo manual |
| `INVOICES_CLAUDE_MODEL` | `claude-opus-5-5` | Modelo del reconocimiento |
| `INVOICES_CLAUDE_EFFORT` | `medium` | Esfuerzo del modelo |
| `INVOICES_CLAUDE_FALLBACKS` | `true` | Respaldo del servidor si el modelo rechaza; desactivable si la API lo rechazara |
| `INVOICES_RECONOCIMIENTO_CONCURRENCIA` | 3 | Reconocimientos simultáneos |
| `INVOICES_RECONOCIMIENTO_TIMEOUT_SEGUNDOS` | 120 | Tiempo máximo de una llamada |
| `INVOICES_MAX_REQUEST_SIZE` | 50MB | Tamaño máximo de una subida (varios ficheros) |
| `INVOICES_MAX_BYTES_POR_FICHERO` | 10485760 | Tamaño máximo de cada fichero |
| `INVOICES_PDF_MAX_PAGINAS` | 20 | Páginas máximas de un PDF |

## Desarrollo guiado por especificaciones (SDD)

Este repo usa [Spec Kit](https://github.com/github/spec-kit) para desarrollo
guiado por especificaciones: antes de escribir código se escribe una
especificación, de ahí sale un plan, del plan una lista de tareas, y solo
entonces se implementa. La idea es que el *qué* y el *por qué* queden escritos
y revisables, en vez de vivir en la cabeza de quien programó.

### Requisito previo

El flujo lo conduce un agente de código (Claude Code), pero el andamiaje lo
genera la CLI `specify`, que no viene en el repo. Instálala una vez:

```bash
uv tool install specify-cli --from git+https://github.com/github/spec-kit.git
```

Si no tienes `uv`: `brew install uv`. Comprueba con `specify check`.

No hace falta volver a ejecutar `specify init`: el repo ya está inicializado
(integración `claude`, scripts `sh`, numeración secuencial de features).

### El ciclo

Cada paso es un comando que se invoca dentro del agente, no en la terminal:

| Comando | Para qué |
|---------|----------|
| `/speckit-constitution` | Fija o enmienda los principios del proyecto. Ya está hecho; solo se vuelve a usar para enmendarlos. |
| `/speckit-specify` | Escribe la especificación de una feature a partir de una descripción en lenguaje natural. Crea su rama y su carpeta. |
| `/speckit-clarify` | *(opcional)* Hace preguntas dirigidas para cerrar ambigüedades. Mejor antes de `plan`. |
| `/speckit-plan` | Convierte la spec en plan de implementación y artefactos de diseño. |
| `/speckit-tasks` | Desglosa el plan en tareas ordenadas por dependencias. |
| `/speckit-analyze` | *(opcional)* Informe de coherencia entre spec, plan y tareas. |
| `/speckit-implement` | Ejecuta las tareas. |
| `/speckit-converge` | Compara el código real con la spec y añade como tareas lo que falte. |

`/speckit-specify` crea una carpeta por feature, numerada de forma secuencial:

```
specs/
└── 001-nombre-de-la-feature/
    ├── spec.md
    ├── plan.md
    └── tasks.md
```

### Qué hay versionado

- `.specify/` — plantillas, scripts y la **constitución** del proyecto
  (`.specify/memory/constitution.md`).
- `.claude/skills/speckit-*/` — los comandos de arriba.

La constitución está ratificada (versión y fecha al pie del fichero) y
prevalece sobre este README y sobre `docs/ARCHITECTURE.md` si se contradicen.
Cambiarla es una enmienda con su propio PR, no un efecto de una feature.

### Skills de terceros (no versionadas)

Aparte de las de Spec Kit, el entorno de desarrollo usa skills de Supabase y
JetBrains que **no están en el repo** a propósito (son ~744 KB de código ajeno;
ver las reglas en `.gitignore`). Si las quieres, instálalas tú:

```bash
# Necesita Node: brew install node
npx skills add supabase/agent-skills -a claude-code -y
npx skills add Kotlin/kotlin-agent-skills -a claude-code -y
```

Son opcionales — el flujo SDD funciona sin ellas. Las útiles aquí son
`kotlin-backend-jpa-entity-mapping` (entidades JPA, `LazyInitializationException`,
fetch plans) y `supabase-postgres-best-practices` (esquema, migraciones, RLS,
índices), relevante porque el `datasource` ya admite Supabase y hoy la
autorización vive solo en `SecurityConfig`, sin nada a nivel de base de datos.

## Ausencias y vacaciones

El contrato está en [`specs/007-absences/contracts/README.md`](specs/007-absences/contracts/README.md).

| Ruta | Quién | Qué hace |
|------|-------|----------|
| `POST /api/ausencias` | `EMPLEADO`, `ENCARGADO`, `ADMIN` | Pedir vacaciones o un permiso para uno mismo → `PENDIENTE` |
| `POST /api/ausencias/registro` | `ENCARGADO`, `ADMIN` | Registrar en nombre de otra persona, aprobada (bajas médicas, regularizaciones) |
| `GET /api/ausencias?empleadoId&desde&hasta&estado` | `EMPLEADO`, `ENCARGADO`, `ADMIN` | Calendario; un `EMPLEADO` solo recibe las suyas |
| `POST /api/ausencias/{id}/aprobar` · `/rechazar` | `ENCARGADO`, `ADMIN` | Nunca las propias; rechazar exige motivo |
| `POST /api/ausencias/{id}/cancelar` | la persona | Pendiente, o aprobada que aún no ha empezado |
| `POST /api/ausencias/{id}/alta` | `ENCARGADO`, `ADMIN` | Cierra una baja abierta |
| `GET /api/ausencias/saldo?anio` | `EMPLEADO`, `ENCARGADO`, `ADMIN` | Derecho, aprobados, pendientes y disponibles |
| `PUT /api/ausencias/derechos/{empleadoId}/{anio}` | `ADMIN` | Derecho anual de una persona |

- **Sin solapamientos**: dos ausencias vigentes de una persona no se pisan, ni
  con peticiones simultáneas (un bloqueo por persona en la base de datos).
- **Días naturales**: 30 por año por defecto (`ABSENCES_DIAS_VACACIONES`), el
  mínimo del art. 38 del Estatuto. Sin festivos ni prorrateo: el `ADMIN` ajusta
  el derecho de quien entra a mitad de año.
- **Bajas sin datos de salud**: solo el tipo; ni comentario ni causa, y la base
  de datos lo impide.
- `REPRESENTANTE` no accede: el art. 34.9 le da el registro de jornada, no las
  ausencias.

## Notificaciones

Sin correo ni push: la aplicación consulta la bandeja de cada persona. El
contrato está en [`specs/008-notifications/contracts/README.md`](specs/008-notifications/contracts/README.md).

| Ruta | Qué hace |
|------|----------|
| `GET /api/notificaciones?soloNoLeidas=false` | Las propias, de la más reciente, hasta 100 |
| `GET /api/notificaciones/no-leidas` | `{"total": n}` |
| `POST /api/notificaciones/{id}/leida` | Marca una; ajena → `404` |
| `POST /api/notificaciones/leidas` | Marca todas |

| Aviso | A quién |
|-------|---------|
| `FICHAJE_SIN_SALIDA` | Titular de un fichaje abierto más de 10 h (`TIMETRACKING_AVISO_SIN_SALIDA_HORAS`) |
| `FICHAJE_INCOMPLETO` | Titular del fichaje que se marca incompleto |
| `CORRECCION_PENDIENTE`, `AUSENCIA_PENDIENTE` | `ENCARGADO` y `ADMIN`, menos quien la pidió |
| `CORRECCION_APROBADA/RECHAZADA`, `AUSENCIA_APROBADA/RECHAZADA` | Titular |
| `REGISTRO_PENDIENTE` | `ADMIN` |

Un aviso es un tipo y un identificador; el texto es fijo por tipo, así que
nunca lleva nombres, motivos ni comentarios. Se crea solo si la operación que lo
origina se confirma, y si falla no la deshace. Los leídos se borran a los 90
días y todos a los 180 (`NOTIFICATIONS_DIAS_LEIDAS`, `NOTIFICATIONS_DIAS_TODAS`).

## Despliegue

La aplicación se entrega como imagen de contenedor (feature 006):

```bash
docker build -t granatum-suite-backend .
docker run -d --env-file prod.env -p 8080:8080 granatum-suite-backend
```

O, en local, junto a Postgres:

```bash
docker compose --profile app up -d --build   # sin --profile app solo arranca Postgres
```

Qué garantiza la imagen:

- **Arranca como `prod`.** También la aplicación: si nadie fija
  `SPRING_PROFILES_ACTIVE`, el perfil es `prod`, y `POST /api/dev/token` no
  existe. Antes el defecto era `dev`, y olvidar la variable dejaba viva una ruta
  que emite tokens de `ADMIN` sin credencial. Solo `./gradlew :app:bootRun`
  arranca en `dev` si nadie dice otra cosa.
- **Sin privilegios**: corre como el usuario `granatum` (uid 10001).
- **Comprobación de salud** en `/actuator/health` cada 15 s.
- **Sin secretos dentro**: `.dockerignore` deja fuera `.env`, el historial de git
  y lo compilado; los secretos llegan como variables de entorno al arrancar.
- Los tests **no** corren en `docker build`: los corre la CI antes, con Postgres
  y Testcontainers.

La CI (`.github/workflows/ci.yml`) corre en **todas las ramas** y en cada
propuesta de cambio, y además construye la imagen y comprueba que no corre como
`root` ni lleva `.env`.

### Límites de peticiones

Por dirección de origen, en memoria, con un cupo por grupo de rutas
(`N/periodo`, u `off` para desactivarlo):

| Variable | Defecto | Rutas |
|----------|---------|-------|
| `SEGURIDAD_LIMITE_LOGIN` | `10/1m` | `POST /api/auth/login` |
| `SEGURIDAD_LIMITE_SESION` | `30/1m` | `POST /api/auth/refresh`, `POST /api/auth/logout` |
| `SEGURIDAD_LIMITE_REGISTRO` | `5/1h` | `POST /api/auth/registro`, código de arranque incluido |
| `SEGURIDAD_LIMITE_GENERAL` | `300/1m` | Todo `/api` (la comprobación de salud no está bajo `/api`) |
| `SEGURIDAD_LIMITE_MAX_DIRECCIONES` | `10000` | Direcciones recordadas por cupo; al llenarse se olvida la menos usada |

Superado un cupo, `429 DEMASIADAS_PETICIONES` con `Retry-After` en segundos, sin
haber comprobado la contraseña ni escrito nada.

> **Detrás de un proxy o balanceador**, todas las peticiones llegan desde la
> dirección del proxy y compartirían cupo. Pon
> `SERVER_FORWARD_HEADERS_STRATEGY=native`: Tomcat tomará la dirección real de
> `X-Forwarded-For`, **solo** si la petición viene de un proxy de red interna.
> No la actives sin proxy: cualquiera podría inventarse una dirección por
> petición y saltarse los límites.

> **Varias instancias**: cada una cuenta por su cuenta, así que el límite
> efectivo es el configurado por el número de instancias. Los contadores
> compartidos necesitarían infraestructura (Redis) que la constitución deja
> fuera mientras no haga falta.

Las direcciones de origen no se escriben en los logs ni en la base de datos.

### CORS y cabeceras

`CORS_ALLOWED_ORIGINS` lista los orígenes de navegador admitidos, separados por
comas (`https://app.granatum.es`). **Vacío, ninguno**: toda petición de navegador
de otro origen recibe `403`. La app móvil no envía `Origin` y no le afecta.

Todas las respuestas llevan `Content-Security-Policy: default-src 'none'`,
`X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy:
no-referrer`, `Permissions-Policy` sin permisos, `Cache-Control: no-store` y, en
HTTPS, HSTS de un año. Ningún error lleva traza: lo que no responde un módulo
responde `{code, message}` (`RECURSO_NO_ENCONTRADO`, `METODO_NO_PERMITIDO`,
`ERROR_INTERNO`…).

## Supabase

Supabase es Postgres, así que no hace falta ningún cambio de esquema ni de
JPA/Flyway - solo apuntar el `datasource` a su host. Supabase da tres cadenas
de conexión distintas (pestaña *Connect* del proyecto); cuál usar importa:

| Conexión                    | Puerto | Cuándo usarla |
|------------------------------|--------|----------------|
| **Direct connection**         | 5432   | Recomendada si el backend corre en un solo proceso de larga duración (esto). Sin límites de PgBouncer. |
| **Session pooler**            | 5432   | Igual de compatible que la directa, útil si tu red no soporta IPv6 (la directa de Supabase es IPv6-only salvo add-on de IPv4). |
| **Transaction pooler (PgBouncer)** | 6543 | Pensada para serverless/muchas conexiones cortas. **No** soporta prepared statements a nivel de sesión ni `SET`, así que rompe a Hibernate y a Flyway si no se ajusta (ver abajo). |

Para desarrollo o un backend "tradicional" como este, usa **Direct connection**
o **Session pooler**. Variables de entorno (`.env`):

```bash
DB_HOST=aws-0-<region>.pooler.supabase.com   # o db.<project-ref>.supabase.co para la directa
DB_PORT=5432
DB_NAME=postgres
DB_USERNAME=postgres.<project-ref>            # la directa usa solo "postgres"
DB_PASSWORD=<tu contraseña de base de datos>
DB_SSLMODE=require
```

Si en su lugar necesitas el **Transaction pooler** (puerto 6543), añade:

```bash
DB_PORT=6543
DB_PREPARE_THRESHOLD=0        # desactiva prepared statements de servidor (PgBouncer los rompe)
DB_POOL_MAX_SIZE=4             # mantente por debajo del límite de conexiones por cliente del pooler
FLYWAY_DB_URL=jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require
FLYWAY_DB_USERNAME=postgres
FLYWAY_DB_PASSWORD=<tu contraseña de base de datos>
```

(Flyway necesita locks de sesión que el modo transacción de PgBouncer no da,
así que ahí sí conviene que las migraciones vayan por la conexión directa
aunque el resto de la app use el pooler.)

En producción, usa el perfil `prod` (`SPRING_PROFILES_ACTIVE=prod`), que pone
`ddl-auto: validate` y no formatea el SQL en logs.

### Row Level Security

Supabase publica automáticamente una API REST (PostgREST) sobre el esquema
`public`. Una tabla sin RLS ahí queda legible desde Internet con la clave
anónima, que es **pública por diseño** — y eso es independiente de la
autorización que haga el backend.

Por eso todas las tablas de aplicación llevan RLS activado desde su migración
(`V5__enable_row_level_security.sql`), sin ninguna política: eso significa
denegar por defecto, así que `anon` y `authenticated` no ven ni una fila. El
backend conecta como propietario de las tablas, y los propietarios no están
sujetos a RLS, así que sigue funcionando sin cambios.

> No se usa `FORCE ROW LEVEL SECURITY` a propósito: aplicaría RLS también al
> propietario y dejaría a la aplicación sin acceso a sus propios datos.

`RowLevelSecurityIT` falla el build si alguna tabla se queda sin RLS, así que
una migración futura que cree una tabla y lo olvide no llega a `main`.

Donde más importa es en `cuentas_acceso`, que guarda correos y hashes de
contraseña: sin RLS, PostgREST la serviría a cualquiera con la clave anónima.

**Un paso manual pendiente por entorno.** La tabla de control de Flyway,
`flyway_schema_history`, también vive en `public` y PostgREST la serviría
(versiones y descripciones de las migraciones; no hay datos personales ni
credenciales, pero sí información de reconocimiento). No se puede arreglar
desde una migración, porque Flyway mantiene un lock sobre esa tabla durante
toda su ejecución y el `ALTER TABLE` se bloquearía contra sí mismo
indefinidamente. Ejecútalo una vez, a mano, en cada entorno con Supabase:

```sql
ALTER TABLE flyway_schema_history ENABLE ROW LEVEL SECURITY;
```

## Comandos habituales

```bash
./gradlew build          # compila y ejecuta tests de todos los módulos
./gradlew test           # solo tests
./gradlew :features:timetracking:test   # tests de una sola feature
./gradlew :app:bootRun   # arranca la app
docker compose down -v   # apaga y limpia los volúmenes locales
```

## Estructura del repositorio

```
.
├── app/            # módulo ejecutable: main class, seguridad, config, application.yml
├── common/         # kernel compartido: excepciones, JWT, roles y contratos entre features
├── features/       # un módulo Gradle por feature, cada uno depende solo de common
│   ├── inventory/      # dominio de inventario (Material, Categoria, HistorialMaterial)
│   ├── timetracking/   # registro de jornada (Empleado, Fichaje, Pausa, correcciones, exportación)
│   ├── auth/           # inicio de sesión, sesiones, bloqueo, alta y restablecimiento
│   └── invoices/       # facturación: subida, reconocimiento con Claude, revisión, reportes, trimestres
├── build-logic/    # convention plugins de Gradle (composite build)
├── gradle/         # version catalog + gradle wrapper
├── .specify/       # Spec Kit: plantillas, scripts y constitución del proyecto
├── .claude/skills/ # comandos speckit-* (las skills de terceros van ignoradas)
├── specs/          # una carpeta por feature, la crea /speckit-specify
├── docker-compose.yml
└── docs/ARCHITECTURE.md
```
