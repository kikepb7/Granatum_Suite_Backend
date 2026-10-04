# Phase 1 — Modelo de datos: 001-timetracking

Diseño de entidades, restricciones y plan de acceso. Las decisiones de fondo
están razonadas en [research.md](research.md).

---

## Visión general

```
                    ┌──────────────┐
                    │   Empleado   │  id = sujeto del JWT
                    └───────┬──────┘
                            │ 1:N
                   ┌────────┴─────────┐
                   │     Fichaje      │  proyección del estado actual
                   │ EN_CURSO/CERRADO │
                   │   /INCOMPLETO    │
                   └───┬──────────┬───┘
                 1:N   │          │   1:N
            ┌──────────┴──┐   ┌───┴─────────────────────────────┐
            │    Pausa    │   │ SolicitudCorreccionFichaje      │
            └─────────────┘   │ PENDIENTE/APROBADA/RECHAZADA    │
                              └─────────────────────────────────┘

            ┌──────────────────────────────────────────┐
            │            FichajeEvento                 │  append-only
            │  client_event_id único · occurred_at     │  prueba documental
            │  received_at · respuesta original        │  e idempotencia
            └──────────────────────────────────────────┘
```

`FichajeEvento` no tiene relación JPA con `Fichaje`: guarda su identificador
pero deliberadamente sin `@ManyToOne`, para que nada pueda cascadear un borrado
hacia la tabla inmutable.

---

## Convenciones heredadas y una mejora

**Heredado de `inventory`** (ver `docs/ARCHITECTURE.md`):

- `class` normal, **nunca `data class`**, para entidades JPA. El `data class`
  genera `equals`/`hashCode` sobre todos los campos, de modo que el hash de una
  entidad cambia al mutarla y se corrompe su pertenencia a cualquier `Set`; y su
  `copy()` produce duplicados desacoplados de una entidad gestionada.
- `id: UUID` asignado en construcción (`UUID.randomUUID()`), no generado por la
  base de datos.
- Auditoría con `@EntityListeners(AuditingEntityListener::class)` y
  `@CreatedDate` / `@LastModifiedDate`.
- Separación estricta entidad JPA ↔ modelo de dominio ↔ DTO, con mappers en
  cada frontera.

**Mejora respecto a `inventory`**: las entidades de este módulo **sí** definen
`equals`/`hashCode` por identidad. Las de `inventory` no los definen, así que
usan igualdad por referencia: dos instancias cargadas de la misma fila no son
iguales, y un proxy perezoso nunca es igual a su entidad. Hoy no les pasa nada
porque no se guardan en `Set` ni se comparan, pero es una trampa latente. Aquí
no se hereda:

```kotlin
override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is FichajeEntity) return false
    return id == other.id
}

override fun hashCode(): Int = javaClass.hashCode()

// toString NUNCA referencia colecciones perezosas
override fun toString(): String = "FichajeEntity(id=$id, estado=$estado)"
```

Como el `id` se asigna en construcción y nunca es nulo, la igualdad por
identificador es correcta desde antes de persistir — sin el estado intermedio
que obliga a tratar el `id` nulo cuando lo genera la base de datos.

`hashCode` devuelve una constante por clase a propósito: mantiene el contrato
`equals`/`hashCode` estable aunque la entidad mute, al coste de degradar los
`HashSet` de entidades a listas. Las colecciones de entidades aquí son pequeñas
(las pausas de una jornada), así que el coste es irrelevante.

---

## Entidades

### Empleado → `empleados`

| Campo | Tipo | Columna | Reglas |
|-------|------|---------|--------|
| `id` | `UUID` | `id` PK | **Es el sujeto del JWT** (ver research.md §7) |
| `nombre` | `String` | `nombre` | No nulo, 1–150 |
| `documentoIdentidad` | `String` | `documento_identidad` | No nulo, **único**, normalizado a mayúsculas sin espacios ni guiones, con dígito de control validado |
| `puesto` | `String` | `puesto` | No nulo, 1–100 |
| `tipoContrato` | `TipoContrato` | `tipo_contrato` | Enum `STRING`: `JORNADA_COMPLETA`, `PARCIAL`, `POR_HORAS` |
| `fechaAlta` | `LocalDate` | `fecha_alta` | No nulo. Fecha civil, no instante: un alta es un hecho administrativo de un día |
| `activo` | `Boolean` | `activo` | No nulo, por defecto `true` |
| `createdAt` / `updatedAt` | `Instant` | | Auditoría |

