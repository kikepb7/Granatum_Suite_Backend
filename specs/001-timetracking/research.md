# Phase 0 — Investigación: 001-timetracking

Decisiones técnicas tomadas antes de diseñar, con su alternativa descartada.
Todo lo verificable se verificó contra el entorno real, no de memoria.

---

## 1. Autoconfiguración de tareas programadas en Spring Boot 4

**Decisión**: usar `@EnableScheduling` en el módulo `app` y `@Scheduled` en un
componente de `timetracking`. Sin dependencias nuevas.

**Rationale**: comprobado empíricamente en el jar de
`spring-boot-autoconfigure:4.0.0-SNAPSHOT`:
`org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration`
sigue dentro del jar **y** registrada en
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
No se ha movido a un módulo propio.

**Por qué se comprobó**: Boot 4 partió la autoconfiguración en módulos por
tecnología y eso ya rompió este proyecto una vez — `flyway-core` en el
classpath no bastaba y hubo que añadir `spring-boot-flyway`, con las migraciones
silenciosamente sin ejecutar y `ddl-auto: validate` fallando después. Asumir que
scheduling sí está habría sido repetir el mismo error.

**Alternativas descartadas**: añadir una dependencia `spring-boot-scheduling`
(no existe); un planificador externo tipo Quartz (infraestructura innecesaria,
contraria a la cláusula de simplicidad deliberada de la constitución).

---

## 2. "Un solo fichaje EN_CURSO por empleado" (FR-002)

**Decisión**: índice único parcial en base de datos:

```sql
CREATE UNIQUE INDEX uk_fichajes_empleado_en_curso
    ON fichajes (empleado_id)
 WHERE estado = 'EN_CURSO';
```

Más la comprobación en el servicio para devolver un error de dominio legible.

**Rationale**: la comprobación solo en código es vulnerable a una condición de
carrera — dos peticiones simultáneas de entrada (el caso real: la app móvil
reintenta porque cree que falló) pasarían ambas la lectura antes de que
cualquiera escriba. El índice parcial lo hace imposible a nivel de motor, con
coste cero de mantenimiento. La comprobación en servicio queda para dar un
mensaje útil en el caso normal, no como garantía.

**Alternativas descartadas**: solo validación en servicio (no es a prueba de
concurrencia); bloqueo pesimista sobre la fila del empleado (serializa todos
los fichajes de esa persona y no aporta nada que el índice no dé).

---

## 3. Pausas que no se solapan (FR-004, FR-020b)

**Decisión**: dos mecanismos, cada uno para su caso.

- **Pausa abierta duplicada** (caso en vivo, FR-004): índice único parcial.
  ```sql
  CREATE UNIQUE INDEX uk_pausas_fichaje_abierta
      ON pausas (fichaje_id)
   WHERE fin IS NULL;
  ```
- **Solapamiento entre pausas cerradas** (solo posible al aprobar una
  corrección que añade o mueve pausas, FR-020b): validación en el servicio al
  aprobar.

**Rationale**: el índice parcial cubre sin coste el único camino por el que un
solapamiento puede entrar de forma concurrente. El solapamiento entre pausas ya
cerradas solo puede producirlo la aprobación de una corrección, que es una
operación deliberada, de un responsable, y de baja frecuencia: validarla en el
servicio es suficiente y mucho más fácil de explicar en un mensaje de error.

**Alternativa descartada**: restricción de exclusión con `tstzrange`:

```sql
ALTER TABLE pausas ADD CONSTRAINT no_overlap
  EXCLUDE USING gist (fichaje_id WITH =, tstzrange(inicio, fin) WITH &&);
```

Es más elegante y cubre los dos casos de una vez. Se descarta porque exige la
extensión `btree_gist` (verificada como disponible, versión 1.7, tanto en el
Postgres local como en Supabase) y la constitución pide no añadir
infraestructura sin necesidad demostrada. Queda anotada como mejora si los
solapamientos aparecen en la práctica: el cambio sería una migración aditiva,
sin rehacer nada.

---

## 4. Inmutabilidad real del registro: log de eventos + proyección

**Decisión**: separar el **registro de hechos** (inmutable) del **estado actual**
(derivado y actualizable).

| Tabla | Naturaleza | Se actualiza |
|-------|-----------|--------------|
| `fichaje_eventos` | append-only: un registro por operación recibida (entrada, inicio/fin de pausa, salida), con `client_event_id`, `occurred_at` y `received_at` | **Nunca** |
| `fichajes` | proyección del estado actual de la jornada | Sí, al avanzar la jornada y al aprobar una corrección |
| `pausas` | proyección de las pausas | Sí, por lo mismo |
| `solicitudes_correccion_fichaje` | append-only una vez resuelta; conserva los valores originales | No, tras resolverse |

