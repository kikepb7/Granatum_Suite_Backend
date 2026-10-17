# Feature Specification: Refactor de los materiales del inventario

**Feature Branch**: `inventory-refactor-feature`

**Created**: 2026-10-17

**Status**: Draft

**Input**: User description: "Hagamos un refactor de los productos del inventario. Los datos de los productos deben ser los siguientes: Categoría, Descripción, Dimensiones (alto y ancho), cantidad, imagen, Localización, fecha de Alta y fecha de modificación. La Localización se va a dividir en 3 almacenes (A, B y C) y cada almacén dispone de varias secciones (1, 2, 3, 4 y 5). Por ahora llamaremos A, B, C y 1, 2, 3, 4, 5. La imagen debe poder subirse directamente al storage."

## Contexto

El inventario actual describe cada material con muchos atributos que el
negocio no usa (nombre, color, material físico, estado, precio unitario,
proveedor, diámetro, unidad de medida, cantidad disponible frente a total),
guarda su ubicación como texto libre y sus fotos como una lista de URL que
alguien tiene que haber subido antes a otro sitio.

El responsable del producto lo reduce a lo que de verdad se gestiona. Cada
material (el "producto" del inventario) tiene **exactamente**:

- **Categoría** (las categorías existentes, sin cambios).
- **Descripción**.
- **Dimensiones**: alto y ancho.
- **Cantidad**.
- **Imagen**: una, opcional, que se sube desde la propia aplicación.
- **Localización**: un almacén y una sección de ese almacén.
- **Fecha de alta** y **fecha de modificación**.

Se conserva el nombre "material" y las rutas actuales del inventario para no
romper a los clientes más de lo imprescindible: cambia el contenido, no la
dirección.

**No hay datos reales que conservar**: los materiales existentes son de prueba
y pueden descartarse al aplicar el nuevo modelo.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Dar de alta y editar un material con el nuevo modelo (Priority: P1)

Quien gestiona el inventario (`ADMIN` o `ENCARGADO`) da de alta un material
indicando su categoría, su descripción, su alto y su ancho, su cantidad inicial
y su localización (almacén y sección). El sistema le asigna la fecha de alta y
la de modificación. Después puede corregir la categoría, la descripción, las
dimensiones o la localización, y la fecha de modificación se actualiza.

**Why this priority**: sin el nuevo modelo no hay inventario que gestionar; es
el núcleo del refactor y lo que todo lo demás presupone.

**Independent Test**: Crear un material con todos los campos, leerlo y
comprobar que devuelve exactamente esos datos más las dos fechas; editarlo y
comprobar que la fecha de modificación avanza y la de alta no.

**Acceptance Scenarios**:

1. **Given** una categoría existente, **When** se da de alta un material con descripción, alto, ancho, cantidad, almacén y sección válidos, **Then** queda creado con fecha de alta y fecha de modificación iguales al instante del alta, y sin imagen.
2. **Given** un material existente, **When** se cambia su descripción, dimensiones, categoría o localización, **Then** los cambios se guardan, la fecha de modificación se actualiza y la fecha de alta no cambia.
3. **Given** cualquier material, **When** se consulta, **Then** la respuesta contiene la categoría, la descripción, el alto, el ancho, la cantidad, la localización (almacén y sección), la imagen si la tiene, y las dos fechas, y ninguno de los atributos eliminados.
4. **Given** una categoría inexistente, **When** se da de alta o se edita un material con ella, **Then** se rechaza indicando que la categoría no existe y no se guarda nada.
5. **Given** un material, **When** se borra, **Then** deja de aparecer en el inventario.

---

### User Story 2 - Localización por almacén y sección (Priority: P1)

Cada material está en un único sitio: uno de los tres almacenes (A, B o C) y
una de sus cinco secciones (1 a 5). El sistema solo acepta esas combinaciones,
para que no vuelvan a aparecer ubicaciones escritas de cien maneras distintas.

**Why this priority**: la localización estructurada es la razón principal del
refactor; con texto libre no se puede saber qué hay en cada sitio.

