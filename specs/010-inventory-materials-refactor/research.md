# Research: Refactor de los materiales del inventario

## D-001: Supabase Storage por su API REST, con `RestClient`

**Decisión**: el backend habla con Supabase Storage por HTTP con el
`RestClient` de Spring, detrás del puerto `AlmacenImagenes`
(`guardar`, `borrar`, `firmar`). Tres llamadas:

| Operación | Llamada |
|---|---|
| Subir | `POST {SUPABASE_URL}/storage/v1/object/{bucket}/{ruta}` — cuerpo: los bytes; `Content-Type`: el tipo detectado; `x-upsert: false` |
| Borrar | `DELETE {SUPABASE_URL}/storage/v1/object/{bucket}` — cuerpo `{"prefixes": [ruta, …]}` |
| Firmar | `POST {SUPABASE_URL}/storage/v1/object/sign/{bucket}` — cuerpo `{"expiresIn": s, "paths": [ruta, …]}`; responde `[{path, signedURL, error}]` con `signedURL` relativo a `/storage/v1` |

Cabeceras: `Authorization: Bearer <clave>` y `apikey: <clave>`.

**Motivo**: no hay SDK Java oficial de Supabase; los no oficiales arrastran
dependencias para tres llamadas. `RestClient` ya está en el classpath y se
prueba con `MockWebServer`, que el proyecto ya usa (facturas).

**Descartado**: subida directa del cliente con URL firmada de subida. La
validación del fichero (tipo por firma y tamaño) y la autorización por rol
quedarían fuera del backend, y habría un segundo paso de confirmación con
estados intermedios. Elegido por el responsable del producto.

**Descartado**: `BYTEA` en Postgres como facturas (D-008 de la 004). El
responsable del producto pidió storage; además el plan gratuito de Supabase
tiene 500 MB de base de datos, que las facturas ya consumen.

## D-002: Clave de servicio y bucket privado

**Decisión**: el backend usa la clave `service_role` (`SUPABASE_SERVICE_ROLE_KEY`)
y un bucket **privado** (`INVENTORY_IMAGENES_BUCKET`, por defecto `materiales`),
creado una vez por entorno (docs/DESPLIEGUE.md). Las imágenes solo se ven con
URL firmada.

**Motivo**: la autorización vive en `SecurityConfig` (principio IV); Storage no
conoce los roles del producto. La clave de servicio salta las políticas de
`storage.objects`, igual que la conexión propietaria salta el RLS de las tablas
(principio VII): PostgREST/Storage quedan ciegos para la clave anónima.

**Riesgo**: la clave de servicio da acceso total al proyecto Supabase. Por eso
solo se lee de entorno, nunca se registra, y `toString()` de la configuración
no la incluye.

## D-003: Sin transacción común: orden de las operaciones

Storage y Postgres no comparten transacción. Orden elegido para que el fallo
deje, como mucho, un fichero huérfano (sin datos personales) y nunca un
material apuntando a un fichero inexistente:

- **Subir**: validar fichero → comprobar que el material existe → subir el
  objeto nuevo (ruta nueva) → transacción que apunta el material a la nueva
  ruta → borrar el objeto anterior. Si la transacción falla, se borra el nuevo
  (mejor esfuerzo). Si el borrado del anterior falla, se registra un aviso sin
  la ruta completa y la operación se da por buena.
- **Quitar imagen** y **borrar material**: transacción primero; borrado del
  objeto después, de mejor esfuerzo.

La subida a Storage ocurre **fuera** de cualquier transacción de base de datos,
para no retener una conexión del pool mientras se envían hasta 10 MB.

## D-004: El detector de tipo de fichero sube a `common`

**Decisión**: `TipoDocumento` y `DetectorTipoFichero` pasan de
`features/invoices` a `common` (`com.granatum.core.ficheros`), con su test.
Inventario acepta el subconjunto JPEG/PNG/WebP.

**Motivo**: principio I — lo que comparten dos features sube a `common`, no se
copia (mismo criterio que el CSV en la 004, D-011). El tipo se decide por la
firma de los bytes, nunca por el nombre ni el `Content-Type` del cliente.

## D-005: URL firmadas al leer, por lotes y tolerantes a fallos