**Rationale**: resuelve una tensión real con el principio III de la
constitución, que dice que las tablas de fichaje son append-only y que la
aplicación no ejecuta `UPDATE` sobre ellas. **Tal cual está escrito es
inimplementable**: un fichaje nace `EN_CURSO` y necesariamente se actualiza para
añadir su salida y pasar a `CERRADO`. Si no se separa el hecho del estado, o se
incumple el principio o no hay forma de cerrar una jornada.

Lo mejor de esta separación es que **no cuesta nada extra**: la tabla de
eventos hace falta de todos modos para la idempotencia (FR-024) y para guardar
`occurredAt`/`receivedAt` por operación (FR-025). Usarla además como prueba
documental es gratis. Y es la prueba que de verdad importa ante la Inspección:
lo que la persona fichó y cuándo, no el estado agregado.

**Consecuencia para la constitución**: el principio III necesita una enmienda
de redacción para decir lo que quiere decir — que los **hechos** son inmutables
y que un fichaje **finalizado** no se modifica salvo por corrección aprobada —
en lugar de prohibir todo `UPDATE`. Ver el apartado Constitution Check del plan.

**Alternativa descartada**: event sourcing puro, reconstruyendo el estado del
fichaje en cada lectura sin tabla de proyección. Más fiel a la inmutabilidad,
pero obliga a recalcular en cada consulta y complica enormemente los filtros
por rango de fechas y la exportación (feature 003). La proyección es la elección
pragmática y habitual.

---

## 5. Idempotencia de operaciones diferidas (FR-024)

**Decisión**: tabla de claves de idempotencia con huella de la petición y
respuesta almacenada.

- `client_event_id` (UUID) es único.
- Se guarda una huella (hash) del cuerpo de la petición, el código de estado y
  el cuerpo de la respuesta original.
- Reenvío con el mismo `client_event_id` **y** la misma huella → se devuelve la
  respuesta original tal cual, sin tocar nada.
- Reenvío con el mismo `client_event_id` y **huella distinta** → `409 Conflict`.
  Significa que el cliente reutilizó una clave para otra operación, que es un
  error del cliente y nunca debe resolverse en silencio.

**Rationale**: devolver "el resultado original" exige haberlo guardado. La
alternativa tentadora —devolver el estado *actual* del fichaje— da respuestas
distintas si la jornada avanzó entre el envío y el reintento, lo que rompe la
promesa de FR-024 justo en el caso que motiva la feature. Es el patrón de
claves de idempotencia que usan las pasarelas de pago, por la misma razón.

La comprobación de huella es lo que convierte la idempotencia en una garantía:
sin ella, un cliente con un bug que reutiliza la clave obtendría la respuesta
de otra operación y nadie se enteraría.

**Alternativas descartadas**: restricción única sobre `(empleado_id, tipo,
occurred_at)` (dos fichajes legítimos podrían coincidir al segundo, y no
permite devolver la respuesta original); deduplicación solo en memoria o en
caché (se pierde al reiniciar, y no hay caché en este proyecto).

---

## 6. Tiempo: instantes, zona y cambio de hora (FR-008)

**Decisión**: `Instant` en Kotlin, `TIMESTAMPTZ` en Postgres, UTC de principio a
fin. `Europe/Madrid` aparece **solo** en el borde: al interpretar los parámetros
`desde`/`hasta` de la consulta y al formatear para presentación.

**Rationale**: `TIMESTAMPTZ` almacena un instante absoluto, así que la
diferencia entre dos instantes es siempre el tiempo realmente transcurrido —
que es exactamente lo que FR-008 exige para jornadas que cruzan la medianoche o
un cambio de hora. Con `LocalDateTime`/`TIMESTAMP` sin zona, una jornada que
atraviesa el cambio de octubre mediría una hora de más o de menos, y el
registro legal sería incorrecto.

El punto delicado no es el almacenamiento sino la **consulta**: `desde=2026-10-25`
es una fecha civil española, y hay que convertirla al instante de inicio de ese
día *en Europe/Madrid* antes de comparar. El día del cambio de hora tiene 23 o
25 horas, y hacerlo a mano con un desplazamiento fijo da un rango equivocado.
Se usa `ZoneId.of("Europe/Madrid")` y `atStartOfDay()`, que lo resuelve bien.

Mismo criterio para "fichajes del día": un fichaje se atribuye a la fecha civil
española de su entrada, no a su fecha UTC — a las 00:30 de Madrid en verano es
todavía el día anterior en UTC.