- FR-029 → índice único sobre `documento_identidad`.
- FR-030 → **no existe operación de borrado**. El repositorio no expone
  `delete`, y la baja es `activo = false`.
- `documentoIdentidad` es dato personal: no aparece en `toString` (principio VI).

### Fichaje → `fichajes`

| Campo | Tipo | Columna | Reglas |
|-------|------|---------|--------|
| `id` | `UUID` | `id` PK | |
| `empleado` | `EmpleadoEntity` | `empleado_id` FK | `@ManyToOne(LAZY)`, no nulo |
| `entrada` | `Instant` | `entrada` `TIMESTAMPTZ` | No nulo |
| `salida` | `Instant?` | `salida` `TIMESTAMPTZ` | Nulo mientras no se cierre |
| `ubicacionEntrada` | `Ubicacion?` | `@Embedded` | Opcional (FR-009) |
| `ubicacionSalida` | `Ubicacion?` | `@Embedded` | Opcional |
| `estado` | `EstadoFichaje` | `estado` | Enum `STRING`: `EN_CURSO`, `CERRADO`, `INCOMPLETO` |
| `minutosTrabajados` | `Int?` | `minutos_trabajados` | Se calcula al cerrar (FR-007). Nulo mientras `EN_CURSO` |
| `fueIncompleto` | `Boolean` | `fue_incompleto` | **No nulo, por defecto `false`.** Se pone a `true` al pasar a `INCOMPLETO` y **nunca vuelve atrás**, incluso tras completarse (FR-012c, SC-010) |
| `pausas` | `MutableList<PausaEntity>` | | `@OneToMany(mappedBy="fichaje", LAZY, cascade=ALL, orphanRemoval=true)` |
| `createdAt` / `updatedAt` | `Instant` | | Auditoría |

**`minutosTrabajados` como `Int` de minutos, no como intervalo ni decimal**: la
jornada se registra y se exporta al minuto, y un entero evita por completo los
errores de redondeo que aparecen al sumar horas decimales en el CSV de la
feature 003.

**`fueIncompleto` separado de `estado`**: cuando una corrección completa un
fichaje `INCOMPLETO`, su estado pasa a `CERRADO` — pero SC-010 exige que siga
siendo distinguible de uno cerrado en su momento. Un flag que solo avanza
conserva ese hecho sin inventar un cuarto estado que complicaría todas las
consultas.

### Pausa → `pausas`

| Campo | Tipo | Columna | Reglas |
|-------|------|---------|--------|
| `id` | `UUID` | `id` PK | |
| `fichaje` | `FichajeEntity` | `fichaje_id` FK | `@ManyToOne(LAZY)`, no nulo |
| `tipo` | `TipoPausa` | `tipo` | Enum `STRING`: `COMIDA`, `DESCANSO`, `OTRO` |
| `inicio` | `Instant` | `inicio` `TIMESTAMPTZ` | No nulo |
| `fin` | `Instant?` | `fin` `TIMESTAMPTZ` | Nulo mientras está abierta |

Relación bidireccional: los métodos de dominio mantienen **los dos lados**
(`fichaje.añadirPausa(p)` asigna `p.fichaje = this` y añade a la lista). Un
grafo medio actualizado produce fallos sutiles al sincronizar.

### SolicitudCorreccionFichaje → `solicitudes_correccion_fichaje`

