# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/);
versiones según [SemVer](https://semver.org/lang/es/). Modelo de ramas en
[docs/RAMAS.md](docs/RAMAS.md).

## [Sin publicar] — `develop`

### Añadido

- **Eventos** (spec 011). La plantilla crea eventos y les asigna material del
  inventario, que se retira al asignarlo y vuelve al quitarlo, al borrar el evento
  o al finalizarlo (solo, al terminar su día, o a mano). Sin stock suficiente,
  `409 INSUFFICIENT_STOCK` con las unidades disponibles. `REPRESENTATIVE` los
  consulta. Constitución v2.5.0: `EMPLOYEE` gestiona eventos (sigue sin acceso al
  inventario).
- `DELETE /api/materials/{id}` de un material asignado a un evento programado
  responde `409 MATERIAL_IN_USE`.

### Cambiado

- El reconocimiento de facturas usa por defecto `claude-sonnet-5-5` en lugar de
  `claude-opus-5-5`: cuesta la mitad por factura. `INVOICES_CLAUDE_MODEL` sigue
  permitiendo cambiarlo sin tocar el código.
- **Solo el propietario se registra** (spec 009). `POST /api/auth/sign-up`
  exige el código de arranque; sin él responde `400`.
- **Alta del personal en un paso** por un `ADMIN`: `POST /api/auth/onboarding` crea la
  ficha y la cuenta y devuelve una contraseña provisional, que la persona cambia
  al entrar por primera vez.

- **Materiales del inventario rehechos** (spec 010). Cada material es categoría,
  descripción, dimensiones (alto y ancho, en cm), cantidad, localización
  (almacén `A`, `B` o `C` y sección `1`–`5`), una imagen opcional y sus fechas
  de alta y modificación. **Incompatible** con los clientes anteriores de
  `/api/materials`: cambian los cuerpos de petición y respuesta, y
  `PATCH …/quantity` pasa a recibir `quantity`. La migración V26 descarta los
  materiales existentes (eran datos de prueba) y su historial.
- **Nombres en inglés** en todo el backend
  ([docs/english-naming.md](docs/english-naming.md)): identificadores del
  código, rutas REST, parámetros, campos JSON, valores de enums, roles (`ADMIN`,
  `MANAGER`, `EMPLOYEE`, `REPRESENTATIVE`), códigos de error, tablas y columnas,
  claves de configuración y nombres de variables de entorno. Los textos para
  personas (`message` de los errores, avisos, PDF, CSV) y la documentación
  siguen en español. Las migraciones V28 a V34 renombran tablas y columnas y
  actualizan los valores guardados sin perder datos. **Incompatible** con los
  clientes anteriores. Los tokens emitidos antes del despliegue llevan los roles
  antiguos y dejan de valer: cada persona vuelve a iniciar sesión una vez.
  Variables de entorno que un despliegue tiene que renombrar (antes → después):
  - `AUTH_HASH_CONCURRENCIA` → `AUTH_HASH_CONCURRENCY`
  - `AUTH_HASH_ESPERA_MS` → `AUTH_HASH_WAIT_MS`
  - `AUTH_PURGA_SESIONES_DIAS` → `AUTH_SESSION_PURGE_DAYS`
  - `AUTH_EVENTOS_RETENCION_DIAS` → `AUTH_SECURITY_EVENTS_RETENTION_DAYS`
  - `AUTH_CODIGO_ARRANQUE` → `AUTH_BOOTSTRAP_CODE`
  - `TIMETRACKING_EXPORTACION_CONCURRENCIA` → `TIMETRACKING_EXPORT_CONCURRENCY`
  - `TIMETRACKING_EXPORTACION_ESPERA_MS` → `TIMETRACKING_EXPORT_WAIT_MS`
  - `TIMETRACKING_EXPORTACION_TIMEOUT_SEGUNDOS` → `TIMETRACKING_EXPORT_TIMEOUT_SECONDS`
  - `TIMETRACKING_EXPORTACION_TAMANO_LOTE` → `TIMETRACKING_EXPORT_BATCH_SIZE`
  - `TIMETRACKING_VERIFICACION_MAX_BYTES` → `TIMETRACKING_VERIFICATION_MAX_BYTES`
  - `TIMETRACKING_AVISO_SIN_SALIDA_HORAS` → `TIMETRACKING_MISSING_CLOCK_OUT_HOURS`
  - `TIMETRACKING_AVISO_SIN_SALIDA_CRON` → `TIMETRACKING_MISSING_CLOCK_OUT_CRON`
  - `TIMETRACKING_RETENCION_*` → `TIMETRACKING_RETENTION_*` (claves
    `timetracking.retention.enabled`, `.cron` y `.years`; por ejemplo
    `TIMETRACKING_RETENCION_HABILITADA` → `TIMETRACKING_RETENTION_ENABLED`)
  - `INVOICES_RECONOCIMIENTO_CONCURRENCIA` → `INVOICES_RECOGNITION_CONCURRENCY`
  - `INVOICES_RECONOCIMIENTO_TIMEOUT_SEGUNDOS` → `INVOICES_RECOGNITION_TIMEOUT_SECONDS`
  - `INVOICES_REINTENTO_CRON` → `INVOICES_RETRY_CRON`
  - `INVOICES_REINTENTO_TRAS_MINUTOS` → `INVOICES_RETRY_AFTER_MINUTES`
  - `INVOICES_MAX_BYTES_POR_FICHERO` → `INVOICES_MAX_BYTES_PER_FILE`
  - `INVOICES_PDF_MAX_PAGINAS` → `INVOICES_PDF_MAX_PAGES`
  - `INVOICES_REFERENCIA_DIR` → `INVOICES_REFERENCE_DIR`
  - `INVENTORY_IMAGENES_BUCKET` → `INVENTORY_IMAGES_BUCKET` (el bucket por
    defecto sigue llamándose `materiales`)
  - `INVENTORY_IMAGENES_URL_SEGUNDOS` → `INVENTORY_IMAGES_URL_SECONDS`
  - `INVENTORY_IMAGENES_MAX_BYTES` → `INVENTORY_IMAGES_MAX_BYTES`
  - `INVENTORY_IMAGENES_TIMEOUT_SEGUNDOS` → `INVENTORY_IMAGES_TIMEOUT_SECONDS`
  - `EVENTS_FINALIZACION_CRON` → `EVENTS_FINISHING_CRON`
  - `SEGURIDAD_LIMITE_LOGIN` → `SECURITY_RATE_LIMIT_LOGIN`
  - `SEGURIDAD_LIMITE_SESION` → `SECURITY_RATE_LIMIT_SESSION`
  - `SEGURIDAD_LIMITE_REGISTRO` → `SECURITY_RATE_LIMIT_SIGN_UP`
  - `SEGURIDAD_LIMITE_GENERAL` → `SECURITY_RATE_LIMIT_GENERAL`
  - `SEGURIDAD_LIMITE_MAX_DIRECCIONES` → `SECURITY_RATE_LIMIT_MAX_ADDRESSES`
  - `ABSENCES_DIAS_VACACIONES` → `ABSENCES_VACATION_ANNUAL_DAYS`
  - `NOTIFICATIONS_DIAS_LEIDAS` → `NOTIFICATIONS_READ_DAYS`
  - `NOTIFICATIONS_DIAS_TODAS` → `NOTIFICATIONS_ALL_DAYS`
  - `NOTIFICATIONS_LIMPIEZA_CRON` → `NOTIFICATIONS_CLEANUP_CRON`

  Una variable con el nombre antiguo se ignora sin error y la aplicación usa el
  valor por defecto. Para regenerar el contrato:
  `OPENAPI_UPDATE=true ./gradlew :app:test --tests '*OpenApiContractIT'`
  (antes `OPENAPI_ACTUALIZAR`).

### Añadido

- **Imagen de cada material en Supabase Storage** (spec 010):
  `PUT /api/materials/{id}/image` (multipart, parte `image`) y
  `DELETE /api/materials/{id}/image`; las lecturas devuelven `imageUrl`
  firmada y temporal. Variables nuevas: `SUPABASE_URL`,
  `SUPABASE_SERVICE_ROLE_KEY` (secreto), `INVENTORY_IMAGES_*`.
- Filtro del listado de materiales por almacén y sección
  (`GET /api/materials?warehouse=A&section=3`).

### Eliminado

- De los materiales: nombre, cantidad total, color, material físico, estado,
  precio unitario, proveedor, diámetro, unidad de medida y las fotos por URL
  (tabla `material_fotos`).
- El registro con solicitud de la 1.0.0 (spec 005, US2 a US5): solicitudes,
  códigos de verificación, aprobación y rechazo (`/api/auth/registros`),
  caducidad y el aviso `REGISTRO_PENDIENTE`. Las migraciones V24 y V25 borran
  sus datos. Variables retiradas: `AUTH_REGISTRO_MAX_PENDIENTES`,
  `AUTH_REGISTRO_CADUCIDAD_DIAS`.

## [1.0.0] — en preparación (`release/1.0.0`)

Primera versión para producción. Pendiente antes de fusionar en `main`: elegir
plataforma y las tareas de operación de [docs/ROADMAP.md](docs/ROADMAP.md)
(apartado 1.0.0); el despliegue se describe en [docs/DESPLIEGUE.md](docs/DESPLIEGUE.md).

### Añadido

- **Inventario** de material: categorías, materiales con fotos e historial de
  cambios inmutable.
- **Registro de jornada** conforme al RD-ley 8/2019 (spec 001): entrada, pausas
  y salida, modo sin conexión idempotente, correcciones con aprobación,
  fichajes incompletos, resumen mensual y depuración a los 4 años (apagada por
  defecto).
- **Exportación del registro** (spec 003): CSV por persona y de toda la
  plantilla, con huella SHA-256 y registro de quién exportó qué.
- **Acceso** (spec 002): inicio de sesión con correo y contraseña (Argon2),
  renovación de un solo uso, cierre de sesión, bloqueo creciente, contraseñas
  temporales y restablecimiento por un `ADMIN`.
- **Registro del personal** (spec 005): cada persona se registra y un `ADMIN`
  la aprueba con un código de verificación; primer `ADMIN` con código de
  arranque.
- **Facturación** (spec 004): subida de fotos y PDF, lectura con Claude o modo
  manual, revisión, reportes mensuales, trimestrales y anuales en CSV y PDF,
  cierre de trimestres.
- **Ausencias y vacaciones** (spec 007): solicitudes, aprobación, bajas sin
  datos de salud, sin solapamientos, saldo anual.
- **Notificaciones** dentro de la aplicación (spec 008).
- **Contrato OpenAPI** generado (springdoc) y versionado en `docs/openapi.json`.
- **Despliegue** (spec 006): imagen de contenedor sin privilegios con
  comprobación de salud; CI en todas las ramas.

### Plataforma

- Spring Boot **4.0.8** (GA) en lugar de `4.0.0-SNAPSHOT`, sin repositorios de
  snapshots. Hibernate 7.2, Jackson 2.21, Testcontainers 1.21.4.
- La versión vive en un solo sitio (`build.gradle.kts`) y `/actuator/info` la
  muestra.
- Imagen publicada en GitHub Container Registry al etiquetar `vX.Y.Z`
  (`.github/workflows/publicar-imagen.yml`), tras comprobar que la etiqueta
  coincide con la versión.
- Modelo de ramas Git Flow ([docs/RAMAS.md](docs/RAMAS.md)); la CI rechaza PR
  contra `main` que no vengan de `release/*` o `hotfix/*`.

### Seguridad

- Límites de peticiones por dirección de origen, cabeceras de seguridad, CORS
  explícito, errores sin trazas, perfil `prod` por defecto (spec 006).
- Row Level Security en todas las tablas, incluida la de Flyway.
- Sin datos personales en los logs, comprobado en cada módulo con el log en
  `DEBUG`; con Hibernate 7.2 se fija también `org.hibernate.orm.core`.