**Alternativas descartadas**: `LocalDateTime` con zona implícita (rompe el
cálculo en el cambio de hora); guardar además la zona por fichaje (solo haría
falta con personal en varios husos, que no es el caso y está fuera de alcance).

---

## 7. Identidad: `Empleado.id` es el sujeto del JWT

**Decisión**: el identificador de `Empleado` **es** el sujeto (`subject`) del
token. No se añade una tabla de usuarios aparte ni un mapeo entre ambos.

**Rationale**: `JwtService` ya emite y lee un `UUID` como sujeto, y
`requestUserId` lo expone. Si `Empleado.id` es ese mismo UUID, la comprobación
de FR-022 ("solo lo suyo") es una comparación directa, sin consulta intermedia
que pueda quedar desincronizada. Evita además el error clásico de aceptar un
`empleadoId` del cuerpo de la petición.

**Consecuencia**: el endpoint `GET /api/fichajes/empleado/{empleadoId}` recibe
el identificador en la ruta por legibilidad de la API, pero para un `EMPLEADO`
**debe** compararse contra el sujeto del token y rechazar si no coincide. El
identificador de la ruta nunca es la fuente de autoridad.

**Alternativas descartadas**: tabla `usuario` separada con relación a
`empleado` (una indirección sin ningún caso de uso que la justifique hoy); el
DNI como identidad (es dato personal y no debe viajar en un token ni en los
logs).

---

## 8. Row Level Security en las tablas nuevas

**Decisión**: cada migración que cree una tabla activa RLS en la misma
migración, sin políticas (denegar por defecto). Sin `FORCE ROW LEVEL SECURITY`.

**Rationale**: es el principio VII de la constitución y el patrón ya establecido
en `V5__enable_row_level_security.sql`. `RowLevelSecurityIT` falla el build si
alguna tabla se queda sin RLS, así que olvidarlo no llega a `main`. `FORCE` está
prohibido porque aplicaría RLS también al propietario y el backend conecta como
propietario.

**Nota**: las tablas de este módulo contienen datos personales (DNI, ubicación,
jornada), así que la exposición accidental vía PostgREST sería más grave aquí
que en inventario.

---

## 9. Validación del documento de identidad

**Decisión**: validar el formato y el dígito de control de DNI y NIE con un
validador propio de Jakarta Validation. Normalizar a mayúsculas sin espacios ni
guiones antes de comparar la unicidad de FR-029.

**Rationale**: sin normalizar, `12345678z` y `12345678-Z` serían dos personas
distintas para el índice único, y la regla de FR-029 quedaría burlada por un
espacio. El dígito de control detecta errores de teclado en el alta, que es
donde se introducen.

**Restricción asociada**: el DNI **nunca** aparece en logs ni en mensajes de
error (principio VI). En los errores de validación se referencia el campo, no su
valor.

**Alternativa descartada**: aceptar cualquier cadena no vacía (deja entrar datos
basura en un campo que identifica legalmente a una persona y que se exporta a la
Inspección).

---

## 10. Estrategia de pruebas

**Decisión**:

| Nivel | Herramienta | Qué cubre |
|-------|-------------|-----------|
| Dominio | MockK, sin Spring ni base de datos | Cálculo de horas (incluida medianoche y cambio de hora), transiciones de estado, reglas de coherencia, tolerancia de reloj, autorización por rol |
| Integración | Testcontainers con Postgres real | Migraciones + mapeo JPA, índices únicos parciales bajo concurrencia, **reenvío duplicado de la misma operación**, inmutabilidad de un fichaje cerrado, RLS de las tablas nuevas |

Los invariantes legales (principio III) y de autorización (principio IV) llevan
test que falla si se rompen, por exigencia del principio V.

**Rationale**: el cálculo de horas en el cambio de hora y la deduplicación por
`clientEventId` son precisamente los sitios donde un mock de base de datos no
detecta nada: el primero depende de la aritmética real de `TIMESTAMPTZ` y el
segundo del comportamiento real de una restricción única bajo carrera. Van a
Testcontainers obligatoriamente.

**Alternativa descartada**: H2 para las pruebas de integración (no implementa
índices únicos parciales igual, ni RLS, ni `TIMESTAMPTZ` con la misma
semántica — probaría otra cosa).

---

## Incógnitas resueltas

Ninguna queda abierta. Las tres decisiones de producto que faltaban
(`INCOMPLETO`, alcance de las correcciones, tolerancia de reloj) se resolvieron
con el responsable del producto antes de cerrar la especificación y están en
`spec.md`, no aquí.