| Campo | Tipo | Columna | Reglas |
|-------|------|---------|--------|
| `id` | `UUID` | `id` PK | |
| `fichaje` | `FichajeEntity` | `fichaje_id` FK | `@ManyToOne(LAZY)`, no nulo |
| `solicitanteId` | `UUID` | `solicitante_id` | No nulo |
| `motivo` | `String` | `motivo` | **No nulo**, 10–500 (FR-013) |
| `valoresPropuestos` | `String` | `valores_propuestos` `JSONB` | No nulo |
| `valoresOriginales` | `String?` | `valores_originales` `JSONB` | Se rellena **al aprobar**, con el estado del fichaje justo antes de aplicar (FR-017) |
| `estado` | `EstadoSolicitud` | `estado` | Enum `STRING`: `PENDIENTE`, `APROBADA`, `RECHAZADA` |
| `resueltaPorId` | `UUID?` | `resuelta_por_id` | FR-016 |
| `resueltaEn` | `Instant?` | `resuelta_en` `TIMESTAMPTZ` | FR-016 |
| `motivoResolucion` | `String?` | `motivo_resolucion` | Obligatorio al rechazar |
| `createdAt` | `Instant` | | |

**Propuesta y original como `JSONB`, no como tablas hijas**: ambos son
documentos inmutables que se escriben una vez y se leen enteros; nunca se
consultan campo a campo. Modelar las pausas propuestas como tabla hija exigiría
dos tablas más con su ciclo de vida, sin ningún beneficio de consulta. La
contrapartida —se pierde la comprobación de tipos del esquema— se cubre
validando la propuesta en el servicio antes de aceptarla (FR-020b).

Forma del documento:

```json
{
  "entrada": "2026-10-05T07:00:00Z",
  "salida": "2026-10-05T15:30:00Z",
  "pausas": [
    { "tipo": "COMIDA", "inicio": "2026-10-05T11:00:00Z", "fin": "2026-10-05T11:30:00Z" }
  ]
}
```

La **ubicación no aparece en el documento** y una propuesta que la incluya se
rechaza (FR-020a).

### FichajeEvento → `fichaje_eventos`

Tabla **append-only**: sin `UPDATE`, sin `DELETE`, sin `updatedAt`. Es a la vez
el mecanismo de idempotencia (FR-024) y la prueba documental del registro
(research.md §4).

| Campo | Tipo | Columna | Reglas |
|-------|------|---------|--------|
| `id` | `UUID` | `id` PK | |
| `clientEventId` | `UUID` | `client_event_id` | No nulo, **único** |
| `empleadoId` | `UUID` | `empleado_id` | No nulo. Sin FK a `empleados`: nada debe poder cascadear un borrado aquí |
| `fichajeId` | `UUID?` | `fichaje_id` | El fichaje afectado. Sin FK, por lo mismo |
| `tipoOperacion` | `TipoOperacionFichaje` | `tipo_operacion` | `ENTRADA`, `INICIO_PAUSA`, `FIN_PAUSA`, `SALIDA` |
| `occurredAt` | `Instant` | `occurred_at` `TIMESTAMPTZ` | Hora declarada por el dispositivo (FR-025) |
| `receivedAt` | `Instant` | `received_at` `TIMESTAMPTZ` | Hora de llegada al servidor (FR-025) |
| `huellaPeticion` | `String` | `huella_peticion` | SHA-256 del cuerpo canonicalizado |
| `estadoRespuesta` | `Int` | `estado_respuesta` | Código HTTP de la respuesta original |
| `cuerpoRespuesta` | `String` | `cuerpo_respuesta` `JSONB` | Respuesta original, devuelta tal cual en los reenvíos |

**Por qué se guarda la respuesta y no se recalcula**: FR-024 exige devolver *el
resultado original*. Devolver el estado actual del fichaje daría una respuesta
distinta si la jornada avanzó entre el envío y el reintento — justo el caso que
motiva la feature.

**Por qué la huella**: mismo `clientEventId` con cuerpo distinto significa que el
cliente reutilizó una clave para otra operación. Eso es un bug del cliente y se
responde `409 Conflict`, nunca en silencio. Sin la huella, ese cliente recibiría
la respuesta de otra operación y nadie se enteraría.

### DepuracionRetencion → `depuraciones_retencion`