**Independent Test**: Dar de alta materiales en distintas localizaciones,
intentar una localización inexistente y comprobar que se rechaza.

**Acceptance Scenarios**:

1. **Given** el almacén B y la sección 4, **When** se da de alta un material allí, **Then** el material queda localizado en B-4.
2. **Given** un almacén distinto de A, B o C, o una sección distinta de 1 a 5, **When** se da de alta o se edita un material con ella, **Then** se rechaza indicando qué valor no es válido y no se guarda nada.
3. **Given** que falta el almacén o la sección, **When** se da de alta un material, **Then** se rechaza: la localización es obligatoria y completa.

---

### User Story 3 - Subir la imagen del material (Priority: P2)

Quien gestiona el inventario sube una foto del material desde la aplicación. La
foto se guarda en el almacenamiento de ficheros privado del producto, no en
otro servicio externo ni pegando un enlace. Al consultar el material, la
aplicación recibe un enlace temporal para mostrar la imagen. Subir otra foto
sustituye a la anterior, y la foto también se puede quitar.

**Why this priority**: la imagen ayuda a reconocer el material, pero el
inventario es útil sin ella; depende de que exista el material (US1).

**Independent Test**: Subir una imagen a un material, consultarlo y abrir el
enlace devuelto; subir otra y comprobar que el enlace muestra la nueva y la
anterior ya no se conserva; quitarla y comprobar que el material queda sin
imagen.

**Acceptance Scenarios**:

1. **Given** un material sin imagen, **When** se sube una foto JPEG, PNG o WebP de hasta 10 MB, **Then** el material queda con imagen y su fecha de modificación se actualiza.
2. **Given** un material con imagen, **When** se consulta (individualmente o en el listado), **Then** la respuesta incluye un enlace a la imagen que funciona durante un tiempo limitado y después deja de funcionar.
3. **Given** un material con imagen, **When** se sube otra, **Then** la nueva sustituye a la anterior y la anterior se elimina del almacenamiento.
4. **Given** un material con imagen, **When** se quita la imagen, **Then** el material queda sin imagen y el fichero se elimina del almacenamiento.
5. **Given** un fichero que no es una imagen admitida (aunque su nombre o su tipo declarado digan lo contrario), o que supera 10 MB, **When** se intenta subir, **Then** se rechaza con un motivo claro y el material no cambia.
6. **Given** un material con imagen, **When** se borra el material, **Then** su imagen también se elimina del almacenamiento.
7. **Given** una instalación sin el almacenamiento de imágenes configurado, **When** se intenta subir una imagen, **Then** se rechaza indicando que la subida de imágenes no está disponible, y el resto del inventario funciona con normalidad.

---

### User Story 4 - Cambiar la cantidad con motivo (Priority: P2)

La cantidad de un material solo cambia por una operación específica que exige
un motivo, y cada cambio queda en el historial del material (quién, cuándo, de
cuánto a cuánto y por qué), igual que hoy ocurre con la cantidad disponible.
Editar el resto del material no toca la cantidad.

**Why this priority**: conserva la trazabilidad de existencias que el producto
ya garantiza (el historial de material es inmutable por constitución); pasar de
dos cantidades a una no debe perderla.

**Independent Test**: Cambiar la cantidad con motivo y comprobar el historial;
intentar cambiarla sin motivo o editando el material y comprobar que no cambia.

**Acceptance Scenarios**:

1. **Given** un material con cantidad 10, **When** se cambia a 7 con un motivo, **Then** la cantidad pasa a 7 y el historial registra 10 → 7, el motivo, quién lo hizo y cuándo.
2. **Given** un material, **When** se intenta cambiar la cantidad sin motivo o a un número negativo, **Then** se rechaza y la cantidad no cambia.
3. **Given** un material, **When** se edita su descripción o su localización, **Then** su cantidad no cambia aunque la petición traiga otro valor.

---

### User Story 5 - Ver qué hay en un almacén o sección (Priority: P3)

Quien gestiona el inventario lista los materiales de un almacén, o de una
sección concreta de un almacén.

**Why this priority**: es lo primero que se hace con una localización
estructurada, pero el listado completo ya permite trabajar.

