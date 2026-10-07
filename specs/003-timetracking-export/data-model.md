# Data Model: Exportación del registro de jornada

**Fecha**: 2026-10-06 | **Spec**: [spec.md](./spec.md) | **Decisiones**: [research.md](./research.md)

Una tabla nueva y una columna nueva, ambas en `timetracking`. Migraciones **V15**
y **V16**, siguiendo la numeración global (`inventory` V1–V5, `timetracking`
V6–V11, `auth` V12–V14).

El fichero exportado **no se almacena**: se genera a partir de los fichajes, las
pausas, las correcciones aprobadas y la ficha de la persona.

---

## `exportaciones` (V15) — nueva

**SOLO INSERCIÓN.** Una fila por cada exportación o descarga mensual, completa o
interrumpida (FR-025, D-003).

| Columna | Tipo | Nulo | Regla |
|---------|------|------|-------|
| `id` | `UUID` | no | PK. |
| `solicitante_id` | `UUID` | no | Id de la persona que exportó: el sujeto de su token. Sin clave ajena, igual que `fichaje_eventos`: nada debe poder propagar un borrado a una tabla inmutable. |
| `rol_solicitante` | `VARCHAR(20)` | no | `CHECK` sobre los cuatro roles. Dice qué versión del fichero recibió: con o sin documento (D-012). |
| `alcance` | `VARCHAR(10)` | no | `CHECK (alcance IN ('PERSONA', 'PLANTILLA', 'MENSUAL'))`. |
| `empleado_id` | `UUID` | sí | Persona exportada. Nulo si y solo si `alcance = 'PLANTILLA'`. |
| `desde` | `DATE` | no | Inicio del rango **efectivo**, ya recortado por el plazo de conservación (D-009). En `MENSUAL`, el día 1. |
| `hasta` | `DATE` | no | Fin del rango. En `MENSUAL`, el último día del mes. `CHECK (hasta >= desde)`. |
| `generada_en` | `TIMESTAMPTZ` | no | |
| `completada` | `BOOLEAN` | no | `false` si el cliente cortó o venció el tiempo (D-003). |
| `filas` | `INTEGER` | no | Filas de datos escritas, sin contar la cabecera ni el resumen final. En una interrumpida, las escritas hasta el corte. `CHECK (filas >= 0)`. |
| `huella` | `VARCHAR(64)` | sí | SHA-256 en hexadecimal de los bytes exactos enviados (D-006). Nula si y solo si `completada = false`. `CHECK (huella IS NULL OR char_length(huella) = 64)`. |

**Restricciones de coherencia**:

- `CHECK ((alcance = 'PLANTILLA') = (empleado_id IS NULL))`
- `CHECK (completada = (huella IS NOT NULL))`

**Índices**:

- `idx_exportaciones_huella (huella)`: la verificación (FR-028) busca por huella.
- `idx_exportaciones_empleado (empleado_id, generada_en)`: la consulta de FR-029.
- `idx_exportaciones_hasta (hasta)`: la depuración borra por fin de rango.

`ENABLE ROW LEVEL SECURITY` en la misma migración (principio VII). Nunca `FORCE`.

**Lo que no contiene, a propósito** (FR-026): ni horas, ni nombres, ni documentos,
ni el fichero. Solo identificadores, el rango, el recuento y la huella.

**Huella no única**: dos exportaciones idénticas dan la misma huella (FR-011). No
hay `UNIQUE`; la verificación devuelve todas las coincidencias.

**Conservación**: la depuración borra las filas cuyo `hasta` es anterior al corte,
cuando ya no queda ningún registro al que se refieran (D-013).

### Repositorio

`ExportacionRepository : Repository<ExportacionEntity, UUID>` con `save`, la
búsqueda por huella y la consulta filtrada de FR-029. **Sin** `delete`: el único
borrado posible es el de `RetencionPurgaRepository`, acotado por el corte.

---

## `depuraciones_retencion.exportaciones_eliminadas` (V16) — columna nueva

| Columna | Tipo | Nulo | Regla |
|---------|------|------|-------|
| `exportaciones_eliminadas` | `INTEGER` | no | `DEFAULT 0`, `CHECK (exportaciones_eliminadas >= 0)`. |

**Una migración nueva y no una edición de V11**, porque V11 ya está aplicada en
`main` y el principio II prohíbe editar migraciones aplicadas. El `DEFAULT 0` hace
que las depuraciones anteriores sigan diciendo la verdad: no borraron ninguna
exportación porque la tabla no existía.

---

## Lo que esta feature lee sin cambiarlo

| Tabla | Para qué |
|---|---|
| `empleados` | Nombre, documento (salvo `REPRESENTANTE`), puesto y tipo de contrato; nombres de quien solicitó y aprobó cada corrección. |
| `fichajes` | Entrada, salida, estado, minutos, `fue_incompleto`. |
| `pausas` | Tipo, inicio y fin. |
| `solicitudes_correccion_fichaje` | Solo las `APROBADA`: solicitante, resolutor, instante de resolución y `valores_originales` de la primera (D-010). |

Exportar no escribe en ninguna de ellas (FR-018). La única escritura de la
feature es la fila de `exportaciones`, después de leer y en su propia transacción
(D-003).

---

## Modelo de dominio (sin JPA, sin HTTP)

```
FilaRegistro(persona, documento?, puesto, fecha, entrada, salida?, pausas,
             minutosTrabajados?, estado, completadoAPosteriori, corregido,
             original: ValoresOriginales?, correcciones: List<CorreccionAplicada>)

CorreccionAplicada(resueltaEn, solicitante: String, aprobador: String)
ValoresOriginales(entrada, salida?, pausas)

AlcanceExportacion = Persona(empleadoId) | Plantilla | Mensual(empleadoId, anio, mes)

RangoEfectivo(desde, hasta, recortado: Boolean)   // D-009
```

`EscritorCsv` es un objeto puro: recibe filas y escribe bytes. Se ocupa del BOM,
el separador, el entrecomillado, la protección contra fórmulas y el formato de
fechas y horas, y no sabe nada de JPA ni de HTTP. Es la pieza con tests
unitarios del principio V.