Tabla **append-only** que anota cada ejecución del proceso de depuración
(FR-031c). **No se depura nunca**, y por eso no contiene ningún dato personal:
solo rangos de fecha y recuentos.

| Campo | Tipo | Columna | Reglas |
|-------|------|---------|--------|
| `id` | `UUID` | `id` PK | |
| `ejecutadaEn` | `Instant` | `ejecutada_en` `TIMESTAMPTZ` | No nulo |
| `fechaCorte` | `LocalDate` | `fecha_corte` | Fecha hasta la que se depuró (entrada del fichaje) |
| `fichajesEliminados` | `Int` | `fichajes_eliminados` | No nulo |
| `pausasEliminadas` | `Int` | `pausas_eliminadas` | No nulo |
| `eventosEliminados` | `Int` | `eventos_eliminados` | No nulo |
| `solicitudesEliminadas` | `Int` | `solicitudes_eliminadas` | No nulo |

**Por qué sin datos personales**: si el registro de depuración conservase
identificadores de empleado o fechas de jornada concretas, sería él mismo un
tratamiento de datos que sobrevive al plazo que la depuración viene a cumplir —
se anularía su propósito. Con recuentos y una fecha de corte basta para
demostrar ante una inspección qué se destruyó y cuándo.

### Ubicacion (`@Embeddable`)

| Campo | Tipo | Columna |
|-------|------|---------|
| `latitud` | `BigDecimal` | `..._latitud` `NUMERIC(9,6)` |
| `longitud` | `BigDecimal` | `..._longitud` `NUMERIC(9,6)` |
| `precisionMetros` | `Int?` | `..._precision_metros` |

Embebida dos veces en `fichajes` con prefijos `ubicacion_entrada_` y
`ubicacion_salida_` vía `@AttributeOverrides`. `NUMERIC(9,6)` da ~0,1 m de
resolución, de sobra, y evita el error de redondeo de los flotantes binarios.
La ubicación es dato personal: nunca a logs (principio VI).

---

## Restricciones que solo existen en SQL

**JPA no puede expresar índices únicos parciales**, así que estos van escritos a
mano en la migración Flyway. No basta con anotar las entidades:

```sql
-- FR-002: un solo fichaje EN_CURSO por empleado
CREATE UNIQUE INDEX uk_fichajes_empleado_en_curso
    ON fichajes (empleado_id) WHERE estado = 'EN_CURSO';

-- FR-004: una sola pausa abierta por fichaje
CREATE UNIQUE INDEX uk_pausas_fichaje_abierta
    ON pausas (fichaje_id) WHERE fin IS NULL;
```

Son la garantía real frente a la concurrencia: la comprobación en el servicio
solo sirve para dar un mensaje legible en el caso normal. Dos peticiones
simultáneas de entrada —el reintento de la app móvil— pasarían ambas la lectura
antes de que cualquiera escriba.

Resto de restricciones:

| Restricción | Dónde | Requisito |
|-------------|-------|-----------|
| `documento_identidad` único | Índice único | FR-029 |
| `client_event_id` único | Índice único | FR-024 |
| `salida > entrada` | `CHECK` | FR-011 |
| `fin > inicio` en pausas | `CHECK` | coherencia |
| `minutos_trabajados >= 0` | `CHECK` | FR-007 |
| `estado` en el conjunto válido | `CHECK` | evita que una escritura directa invente un estado |
| RLS activado en las 5 tablas | `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` | Principio VII |

Los `CHECK` duplican validaciones que ya están en el dominio **a propósito**:
son datos con valor legal y la base de datos es la última línea de defensa
frente a un bug de la aplicación o una corrección mal aplicada.

---

## Transiciones de estado

### Fichaje

```
            entrada
   (nada) ──────────► EN_CURSO
                         │
          salida         │        proceso diario (día anterior)
      ┌──────────────────┴──────────────────┐
      ▼                                     ▼
   CERRADO                            INCOMPLETO
   (minutosTrabajados calculado)      (fueIncompleto = true)
                                            │
                                            │ corrección APROBADA
                                            ▼
                                        CERRADO
                                   (fueIncompleto sigue true)
```

