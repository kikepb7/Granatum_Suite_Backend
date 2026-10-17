# Nombres en inglés (2026-10-18)

Decisión del responsable del producto: todo identificador del código (clases, ficheros, funciones,
variables, tests), del contrato de la API (rutas, parámetros, campos JSON, valores de enums,
códigos de error, roles) y de la base de datos (tablas, columnas, valores guardados) pasa a inglés.
Los textos que lee una persona (`message` de los errores, avisos, PDF, CSV) siguen en español, y la
documentación también. Esta tabla es la fuente única: backend, app y backend demo de la app la
siguen igual.

## Glosario (campos, variables y nombres de clase)

| Español | Inglés | Español | Inglés |
|---|---|---|---|
| empleado / persona | employee | cuenta | account |
| fichaje | shift | pausa | break |
| entrada / salida | clockIn / clockOut | corrección | correction |
| ausencia | absence | derecho (vacaciones) | entitlement |
| saldo | balance | alta (de una baja) | discharge |
| registro (de ausencia por otro) | record | solicitud | request |
| exportación | export | huella | fingerprint |
| factura | invoice | empresa | company |
| trimestre | quarter | reconocimiento | recognition |
| parte (emisor/destinatario) | party | emisor / destinatario | issuer / recipient |
| línea de IVA | vatLine | retención | withholding |
| material | material | categoría | category |
| historial | history | ubicación / localización | location |
| almacén / sección | warehouse / section | dimensiones (alto, ancho) | dimensions (height, width) |
| evento | event | notificación | notification |
| nombre | name | descripción | description |
| fecha | date | estado | status |
| tipo | type | motivo | reason |
| cantidad | quantity | comentario | comment |
| desde / hasta | from / to | año / mes | year / month |
| creado/a en | createdAt | resuelto/a en / por | resolvedAt / resolvedBy |
| solicitado/a en / por | requestedAt / requestedBy | cancelado/a en | cancelledAt |
| finalizado en | finishedAt | leído/a en | readAt |
| fecha de alta | createdAt (registro) / hireDate (empleado) | fecha de última modificación | updatedAt |
| documento de identidad | identityDocument | puesto | position |
| tipo de contrato | contractType | activo | active |
| contraseña temporal | temporaryPassword | código de arranque | bootstrapCode |
| requiere cambio de contraseña | passwordChangeRequired | contraseña actual / nueva | currentPassword / newPassword |
| minutos trabajados / de pausa | workedMinutes / breakMinutes | resumen | summary |
| valores originales / propuestos | originalValues / proposedValues | solicitante | requester |
| página / tamaño | page / size | razón social | legalName |
| periodo / formato | period / format | número | number |
| concepto | concept | moneda | currency |
| aviso | notice | campo / código / mensaje | field / code / message |
| precisión (metros) | accuracyMeters | latitud / longitud | latitude / longitude |
| creado por | createdBy | id del material | materialId |
| valor anterior / nuevo | previousValue / newValue | tipo de cambio | changeType |
| imagen | image (`imageUrl`) | categoría (nombre) | category |

## Roles (JWT `role`, `accounts.role`)

`ADMIN` → `ADMIN` · `ENCARGADO` → `MANAGER` · `EMPLEADO` → `EMPLOYEE` · `REPRESENTANTE` → `REPRESENTATIVE`

## Rutas