**Decisión**: el servicio devuelve el material con `imagenUrl`. Para un listado
se firman todas las rutas en una sola llamada. Si Storage falla o no está
configurado, `imagenUrl` es `null` y se registra un aviso (sin rutas). Validez
por defecto 3600 s (`INVENTORY_IMAGENES_URL_SEGUNDOS`).

**Motivo**: FR-018 y FR-020; una llamada por material haría el listado lineal
en latencia de red.

## D-006: Localización: almacén enum, sección entero, validados en el borde

**Decisión**: `Almacen { A, B, C }` y sección `Int` 1–5, como `@Embeddable`
(`almacen VARCHAR(1)`, `seccion SMALLINT`) con `CHECK` en base de datos. En los
DTO el almacén llega como `String` con `@Pattern("[ABC]")` y la sección con
`@Min(1) @Max(5)`.

**Motivo**: con un enum en el DTO, un valor inválido rompe la deserialización
y el error sale como "cuerpo ilegible" sin nombrar el campo; con validación
Jakarta el `400 VALIDACION` nombra `localizacion.almacen` (spec US2-2). Los
`CHECK` protegen de cualquier otra vía de escritura. Los nombres son
provisionales: renombrar es cambiar el enum, el `CHECK` y la validación.

**Descartado**: tabla de localizaciones gestionable. La spec fija 15 valores
"por ahora"; una tabla es infraestructura para una necesidad que no existe.

## D-007: V26 transforma la tabla en lugar de recrearla

**Decisión**: V26 borra las filas de prueba (`historial_material`,
`material_fotos`, `materiales`), elimina `material_fotos`, y altera
`materiales`: quita columnas retiradas, renombra `cantidad_disponible` a
`cantidad`, añade `descripcion`, `almacen`, `seccion`, `imagen_ruta` y los
`CHECK`. Índice por `(almacen, seccion)`.

**Motivo**: alterar conserva la FK de `historial_material` y el RLS ya activo
en `materiales` sin redeclararlos. Borrar los datos es posible porque no hay
datos reales (spec, FR-022) y necesario porque las columnas nuevas son `NOT
NULL` sin valor razonable para las filas viejas. El historial vacía solo filas
de prueba de materiales que dejan de existir; la aplicación sigue sin poder
modificarlo (principio III).

## D-008: Una sola cantidad, solo por `PATCH` con motivo

**Decisión**: `cantidad_disponible`/`cantidad_total` pasan a `cantidad`. Se fija
al crear y después solo cambia con `PATCH /api/materiales/{id}/cantidad`
(`{cantidad, motivo}`), que escribe en el historial. `PUT` no la incluye.

**Motivo**: conserva la razón documentada en el principio IX (por qué la
cantidad solo cambia con motivo) con el nuevo modelo.

## D-009: Ruta del objeto

**Decisión**: `materiales/{materialId}/{uuid}.{ext}`, un UUID nuevo por subida.

**Motivo**: una ruta nueva por versión evita que cachés o URL firmadas ya
emitidas muestren la imagen nueva con la ruta vieja o al revés, y permite el
orden de D-003 (subir antes de soltar la anterior). Solo contiene UUID: nada
personal (principio VI).

## D-010: Sin Storage configurado

**Decisión**: con `SUPABASE_URL` o la clave vacías se registra
`AlmacenImagenesDeshabilitado` y un aviso al arrancar (como el modo manual de
facturas, D-006 de la 004). Subir responde `503 ALMACEN_IMAGENES_NO_DISPONIBLE`;
quitar imagen y borrar material funcionan (el borrado del objeto se omite);
las lecturas devuelven `imagenUrl: null`.

## D-011: Códigos de error

| Código | HTTP | Cuándo |
|---|---|---|
| `IMAGEN_NO_VALIDA` | 415 | El fichero no es JPEG, PNG ni WebP por su firma, o está vacío |
| `IMAGEN_DEMASIADO_GRANDE` | 413 | Más de 10 MB (`INVENTORY_IMAGENES_MAX_BYTES`) |
| `ALMACEN_IMAGENES_NO_DISPONIBLE` | 503 | Storage sin configurar o fallo al subir |
| `MATERIAL_NOT_FOUND` / `CATEGORIA_NOT_FOUND` | 404 | Como hoy |
| `INVALID_OPERATION` | 400 | Cantidad negativa en el servicio (como hoy) |
| `VALIDACION` | 400 | Validación del borde (campos o parámetros) |