- `EN_CURSO → CERRADO` exige que no haya ninguna pausa abierta (FR-006).
- `EN_CURSO → INCOMPLETO` lo hace solo el proceso programado, y solo para
  fichajes cuya entrada sea de un día anterior (FR-012).
- `INCOMPLETO → CERRADO` solo por corrección aprobada (FR-012b).
- `CERRADO` es terminal salvo corrección aprobada (FR-018).
- **No existe transición a borrado** por ninguna vía de la aplicación dentro del
  plazo de conservación (FR-031a). Agotados los 4 años, el proceso de retención
  elimina la fila completa —no hay estado "depurado"— y deja constancia del
  recuento en `depuraciones_retencion` (FR-031b, FR-031c).

### SolicitudCorreccionFichaje

```
   (nada) ──► PENDIENTE ──► APROBADA   (terminal)
                       └──► RECHAZADA  (terminal)
```

Ambos finales son terminales: resolver dos veces se rechaza (FR-019). Se
implementa con una actualización condicionada al estado `PENDIENTE`, de modo que
dos aprobaciones simultáneas no puedan aplicarse las dos.

---

## Plan de acceso y fetch

### El N+1 que hay que evitar

`GET /api/fichajes/empleado/{id}?desde=&hasta=` devuelve N fichajes, cada uno
con sus pausas. Con `@OneToMany(LAZY)` y acceso ingenuo son 1 + N consultas: un
mes de jornadas de una persona son ~22 consultas extra, y para un responsable
que pide todo el personal se multiplica por la plantilla.

**Solución**: `@EntityGraph` sobre el método del repositorio.

```kotlin
@EntityGraph(attributePaths = ["pausas"])
fun findByEmpleadoIdAndEntradaBetweenOrderByEntradaDesc(
    empleadoId: UUID, desde: Instant, hasta: Instant
): List<FichajeEntity>
```

**Contrapartida que hay que conocer**: un fetch join de colección **no se puede
paginar en base de datos**. Si este listado llega a necesitar paginación,
Hibernate traería todas las filas a memoria para paginarlas ahí. La salida
entonces es consultar los identificadores paginados primero y cargar las pausas
en una segunda consulta. Hoy el rango está acotado por fechas, así que no se
pagina; queda anotado para cuando aparezca.

**Verificación, no suposición**: el N+1 se confirma contando las consultas
reales en los tests de integración, no leyendo las anotaciones.

### `open-in-view` está desactivado

`application.yml` fija `spring.jpa.open-in-view: false`. Consecuencia directa:
**todo el mapeo a DTO que toque una colección perezosa tiene que ocurrir dentro
de la transacción**, en el servicio. Si un mapper toca `fichaje.pausas` después
de que el servicio haya devuelto el control, salta `LazyInitializationException`.

Esto no es teórico en este proyecto: ya ocurrió con `MaterialEntity.fotos`, y se
arregló en el test, no poniendo la colección en `EAGER`. Mismo criterio aquí.

### Índices para las consultas reales

| Índice | Consulta que sirve |
|--------|--------------------|
| `(empleado_id, entrada DESC)` | listado por empleado y rango — el caso dominante |
| `(estado)` parcial `WHERE estado = 'EN_CURSO'` | el proceso diario busca fichajes abiertos; sin él recorrería toda la tabla cada noche |
| `(fichaje_id)` en `pausas` | el fetch join |
| `(fichaje_id, estado)` en solicitudes | correcciones pendientes de un fichaje |
| `(client_event_id)` único | la comprobación de idempotencia, en cada operación |

---

## Qué no se expone

Las entidades **no salen por HTTP** (principio VIII). Cada una tiene su modelo
de dominio y su DTO, con mappers en las dos fronteras, igual que en `inventory`.
En particular, un DTO de fichaje **nunca** incluye el documento de identidad
salvo donde la exportación lo exija (feature 003), y jamás incluye los campos de
auditoría internos.