| Antes | Después |
|---|---|
| `/api/ausencias` (`empleadoId`, `estado`, `desde`, `hasta`) | `/api/absences` (`employeeId`, `status`, `from`, `to`) |
| `/api/ausencias/derechos/{empleadoId}/{anio}` | `/api/absences/entitlements/{employeeId}/{year}` |
| `/api/ausencias/registro` | `/api/absences/record` |
| `/api/ausencias/saldo` (`anio`, `empleadoId`) | `/api/absences/balance` (`year`, `employeeId`) |
| `/api/ausencias/{id}/alta` · `/aprobar` · `/cancelar` · `/rechazar` | `/api/absences/{id}/discharge` · `/approve` · `/cancel` · `/reject` |
| `/api/auth/altas` | `/api/auth/onboarding` |
| `/api/auth/cuentas` · `/cuentas/huerfanas` · `/cuentas/{empleadoId}/restablecer` | `/api/auth/accounts` · `/accounts/orphans` · `/accounts/{employeeId}/reset` |
| `/api/auth/registro` | `/api/auth/sign-up` |
| `/api/auth/login` · `/logout` · `/refresh` · `/change-password` | sin cambios |
| `/api/categorias` | `/api/categories` |
| `/api/correcciones` (`estado`) · `/{id}/aprobar` · `/{id}/rechazar` | `/api/corrections` (`status`) · `/{id}/approve` · `/{id}/reject` |
| `/api/empleados` (`activo`) · `/{id}/activo` | `/api/employees` (`active`) · `/{id}/active` |
| `/api/eventos` (`estado`) · `/materiales` · `/{id}/finalizar` · `/{id}/materiales/{materialId}` | `/api/events` (`status`) · `/materials` · `/{id}/finish` · `/{id}/materials/{materialId}` |
| `/api/exportaciones` (`empleadoId`, `generadaDesde`, `generadaHasta`, `cubreDesde`, `cubreHasta`) · `/verificar` | `/api/exports` (`employeeId`, `generatedFrom`, `generatedTo`, `coversFrom`, `coversTo`) · `/verify` |
| `/api/facturacion/empresa` | `/api/invoicing/company` |
| `/api/facturacion/facturas` (`desde`, `hasta`, `parte`, `tipo`, `estado`, `pagina`, `tamano`) | `/api/invoicing/invoices` (`from`, `to`, `party`, `type`, `status`, `page`, `size`) |
| `…/facturas/{id}/confirmar` · `/descartar` · `/historial` · `/original` · `/reconocer` | `…/invoices/{id}/confirm` · `/discard` · `/history` · `/original` · `/recognize` |
| `/api/facturacion/reportes` (`periodo`, `anio`, `mes`, `trimestre`, `formato`) | `/api/invoicing/reports` (`period`, `year`, `month`, `quarter`, `format`) |
| `/api/facturacion/trimestres` (`anio`) · `/{anio}/{trimestre}/cerrar` · `/reabrir` | `/api/invoicing/quarters` (`year`) · `/{year}/{quarter}/close` · `/reopen` |
| `/api/fichajes` (`desde`, `hasta`) | `/api/shifts` (`from`, `to`) |
| `/api/fichajes/empleado/{empleadoId}` · `/resumen` (`anio`, `mes`) · `/resumen/descarga` | `/api/shifts/employee/{employeeId}` · `/summary` (`year`, `month`) · `/summary/download` |
| `/api/fichajes/entrada` · `/{id}/salida` · `/{id}/pausa/inicio` · `/{id}/pausa/fin` | `/api/shifts/clock-in` · `/{id}/clock-out` · `/{id}/break/start` · `/{id}/break/end` |
| `/api/fichajes/export` (`formato`, `desde`, `hasta`, `empleadoId`) · `/{id}/correcciones` | `/api/shifts/export` (`format`, `from`, `to`, `employeeId`) · `/{id}/corrections` |
| `/api/materiales` (`almacen`, `seccion`) · `/{id}/cantidad` · `/historial` · `/imagen` | `/api/materials` (`warehouse`, `section`) · `/{id}/quantity` · `/history` · `/image` |
| `/api/notificaciones` (`soloNoLeidas`) · `/leidas` · `/no-leidas` · `/{id}/leida` | `/api/notifications` (`unreadOnly`) · `/read` · `/unread` · `/{id}/read` |
| `/api/dev/token` | sin cambios |

## Valores de enums

