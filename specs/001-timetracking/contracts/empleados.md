# Contrato: empleados

Convenciones transversales en [`README.md`](README.md).

**Todo este recurso es exclusivo de `ADMIN`** (FR-027). Un `ENCARGADO` gestiona
inventario y aprueba correcciones, pero no personal — principio IV de la
constitución. Cualquier otro rol recibe `403`.

**No existe `DELETE`** (FR-030). La baja es `activo = false`. Es una decisión de
diseño del contrato, no un olvido: borrar a una persona destruiría su histórico
de jornada, que debe conservarse 4 años (FR-031).

---

## `POST /api/empleados`

Da de alta. **FR-027, FR-028, FR-029.**

**Petición**

```json
{
  "nombre": "María López Ruiz",
  "documentoIdentidad": "12345678Z",
  "puesto": "Florista",
  "tipoContrato": "JORNADA_COMPLETA",
  "fechaAlta": "2026-10-01"
}
```

| Campo | Reglas |
|-------|--------|
| `nombre` | Obligatorio, 1–150 |
| `documentoIdentidad` | Obligatorio, único. DNI o NIE con **dígito de control válido**. Se normaliza a mayúsculas sin espacios ni guiones antes de comprobar la unicidad: sin normalizar, `12345678z` y `12345678-Z` serían dos personas distintas y FR-029 se burlaría con un guion |
| `puesto` | Obligatorio, 1–100 |
| `tipoContrato` | `JORNADA_COMPLETA` \| `PARCIAL` \| `POR_HORAS` |
| `fechaAlta` | Obligatoria, fecha civil `YYYY-MM-DD`. No es un instante: un alta es un hecho administrativo de un día |

**`201 Created`**

```json
{
  "id": "7c2e...",
  "nombre": "María López Ruiz",
  "documentoIdentidad": "12345678Z",
  "puesto": "Florista",
  "tipoContrato": "JORNADA_COMPLETA",
  "fechaAlta": "2026-10-01",
  "activo": true
}
```

> `id` es el identificador con el que esa persona se autenticará: **es el sujeto
> de su JWT** (research.md §7). Por eso la creación del empleado y la de sus
> credenciales están acopladas, y el login real (feature 002) construye sobre
> esta entidad.

**Errores propios**: `409 DOCUMENTO_DUPLICADO`, `400 VALIDATION_ERROR` (dígito
de control inválido, campos fuera de rango), `403 FORBIDDEN`.

El mensaje de un error de validación **referencia el campo, nunca su valor**: el
documento de identidad no puede aparecer en una respuesta de error ni en un log
(principio VI).

---

## `GET /api/empleados`

Lista el personal. Admite `?activo=true|false` para filtrar; omitido, devuelve
todos.

**`200 OK`**: lista de empleados. Lista vacía si no hay ninguno, no error.

---

## `GET /api/empleados/{id}`

Devuelve uno. **`404 EMPLEADO_NOT_FOUND`** si no existe.

---

## `PUT /api/empleados/{id}`

Modifica los datos laborales. **FR-027.**

**Petición**: mismos campos que el alta, salvo que `documentoIdentidad`
**no es modificable** — corregir el documento de una persona ya registrada es un
caso excepcional que merece su propia operación auditada, no un `PUT` genérico.
Si llega en el cuerpo, **se rechaza con `422 VALIDATION_ERROR`**; no se ignora
en silencio. Ignorarlo devolvería `200` a un cliente convencido de haber
cambiado el dato, y la divergencia no se descubriría hasta mucho después.

`activo` tampoco se cambia aquí: tiene su propio endpoint, para que una baja sea
una acción explícita y no el efecto colateral de una edición de datos.

**`200 OK`**: el empleado actualizado.

**Errores propios**: `404 EMPLEADO_NOT_FOUND`, `400 VALIDATION_ERROR`,
`403 FORBIDDEN`.

---

## `PATCH /api/empleados/{id}/activo`

Alta o baja efectiva. **FR-030.**

**Petición**

```json
{ "activo": false }
```

**`200 OK`**: el empleado con su nuevo estado.

Efecto de `activo = false`: la persona **no puede fichar** (FR-010,
`409 EMPLEADO_INACTIVO`), y su histórico de jornadas **permanece íntegro y
consultable** (FR-030).

> Qué pasa con un fichaje `EN_CURSO` al dar de baja a alguien no está resuelto
> en la especificación. Opciones razonables: rechazar la baja mientras tenga una
> jornada abierta, o dejar que el proceso diario lo marque `INCOMPLETO`. Se
> señala como hueco a decidir en implementación; la segunda es más coherente con
> FR-012, porque no bloquea una gestión administrativa por un olvido de la
> persona.

**Errores propios**: `404 EMPLEADO_NOT_FOUND`, `403 FORBIDDEN`.
