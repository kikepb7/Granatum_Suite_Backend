# Implementation Plan: Registro horario de personal (timetracking)

**Branch**: `time-track-feature` | **Date**: 2026-10-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-timetracking/spec.md`

## Summary

Módulo `timetracking` que registra la jornada laboral conforme al RD-ley
8/2019: entrada, pausas, salida y cálculo de horas, con un registro que no se
puede editar y cuyas correcciones pasan por aprobación.

El enfoque técnico se apoya en una decisión que resuelve dos requisitos a la
vez: **un log de eventos append-only** (`fichaje_eventos`) sirve de mecanismo de
idempotencia para las operaciones diferidas de la app móvil **y** de prueba
documental inmutable, mientras que las tablas `fichajes` y `pausas` son una
proyección del estado actual que sí se actualiza al avanzar la jornada. Sin esa
separación no hay forma de cerrar una jornada sin incumplir la inmutabilidad
(ver Constitution Check).

Todo instante se almacena en UTC como `TIMESTAMPTZ`, de modo que el cálculo de
horas sea correcto al cruzar la medianoche y el cambio de hora; `Europe/Madrid`
aparece solo al interpretar los parámetros de fecha de las consultas.

## Technical Context

**Language/Version**: Kotlin 2.2 sobre JVM 21 (toolchain 21 vía
`granatum.kotlin-common`)

**Primary Dependencies**: Spring Boot 4 (Web, Security, Data JPA, Validation),
jjwt para los roles de `common`, Flyway. Sin dependencias nuevas:
`TaskSchedulingAutoConfiguration` para el proceso diario ya viene en
`spring-boot-autoconfigure` (verificado, research.md §1).

**Storage**: PostgreSQL. Cinco tablas nuevas con migraciones Flyway versionadas
y RLS activado en la misma migración que las crea.

**Testing**: JUnit 5 + MockK para dominio; Testcontainers con Postgres real para
integración. H2 descartado: no reproduce índices únicos parciales, RLS ni la
semántica de `TIMESTAMPTZ`.

**Target Platform**: servidor JVM. Consumido por una app móvil que opera sin
conexión y reenvía operaciones.

**Project Type**: módulo de dominio dentro de un monorepo Gradle multi-módulo;
servicio web REST.

**Performance Goals**: SC-007 entrada/salida en <5 s; SC-008 fichajes de toda la
plantilla de un mes en <3 s. El listado por rango usa fetch join para evitar el
N+1 que convertiría SC-008 en 1+N consultas.

**Constraints**: `spring.jpa.open-in-view: false`, así que todo mapeo a DTO que
toque una colección perezosa ocurre dentro de la transacción. `ddl-auto:
validate` en todos los perfiles: un mapeo que no coincida con el esquema rompe
el arranque.

**Scale/Scope**: plantilla de decenas de personas, ~1 fichaje por persona y día.
Volumen pequeño; la presión no está en la escala sino en la corrección legal y
la retención de 4 años.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Estado | Cómo lo cumple el diseño |
|-----------|--------|--------------------------|
| **I. Features independientes** | ✅ | Módulo Gradle propio que solo depende de `common`. No toca `inventory` ni es tocado por él. |
| **II. Esquema versionado con Flyway** | ✅ | Tablas nuevas en migraciones numeradas. Ninguna migración existente se edita. `validate` en todos los perfiles. |
| **III. Inmutabilidad del registro horario** | ✅ **Enmendado a v1.1.0** | Ver abajo. El diseño cumple la redacción enmendada, incluido el invariante de derivabilidad. |
| **IV. Autorización por roles** | ✅ | FR-022 implementado comparando contra el sujeto del JWT, nunca contra un identificador de la petición. Empleados de `/api/empleados` solo `ADMIN`. El cuarto rol `REPRESENTANTE` (v2.0.0) se añade a `common` y es solo lectura sin ubicación (FR-023a–c). |
| **V. Tests y CI en verde** | ✅ | MockK + Testcontainers, con test específico para cada invariante de los principios III, IV, VI y VII. |
| **VI. Seguridad y secretos** | ✅ | DNI y ubicación fuera de `toString`, de logs y de mensajes de error. Sin secretos nuevos. |
| **VII. RLS en todas las tablas** | ✅ | Las cinco tablas activan RLS en su propia migración, verificado por un `RowLevelSecurityIT` **propio de este módulo** (T018) más un test de esquema completo en `app` (T095). El de `inventory` no sirve aquí: solo ve las migraciones de su propio classpath. |
| **VIII. Contrato de la API REST** | ✅ | Todo bajo `/api`, formato de error único desde `@RestControllerAdvice`, DTO separados de entidades, flujo Controller → Service → Repository. |
| **IX. Documentación** | ✅ | Esta carpeta `specs/001-timetracking/`. Pendiente actualizar `README.md` y `docs/ARCHITECTURE.md` al implementar. |

### Principio III: tensión detectada y propuesta

El principio III dice hoy:

> Las tablas de fichaje y de histórico **DEBEN** tratarse como append-only: la
> aplicación no ejecuta `UPDATE` ni `DELETE` sobre ellas.

**Tal como está redactado es inimplementable.** Un fichaje nace `EN_CURSO` y
necesariamente se actualiza para registrar su salida y pasar a `CERRADO`; una
pausa se actualiza al cerrarla. Cumplir la letra exigiría no poder terminar
nunca una jornada.

Esto es un defecto de redacción mío al escribir la constitución, no un problema
del encargo: la intención del principio —que el registro no se pueda falsear—
es correcta y es la que manda.

**Enmienda aplicada: v1.0.0 → v1.1.0** (MINOR, no PATCH como se propuso al
principio: añade guía materialmente nueva —el invariante de derivabilidad y la
restricción sobre los repositorios— y la propia política de versionado de la
constitución reserva PATCH para aclaraciones sin cambio de significado).

La enmienda está redactada para **cerrar fugas, no para abrirlas**:

- Los **hechos** son inmutables sin excepción: la tabla de eventos no admite
  `UPDATE` ni `DELETE`, nunca, por ninguna vía.
- El **estado** es una proyección y sí se actualiza, pero **solo por las cinco
  transiciones enumeradas**. Cualquier otra escritura está prohibida.
- El **instante de entrada no cambia nunca** salvo por corrección aprobada,
  tampoco mientras el fichaje sigue `EN_CURSO`. Sin esta frase, "el estado se
  actualiza" sería licencia para reescribir el inicio de la jornada.
- Un fichaje finalizado no cambia salvo por corrección aprobada.
- **Invariante que cierra el resto**: el estado de un fichaje debe poder
  derivarse en todo momento del log de eventos más las correcciones aprobadas.
  Si un valor no se explica por un evento o una corrección, se escribió por una
  vía que no debería existir. Es verificable con un test que reconstruya el
  estado y lo compare, y la constitución exige tenerlo.
- Ninguna fila se borra nunca, en ninguna tabla.
- Los repositorios de tablas append-only no exponen operaciones de mutación.
  No basta con no llamarlas: si la interfaz las ofrece, un descuido futuro las
  usará.

Lo que hace sólida esta lectura es que la prueba documental no desaparece: vive
en `fichaje_eventos`, genuinamente append-only y necesaria de todos modos para
la idempotencia. Ante la Inspección importa qué fichó la persona y cuándo, no el
agregado.

**Deuda que genera la enmienda**, registrada en la sección Governance de la
constitución: `HistorialMaterialRepository` extiende `JpaRepository`, que expone
`delete`/`deleteAll` sobre una tabla append-only. Verificado que hoy nadie los
llama —el servicio solo inserta y lee—, así que no hay incumplimiento efectivo,
pero la interfaz ofrece la fuga y debe estrecharse antes de dar `timetracking`
por terminado.

### Re-evaluación posterior al diseño de Phase 1

Sin violaciones nuevas. Dos huecos del encargo detectados al escribir los
contratos, ambos señalados en su fichero y ninguno bloqueante para generar
tareas:

1. **Falta un endpoint de consulta de correcciones.** Sin
   `GET /api/fichajes/{id}/correcciones`, los valores originales se almacenan
   pero no hay forma de leerlos por API, y SC-003 no sería verificable desde
   fuera. También lo necesita la columna "correcciones aplicadas" del CSV de la
   feature 003.
2. **Dar de baja a alguien con un fichaje `EN_CURSO`** no está definido. La
   opción coherente con FR-012 es dejar que el proceso diario lo marque
   `INCOMPLETO`, en lugar de bloquear una gestión administrativa por un olvido.

## Project Structure

### Documentation (this feature)

```text
specs/001-timetracking/
├── plan.md              # Este fichero
├── spec.md              # Especificación (/speckit-specify)
├── research.md          # Phase 0: 10 decisiones técnicas razonadas
├── data-model.md        # Phase 1: entidades, restricciones, fetch plan
├── quickstart.md        # Phase 1: 8 escenarios de validación
├── contracts/
│   ├── README.md        # Error, autorización, idempotencia, tiempo
│   ├── fichajes.md
│   ├── correcciones.md
│   └── empleados.md
├── checklists/
│   └── requirements.md  # 16/16
└── tasks.md             # Phase 2 (/speckit-tasks) - NO lo crea este comando
```

### Source Code (repository root)

Módulo nuevo `timetracking/`, con el mismo layout que `inventory` (principio I y
`docs/ARCHITECTURE.md`):

```text
timetracking/
├── build.gradle.kts                  # id("granatum.spring-boot-service"), kotlin("plugin.jpa")
└── src/
    ├── main/
    │   ├── kotlin/com/granatum/core/
    │   │   ├── api/
    │   │   │   ├── controllers/      # FichajeController, CorreccionController, EmpleadoController
    │   │   │   ├── dto/              # peticiones y respuestas, separados de las entidades
    │   │   │   ├── mappers/          # Model -> Dto
    │   │   │   └── exception_handling/  # TimetrackingExceptionHandler
    │   │   ├── domain/
    │   │   │   ├── model/            # EmpleadoModel, FichajeModel, PausaModel, SolicitudCorreccionModel
    │   │   │   ├── exception/        # FichajeYaEnCursoException, FichajeInmutableException, ...
    │   │   │   ├── type/             # EstadoFichaje, TipoPausa, TipoContrato, EstadoSolicitud, TipoOperacionFichaje
    │   │   │   └── service/          # CalculadoraJornada: cálculo puro de minutos, sin JPA
    │   │   ├── infrastructure/
    │   │   │   └── database/
    │   │   │       ├── entities/     # EmpleadoEntity, FichajeEntity, PausaEntity, SolicitudCorreccionFichajeEntity, FichajeEventoEntity, UbicacionEmbeddable
    │   │   │       ├── mappers/      # Entity -> Model
    │   │   │       └── repositories/
    │   │   ├── service/              # FichajeService, CorreccionService, EmpleadoService, IdempotenciaService
    │   │   └── scheduling/           # MarcadoFichajesIncompletosJob
    │   └── resources/db/migration/   # V6..V10
    └── test/
        ├── kotlin/com/granatum/core/ # unitarios (MockK) + integración (Testcontainers)
        └── resources/application.yml # propiedades jwt.* para el contexto de test