| Enum | Valores |
|---|---|
| Tipo de contrato | `JORNADA_COMPLETA` `PARCIAL` `POR_HORAS` → `FULL_TIME` `PART_TIME` `HOURLY` |
| Estado del fichaje | `EN_CURSO` `CERRADO` `INCOMPLETO` → `IN_PROGRESS` `CLOSED` `INCOMPLETE` |
| Tipo de pausa | `COMIDA` `DESCANSO` `OTRO` → `MEAL` `REST` `OTHER` |
| Estado de corrección | `PENDIENTE` `APROBADA` `RECHAZADA` → `PENDING` `APPROVED` `REJECTED` |
| Tipo de ausencia | `VACACIONES` `PERMISO` `BAJA_MEDICA` → `VACATION` `LEAVE` `SICK_LEAVE` |
| Estado de ausencia | `PENDIENTE` `APROBADA` `RECHAZADA` `CANCELADA` → `PENDING` `APPROVED` `REJECTED` `CANCELLED` |
| Causa de permiso | `MATRIMONIO` `NACIMIENTO` `FALLECIMIENTO_FAMILIAR` `ENFERMEDAD_FAMILIAR` `MUDANZA` `DEBER_INEXCUSABLE` `OTRO` → `MARRIAGE` `BIRTH` `FAMILY_DEATH` `FAMILY_ILLNESS` `HOUSE_MOVE` `PUBLIC_DUTY` `OTHER` |
| Alcance de exportación | `PERSONA` `PLANTILLA` `MENSUAL` → `PERSON` `STAFF` `MONTHLY` |
| Estado de factura | `PENDIENTE_RECONOCER` `BORRADOR` `CONFIRMADA` `DESCARTADA` → `PENDING_RECOGNITION` `DRAFT` `CONFIRMED` `DISCARDED` |
| Tipo de factura | `EMITIDA` `RECIBIDA` → `ISSUED` `RECEIVED` |
| Causa sin cuota | `EXENTA` `INVERSION_SUJETO_PASIVO` `INTRACOMUNITARIA` → `EXEMPT` `REVERSE_CHARGE` `INTRA_COMMUNITY` |
| Periodo de reporte | `MENSUAL` `TRIMESTRAL` `ANUAL` → `MONTHLY` `QUARTERLY` `YEARLY` |
| Resultado de subida | `ACEPTADA` `DUPLICADA` `FORMATO_NO_ADMITIDO` `DEMASIADO_GRANDE` `VACIO` → `ACCEPTED` `DUPLICATE` `UNSUPPORTED_FORMAT` `TOO_LARGE` `EMPTY` |
| Resultado de reconocimiento | `RECONOCIDA` `NO_ES_FACTURA` `VARIAS_FACTURAS` `PDF_NO_LEGIBLE` `ERROR_API` `ERROR_RED` `RESPUESTA_INVALIDA` `TIMEOUT` `REFUSAL` `MAX_TOKENS` → `RECOGNIZED` `NOT_AN_INVOICE` `MULTIPLE_INVOICES` `UNREADABLE_PDF` `API_ERROR` `NETWORK_ERROR` `INVALID_RESPONSE` `TIMEOUT` `REFUSAL` `MAX_TOKENS` |
| Acción del historial de factura y trimestre | `CORRECCION` `CONFIRMACION` `DESCARTE` `RECLASIFICACION` `CIERRE` `REAPERTURA` → `CORRECTION` `CONFIRMATION` `DISCARD` `RECLASSIFICATION` `CLOSE` `REOPEN` |
| Tipo de cambio del material | `CANTIDAD` `ESTADO` `UBICACION` `PRECIO_UNITARIO` `PROVEEDOR` `OTRO` → `QUANTITY` `STATUS` `LOCATION` `UNIT_PRICE` `SUPPLIER` `OTHER` |
| Estado del evento | `PROGRAMADO` `FINALIZADO` → `SCHEDULED` `FINISHED` |
| Tipo de notificación | `FICHAJE_SIN_SALIDA` `FICHAJE_INCOMPLETO` `CORRECCION_*` `AUSENCIA_*` (`_PENDIENTE`/`_APROBADA`/`_RECHAZADA`) → `SHIFT_WITHOUT_CLOCK_OUT` `SHIFT_INCOMPLETE` `CORRECTION_*` `ABSENCE_*` (`_PENDING`/`_APPROVED`/`_REJECTED`) |
| Almacén | `A` `B` `C` sin cambios |