**Independent Test**: Crear materiales en varias localizaciones y filtrar por
almacén y por almacén y sección.

**Acceptance Scenarios**:

1. **Given** materiales en A-1, A-3 y C-2, **When** se listan los del almacén A, **Then** aparecen solo los de A-1 y A-3.
2. **Given** los mismos materiales, **When** se listan los de A-3, **Then** aparece solo el de A-3.
3. **Given** un filtro de sección sin almacén, o un almacén o sección inexistentes, **When** se lista, **Then** se rechaza indicando el valor no válido.

---

### Edge Cases

- **Alto o ancho cero o negativos**: se rechazan; las dimensiones de un objeto físico son positivas.
- **Descripción vacía o solo espacios**: se rechaza; la descripción es lo que identifica al material.
- **Cantidad inicial cero**: se admite (material catalogado sin existencias). Negativa: se rechaza.
- **Subir una imagen a un material inexistente**: se rechaza como material no encontrado y no se guarda ningún fichero.
- **Quitar la imagen de un material que no tiene**: no es un error; el material sigue sin imagen.
- **Fallo del almacenamiento al subir**: la subida se rechaza con un error de servicio no disponible y el material conserva la imagen que tuviera.
- **Fallo del almacenamiento al consultar**: el material se devuelve igualmente, sin enlace de imagen, en lugar de fallar la consulta entera.
- **Fallo al eliminar la imagen anterior tras sustituirla**: el material queda con la nueva; el fichero huérfano no bloquea la operación (y no es un dato personal).
- **Borrar un material con historial de cantidad**: el historial es inmutable y no se borra; el comportamiento frente a ese caso no cambia respecto al actual.
- **`EMPLEADO` o `REPRESENTANTE`**: no acceden a ninguna operación del inventario, tampoco a las imágenes.

## Requirements *(mandatory)*

### Functional Requirements

**Modelo del material**

- **FR-001**: Un material DEBE tener exactamente: categoría, descripción, alto, ancho, cantidad, imagen (opcional), localización (almacén y sección), fecha de alta y fecha de modificación.
- **FR-002**: El sistema DEBE dejar de aceptar, guardar y devolver nombre, cantidad disponible y total separadas, color, material físico, estado, precio unitario, proveedor, diámetro, unidad de medida y la lista de fotos por enlace.
- **FR-003**: La categoría DEBE ser una de las categorías existentes; la gestión de categorías no cambia.
- **FR-004**: La descripción DEBE ser obligatoria, no vacía y de hasta 500 caracteres.
- **FR-005**: El alto y el ancho DEBEN ser obligatorios, mayores que cero, expresados en centímetros con hasta dos decimales.
- **FR-006**: La cantidad DEBE ser un número entero mayor o igual que cero.
- **FR-007**: La fecha de alta DEBE fijarse al crear el material y no cambiar nunca; la fecha de modificación DEBE actualizarse con cualquier cambio del material, incluida su imagen y su cantidad.

**Localización**

- **FR-008**: La localización DEBE componerse de un almacén entre A, B y C y una sección entre 1, 2, 3, 4 y 5, ambos obligatorios. Toda sección existe en todos los almacenes (15 localizaciones).
- **FR-009**: El sistema DEBE rechazar cualquier otro valor de almacén o sección indicando cuál no es válido.
- **FR-010**: El sistema DEBE permitir listar los materiales filtrando por almacén, o por almacén y sección.

**Cantidad**

- **FR-011**: La cantidad DEBE fijarse al crear el material y, después, cambiar solo mediante la operación de cambio de cantidad, que exige un motivo.
- **FR-012**: Cada cambio de cantidad DEBE registrarse en el historial inmutable del material con el valor anterior, el nuevo, el motivo, la persona (la de la sesión, nunca una indicada en la petición) y el instante.

**Imagen**