```

Cambios en módulos existentes, mínimos y aditivos:

| Fichero | Cambio |
|---------|--------|
| `common/.../domain/type/Role.kt` | Añadir `REPRESENTANTE` (principio IV v2.0.0) |
| `settings.gradle.kts` | `include("timetracking")` |
| `app/build.gradle.kts` | `implementation(projects.timetracking)` |
| `app/.../GranatumSuiteApplication.kt` | `@EnableScheduling` |
| `app/.../SecurityConfig.kt` | Reglas de ruta para `/api/fichajes/**`, `/api/correcciones/**`, `/api/empleados/**` |
| `README.md`, `docs/ARCHITECTURE.md` | Estado del módulo (principio IX) |

**Structure Decision**: módulo Gradle independiente que replica el layout de
`inventory`, por el principio I. No se reutiliza el módulo `inventory` ni se
crea un módulo compartido intermedio: `timetracking` solo depende de `common`,
igual que `inventory`, y ninguno conoce al otro. `CalculadoraJornada` se aísla en
`domain/service/` como función pura para que el cálculo de horas —incluidos
medianoche y cambio de hora— se pueda testear con MockK sin base de datos ni
contexto de Spring.

Las migraciones van de `V6` a `V10` porque `inventory` ya ocupa `V1`–`V5` y
Flyway comparte un único histórico en `classpath:db/migration` para todos los
módulos. **Esto es un acoplamiento real entre módulos por la numeración**: al
añadir una migración en `inventory` habrá que mirar qué número ocupa
`timetracking`. Se acepta porque la alternativa (esquemas o histórico separados
por módulo) complica el despliegue mucho más de lo que ahorra.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Tabla de eventos añadida a las 4 entidades del encargo | FR-024 exige devolver el resultado original de una operación reenviada, y FR-025 guardar `occurredAt` y `receivedAt` por operación. Ninguna de las dos cosas cabe en las tablas de estado | Deducir la idempotencia de una restricción única sobre `(empleado_id, tipo, occurred_at)` no permite devolver la respuesta original y falla si dos operaciones legítimas coinciden al segundo. La tabla hace falta de todos modos, y usarla además como prueba documental es gratis |
| Índices únicos parciales escritos a mano en SQL | JPA no sabe expresarlos, y son la única garantía real frente a la carrera del reintento de la app móvil | Validar solo en el servicio deja pasar dos entradas simultáneas: ambas leen antes de que cualquiera escriba |
| `fueIncompleto` como campo aparte de `estado` | SC-010 exige distinguir una jornada reconstruida de una cerrada en su momento, incluso después de completarla | Un cuarto estado (`COMPLETADO_A_POSTERIORI`) obligaría a tratar dos estados equivalentes en todas las consultas y filtros |