Cualquier otro valor guardado o enviado sigue la misma regla (traducción literal en
`UPPER_SNAKE_CASE`); la migración de su módulo lo actualiza y lo deja escrito.

## Códigos de error

Mismo criterio: traducción literal en `UPPER_SNAKE_CASE`. Los que ya están en inglés
(`NOT_FOUND`, `FORBIDDEN`, `UNAUTHORIZED`, `INVALID_OPERATION`, `MATERIAL_NOT_FOUND`…) no cambian.

| Antes | Después | Antes | Después |
|---|---|---|---|
| `VALIDACION` | `VALIDATION` | `CREDENCIALES_INVALIDAS` | `INVALID_CREDENTIALS` |
| `DEMASIADAS_PETICIONES` | `TOO_MANY_REQUESTS` | `SERVICIO_SATURADO` | `SERVICE_BUSY` |
| `TOKEN_ACCESO_EXPIRADO` | `ACCESS_TOKEN_EXPIRED` | `NO_AUTENTICADO` | `NOT_AUTHENTICATED` |
| `TOKEN_RENOVACION_INVALIDO` | `INVALID_REFRESH_TOKEN` | `EMPLEADO_INACTIVO` | `EMPLOYEE_INACTIVE` |
| `PASSWORD_DEBIL` (`requisitos`) | `WEAK_PASSWORD` (`requirements`) | `CODIGO_ARRANQUE_INVALIDO` | `INVALID_BOOTSTRAP_CODE` |
| `EMAIL_YA_REGISTRADO` | `EMAIL_ALREADY_REGISTERED` | `CUENTA_YA_EXISTE` | `ACCOUNT_ALREADY_EXISTS` |
| `CUENTA_NO_ENCONTRADA` | `ACCOUNT_NOT_FOUND` | `DOCUMENTO_INVALIDO` | `INVALID_DOCUMENT` |
| `DOCUMENTO_DUPLICADO` | `DUPLICATE_DOCUMENT` | `EMPLEADO_NOT_FOUND` / `EMPLEADO_NO_ENCONTRADO` | `EMPLOYEE_NOT_FOUND` |
| `FICHAJE_NOT_FOUND` | `SHIFT_NOT_FOUND` | `FICHAJE_YA_EN_CURSO` | `SHIFT_ALREADY_IN_PROGRESS` |
| `FICHAJE_NO_EN_CURSO` | `SHIFT_NOT_IN_PROGRESS` | `FICHAJE_NO_FINALIZADO` | `SHIFT_NOT_FINISHED` |
| `FICHAJE_INMUTABLE` | `SHIFT_IMMUTABLE` | `PAUSA_YA_ABIERTA` | `BREAK_ALREADY_OPEN` |
| `PAUSA_NO_ABIERTA` | `BREAK_NOT_OPEN` | `PAUSA_ABIERTA_AL_CERRAR` | `BREAK_OPEN_ON_CLOSE` |
| `CLIENT_EVENT_ID_REUTILIZADO` | `CLIENT_EVENT_ID_REUSED` | `DESVIACION_RELOJ` | `CLOCK_SKEW` |
| `SOLICITUD_NOT_FOUND` | `REQUEST_NOT_FOUND` | `SOLICITUD_YA_RESUELTA` | `REQUEST_ALREADY_RESOLVED` |
| `VALORES_INCOHERENTES` | `INCONSISTENT_VALUES` | `UBICACION_NO_CORREGIBLE` | `LOCATION_NOT_CORRECTABLE` |
| `EXPORTACION_SATURADA` | `EXPORT_BUSY` | `AUSENCIA_NO_ENCONTRADA` | `ABSENCE_NOT_FOUND` |
| `AUSENCIA_SOLAPADA` | `ABSENCE_OVERLAP` | `SALDO_INSUFICIENTE` | `INSUFFICIENT_BALANCE` |
| `RANGO_INVALIDO` | `INVALID_RANGE` | `DATOS_AUSENCIA_INVALIDOS` | `INVALID_ABSENCE_DATA` |
| `AUSENCIA_NO_MODIFICABLE` | `ABSENCE_NOT_MODIFIABLE` | `RESOLUCION_PROPIA` | `OWN_RESOLUTION` |
| `NOTIFICACION_NO_ENCONTRADA` | `NOTIFICATION_NOT_FOUND` | `FACTURA_NOT_FOUND` | `INVOICE_NOT_FOUND` |
| `FACTURA_DUPLICADA` | `DUPLICATE_INVOICE` | `FACTURA_INCOHERENTE` | `INCONSISTENT_INVOICE` |
| `EMPRESA_SIN_CONFIGURAR` | `COMPANY_NOT_CONFIGURED` | `NIF_INVALIDO` | `INVALID_NIF` |
| `ESTADO_NO_PERMITIDO` | `STATUS_NOT_ALLOWED` | `VERSION_DESACTUALIZADA` | `STALE_VERSION` |
| `TRIMESTRE_CERRADO` | `QUARTER_CLOSED` | `TRIMESTRE_ABIERTO` | `QUARTER_OPEN` |
| `TRIMESTRE_CON_PENDIENTES` | `QUARTER_HAS_PENDING` | `PERIODO_INVALIDO` | `INVALID_PERIOD` |
| `RECONOCIMIENTO_NO_DISPONIBLE` | `RECOGNITION_UNAVAILABLE` | `FICHERO_DEMASIADO_GRANDE` | `FILE_TOO_LARGE` |
| `PETICION_DEMASIADO_GRANDE` | `REQUEST_TOO_LARGE` | `ERROR_INTERNO` | `INTERNAL_ERROR` |
| `CATEGORIA_NOT_FOUND` | `CATEGORY_NOT_FOUND` | `IMAGEN_NO_VALIDA` | `INVALID_IMAGE` |
| `IMAGEN_DEMASIADO_GRANDE` | `IMAGE_TOO_LARGE` | `ALMACEN_IMAGENES_NO_DISPONIBLE` | `IMAGE_STORAGE_UNAVAILABLE` |
| `MATERIAL_EN_USO` | `MATERIAL_IN_USE` | `EVENTO_NOT_FOUND` | `EVENT_NOT_FOUND` |
| `EVENTO_FINALIZADO` | `EVENT_FINISHED` | `STOCK_INSUFICIENTE` (`disponible`) | `INSUFFICIENT_STOCK` (`available`) |
| `FECHA_PASADA` | `DATE_IN_PAST` | `PWD_CHANGE_ONLY` (autoridad interna) | sin cambios |
| `RECURSO_NO_ENCONTRADO` (`/error`) | `RESOURCE_NOT_FOUND` | `METODO_NO_PERMITIDO` (`/error`) | `METHOD_NOT_ALLOWED` |
| `TIPO_NO_ADMITIDO` (`/error`) | `UNSUPPORTED_MEDIA_TYPE` | `ERROR_<estado>` (`/error`) | `ERROR_<estado>` sin cambios |