- **FR-013**: Cada material DEBE poder tener como mucho una imagen, que se sube a través del backend al almacenamiento de ficheros privado del producto.
- **FR-014**: El sistema DEBE admitir solo JPEG, PNG y WebP, identificados por el contenido del fichero y no por su nombre ni por el tipo que declare el cliente, y de hasta 10 MB.
- **FR-015**: Subir una imagen a un material que ya tiene una DEBE sustituirla y eliminar la anterior del almacenamiento.
- **FR-016**: El sistema DEBE permitir quitar la imagen de un material, eliminándola del almacenamiento.
- **FR-017**: Borrar un material DEBE eliminar también su imagen del almacenamiento.
- **FR-018**: Al consultar materiales, el sistema DEBE devolver para cada imagen un enlace temporal, válido durante un tiempo limitado y configurable; los ficheros NO DEBEN ser accesibles sin ese enlace.
- **FR-019**: Si el almacenamiento de imágenes no está configurado, la aplicación DEBE arrancar y el inventario funcionar, rechazando solo las subidas con un error estable que lo indique.
- **FR-020**: Un fallo del almacenamiento al consultar NO DEBE hacer fallar la consulta: el material se devuelve sin enlace de imagen.

**Acceso y datos existentes**

- **FR-021**: Solo `ADMIN` y `ENCARGADO` DEBEN poder usar cualquier operación del inventario, incluidas las de imagen.
- **FR-022**: Al aplicar el nuevo modelo, los materiales de prueba existentes (y sus fotos e historial) PUEDEN descartarse; no se exige conversión.
- **FR-023**: Los errores DEBEN devolverse con un código estable y un mensaje legible, como el resto de la API.

### Key Entities

- **Material**: un artículo del inventario. Categoría, descripción, alto y ancho en centímetros, cantidad, localización, referencia a su imagen (si tiene) y fechas de alta y modificación.
- **Localización**: par almacén (A, B, C) y sección (1–5). No es una entidad gestionable: los valores son fijos por ahora.
- **Imagen del material**: el fichero subido, guardado en el almacenamiento privado; el material conserva solo dónde está. Se entrega mediante enlaces temporales.
- **Categoría**: sin cambios.
- **Historial de material**: sin cambios de estructura; registra los cambios de cantidad.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un material se da de alta indicando 7 datos (categoría, descripción, alto, ancho, cantidad, almacén, sección), frente a los 14 actuales.
- **SC-002**: El 100 % de los materiales tiene una localización entre las 15 válidas; ninguna localización inválida llega a guardarse.
- **SC-003**: Una foto de móvil típica (hasta 10 MB) se sube y queda visible en el material en menos de 5 segundos con una conexión normal.
- **SC-004**: Ningún enlace de imagen permite ver la foto pasado su tiempo de validez, y ninguna imagen es accesible sin enlace.
- **SC-005**: Tras sustituir, quitar la imagen o borrar el material, no queda en el almacenamiento ninguna imagen de ese material salvo la vigente.
- **SC-006**: Con el almacenamiento caído o sin configurar, el 100 % de las consultas del inventario siguen respondiendo.

## Assumptions

- Las dimensiones se expresan siempre en centímetros: el negocio no mezcla unidades y una unidad fija evita conversiones. Si hiciera falta otra, sería una feature nueva.
- Los nombres A/B/C y 1–5 son provisionales ("por ahora"); el modelo los trata como valores fijos y renombrarlos más adelante será un cambio acotado.
- "Directamente al storage" se interpreta como que la imagen se sube desde la aplicación al almacenamiento de ficheros del producto (Supabase Storage, bucket privado) pasando por el backend, que valida el fichero y la autorización; el cliente no necesita credenciales del almacenamiento.
- El enlace temporal vale 1 hora por defecto, configurable por entorno.
- El límite de 10 MB y los formatos admitidos coinciden con los de las fotos de facturas (004) por coherencia; HEIC no se admite (un iPhone comparte JPEG por defecto).
- La localización no se registra en el historial: el historial de material sigue registrando solo cambios de cantidad, como hoy.
- Se mantienen el nombre "material" y las rutas existentes del inventario; las peticiones y respuestas cambian de forma incompatible, lo cual es aceptable porque no hay clientes en producción que dependan del modelo anterior.