## Base de datos

Cada módulo añade una migración que renombra sus tablas y columnas al inglés (`snake_case`,
tablas en plural), actualiza los valores guardados de sus enums y reescribe sus `CHECK`, sin
perder datos. Versiones reservadas: `V28` inventory, `V29` timetracking, `V30` auth, `V31`
invoices, `V32` absences, `V33` notifications, `V34` events. Las migraciones anteriores no se
editan (principio II).

Consecuencia al desplegar: los tokens emitidos antes llevan los roles antiguos y dejan de valer;
cada persona vuelve a iniciar sesión una vez.

## Configuración y variables de entorno

Las claves de configuración (`application.yml`) y las variables de entorno siguen la misma regla.
Las que ya estaban en inglés (`DB_*`, `JWT_*`, `SUPABASE_*`, `ANTHROPIC_API_KEY`, `CORS_ALLOWED_ORIGINS`,
`OPENAPI_ENABLED`, `AUTH_ARGON2_*`, `AUTH_REFRESH_EXPIRATION_DAYS`, `INVOICES_CLAUDE_*`,
`INVOICES_MAX_REQUEST_SIZE`) no cambian. Un despliegue existente tiene que renombrar estas:

| Antes | Después |
|---|---|
| `AUTH_HASH_CONCURRENCIA` · `AUTH_HASH_ESPERA_MS` | `AUTH_HASH_CONCURRENCY` · `AUTH_HASH_WAIT_MS` |
| `AUTH_PURGA_SESIONES_DIAS` | `AUTH_SESSION_PURGE_DAYS` |
| `AUTH_EVENTOS_RETENCION_DIAS` | `AUTH_SECURITY_EVENTS_RETENTION_DAYS` |
| `AUTH_CODIGO_ARRANQUE` | `AUTH_BOOTSTRAP_CODE` |
| `TIMETRACKING_AVISO_SIN_SALIDA_HORAS` · `_CRON` | `TIMETRACKING_MISSING_CLOCK_OUT_HOURS` · `_CRON` |
| `TIMETRACKING_EXPORTACION_CONCURRENCIA` · `_ESPERA_MS` · `_TIMEOUT_SEGUNDOS` · `_TAMANO_LOTE` | `TIMETRACKING_EXPORT_CONCURRENCY` · `_WAIT_MS` · `_TIMEOUT_SECONDS` · `_BATCH_SIZE` |
| `TIMETRACKING_VERIFICACION_MAX_BYTES` | `TIMETRACKING_VERIFICATION_MAX_BYTES` |
| `TIMETRACKING_RETENCION_HABILITADA` (enlace relajado) | `TIMETRACKING_RETENTION_ENABLED` |
| `SEGURIDAD_LIMITE_LOGIN` · `_SESION` · `_REGISTRO` · `_GENERAL` · `_MAX_DIRECCIONES` | `SECURITY_RATE_LIMIT_LOGIN` · `_SESSION` · `_SIGN_UP` · `_GENERAL` · `_MAX_ADDRESSES` |
| `ABSENCES_DIAS_VACACIONES` | `ABSENCES_VACATION_ANNUAL_DAYS` |
| `NOTIFICATIONS_DIAS_LEIDAS` · `_DIAS_TODAS` · `_LIMPIEZA_CRON` | `NOTIFICATIONS_READ_DAYS` · `_ALL_DAYS` · `_CLEANUP_CRON` |
| `INVOICES_RECONOCIMIENTO_CONCURRENCIA` · `_TIMEOUT_SEGUNDOS` | `INVOICES_RECOGNITION_CONCURRENCY` · `_TIMEOUT_SECONDS` |
| `INVOICES_REINTENTO_CRON` · `INVOICES_REINTENTO_TRAS_MINUTOS` | `INVOICES_RETRY_CRON` · `INVOICES_RETRY_AFTER_MINUTES` |
| `INVOICES_MAX_BYTES_POR_FICHERO` · `INVOICES_PDF_MAX_PAGINAS` | `INVOICES_MAX_BYTES_PER_FILE` · `INVOICES_PDF_MAX_PAGES` |
| `INVOICES_REFERENCIA_DIR` (solo tests reales) | `INVOICES_REFERENCE_DIR` |
| `INVENTORY_IMAGENES_BUCKET` · `_URL_SEGUNDOS` · `_MAX_BYTES` · `_TIMEOUT_SEGUNDOS` | `INVENTORY_IMAGES_BUCKET` · `_URL_SECONDS` · `_MAX_BYTES` · `_TIMEOUT_SECONDS` |
| `EVENTS_FINALIZACION_CRON` | `EVENTS_FINISHING_CRON` |
| `OPENAPI_ACTUALIZAR` (regenerar `docs/openapi.json`) | `OPENAPI_UPDATE` |

Una variable antigua que quede en el entorno no da error: se ignora y se usa el valor por defecto.
El nombre del bucket por defecto sigue siendo `materiales`, porque es un recurso ya creado en Supabase.
