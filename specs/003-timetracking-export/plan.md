# Implementation Plan: Exportación del registro de jornada

**Branch**: `export-feature` | **Date**: 2026-10-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-timetracking-export/spec.md`

## Summary

Poner el registro de jornada a disposición como fichero: cada persona el suyo,
quien gestiona la plantilla y la representación legal el de todos, y para cada
persona la descarga mensual. Esa descarga es la condición que la feature 001
(FR-031d) y el principio III exigen para poder habilitar la depuración a los cuatro
años, hoy desactivada.

**Enfoque técnico**: todo en `timetracking`, que es dueño de los datos, sin
depender de `auth`. CSV en UTF-8 con BOM y separador `;` que se genera en
streaming dentro de **una** transacción de lectura con instantánea, para que el
fichero sea coherente y determinista. Una huella SHA-256 calculada al vuelo sobre
los bytes enviados, y un registro de exportaciones de solo inserción que se
escribe **después** de esa transacción, nunca anidado. Dos migraciones: V15 y V16.

Al planificar aparecieron dos huecos en código que ya está en `main`, y el plan
los cierra porque esta feature pasa por encima de ellos:

1. **Ningún handler produce `400 VALIDACION`**, aunque el contrato de `auth` ya lo
   promete. Los errores de validación salen con el cuerpo por defecto de Spring, y
   en `dev` con la traza completa y el valor rechazado (D-019).
2. **`resumenMensual` carga todas las correcciones aprobadas de la tabla** para cada
   resumen, y la descarga mensual de toda la plantilla lo repetiría una vez por
   persona (D-011).

## Technical Context

**Language/Version**: Kotlin 2.2 sobre JVM 21

**Primary Dependencies**: Spring Boot 4.0.0-SNAPSHOT (Web, Data JPA, Validation,
Security). **Sin dependencias nuevas**: el CSV se escribe a mano, porque son
quince columnas con reglas propias de entrecomillado y de protección contra
fórmulas que ninguna librería aplica por defecto; SHA-256 viene en la JDK
(`MessageDigest`).

**Storage**: PostgreSQL. Tabla nueva `exportaciones` (V15) y columna nueva en
`depuraciones_retencion` (V16).

**Testing**: JUnit 5 + MockK (escritor CSV) + Testcontainers con Postgres real
(contenido, auditoría, RLS) + `RestClient` sobre puerto real en `app` (bytes,
permisos, logs).

**Target Platform**: servidor Linux; Postgres local o Supabase

**Project Type**: servicio web multi-módulo

**Performance Goals**: un año de una plantilla de 100 personas en menos de 30 s
(SC-009)

**Constraints**:
- El mismo alcance y rango, sin cambios entre medias, producen ficheros idénticos
  byte a byte (FR-011): sin marcas de tiempo dentro, con orden estable e
  instantánea única
- La ubicación no sale nunca; el documento no sale para `REPRESENTANTE`
- Ninguna celda se ejecuta como fórmula
- Una exportación ocupa **una** conexión y nunca espera otra: el registro se
  escribe fuera de la transacción de lectura (lección de la feature 002)
- Exportar no escribe nada en el registro de jornada (FR-018)

**Scale/Scope**: plantilla de decenas a pocos cientos de personas; 4 rutas, 1
tabla, 1 columna

## Constitution Check

*GATE: comprobado antes de la Fase 0 y revisado tras la Fase 1.*

| Principio | Cómo lo cumple este plan | Estado |
|---|---|---|
| **I. Features independientes** | Todo en `timetracking`, que solo depende de `common`. Los nombres de quien solicitó y aprobó una corrección salen de `empleados`; identidad y rol, del token vía `common`. Sin contrato nuevo y sin tocar `auth`. | ✅ |
| **II. Flyway** | V15 y V16 nuevas. V16 **añade** una columna con `DEFAULT 0` en lugar de editar V11, que ya está aplicada en `main`. | ✅ |
| **III. Inmutabilidad del registro** | La exportación solo lee (FR-018). `exportaciones` es de solo inserción, con repositorio sin `delete`. Su único borrado es el de la depuración automática, acotado por el corte. La depuración se hace **posible**, no se activa (FR-024). | ✅ |
| **IV. Autorización** | Rutas en `SecurityConfig`, con una regla nueva `ADMIN` para `/api/exportaciones/**`. La propiedad se comprueba contra el sujeto del token. `REPRESENTANTE`: solo lectura, sin ubicación y sin documento. | ✅ |
| **V. Tests** | Unitarios del escritor; Testcontainers de contenido, auditoría, depuración y RLS; HTTP real en `app` de permisos, bytes y logs. Cada invariante tiene un test que falla si se rompe (D-016). | ✅ |
| **VI. Secretos y datos personales** | Ni el fichero ni la verificación se registran; el nombre del fichero lleva el id, no el nombre; el registro de exportaciones no guarda datos exportados. Test de logs por HTTP real. | ✅ |
| **VII. RLS** | `exportaciones` con RLS en V15. `EsquemaCompletoRlsIT` y `RowLevelSecurityIT` de `timetracking` amplían su lista. | ✅ |
| **VIII. Contrato de la API** | Errores en `{code, message}` desde los advice existentes, y **se repara el incumplimiento preexistente** de los `400` de validación (D-019). | ✅ |
| **IX. Documentación** | `specs/003-…` completa; README (estado, depuración desbloqueada, cómo exportar); ARCHITECTURE (dos puntos de borrado pasan a incluir `exportaciones`). | ✅ pendiente de ejecución |

**Sin violaciones**, así que *Complexity Tracking* queda vacío.

Un punto para que una revisión no lo confunda con un incumplimiento: la depuración
gana capacidad de borrar (`exportaciones`). Respeta lo que exige el principio III:
solo el proceso automático borra, solo lo que ha cumplido el plazo por completo, y
cada ejecución queda anotada, ahora también con cuántas exportaciones eliminó
(V16).

## Project Structure

### Documentation (this feature)

```text
specs/003-timetracking-export/
├── plan.md              # Este fichero
├── research.md          # 19 decisiones con su porqué
├── data-model.md        # tabla exportaciones, columna nueva, modelo de dominio
├── quickstart.md        # validación contra la aplicación en marcha
├── contracts/README.md  # 4 rutas
├── checklists/requirements.md   # 16/16
└── tasks.md             # /speckit-tasks — no lo crea este comando
```

### Source Code (repository root)

```text
timetracking/src/main/
├── kotlin/com/granatum/core/
│   ├── api/controllers/
│   │   ├── ExportacionController.kt          # GET /api/fichajes/export y la descarga mensual
│   │   └── AuditoriaExportacionController.kt # GET /api/exportaciones, POST …/verificar
│   ├── api/dto/ExportacionDtos.kt            # con toString seguro (lección de 002)
│   ├── domain/model/                         # FilaRegistro, CorreccionAplicada, AlcanceExportacion…
│   ├── domain/service/
│   │   └── EscritorCsv.kt                    # puro: BOM, ;, CRLF, comillas, anti-fórmula
│   ├── domain/exception/                     # ExportacionSaturadaException, FicheroDemasiadoGrandeException
│   ├── infrastructure/database/
│   │   ├── entities/ExportacionEntity.kt
│   │   └── repositories/ExportacionRepository.kt   # Repository<T,ID>, sin delete
│   ├── service/
│   │   ├── PlazoConservacion.kt              # un solo cálculo del límite (D-009); posee la propiedad y el reloj
│   │   ├── ExportacionService.kt             # alcance, permisos, recorrido por lotes, instantánea
│   │   ├── RegistroExportaciones.kt          # inserta DESPUÉS de leer, nunca anidado
│   │   └── VerificacionExportaciones.kt
│   └── scheduling/DepuracionRetencionJob.kt  # usa PlazoConservacion; borra exportaciones
└── resources/db/migration/
    ├── V15__create_exportaciones_table.sql
    └── V16__add_exportaciones_eliminadas_to_depuraciones.sql

timetracking/src/main/kotlin/com/granatum/core/
├── service/FichajeService.kt                  # resumenMensual: correcciones acotadas al mes (D-011)
└── infrastructure/database/repositories/
    ├── SolicitudCorreccionFichajeRepository.kt  # aprobadas por lista de fichajes
    └── RetencionPurgaRepository.kt              # + borrarExportacionesAnterioresA

common/src/main/kotlin/com/granatum/core/api/exception_handling/
└── CommonExceptionHandler.kt                  # 400 VALIDACION para toda la API (D-019)

app/src/main/kotlin/…/api/security/SecurityConfig.kt   # /api/exportaciones/** → ADMIN
app/src/test/kotlin/com/granatum/core/                 # HTTP real: permisos, bytes, logs, VALIDACION
```

**Structure Decision**: módulo existente, sin módulos nuevos. La exportación es
una forma más de leer los datos de `timetracking`; un módulo aparte necesitaría un
contrato para leerlos y no aportaría nada a cambio.

## Fases de entrega

| Fase | Contenido | Historias | Entregable comprobable |
|---|---|---|---|
| **1. Setup** | Propiedades `timetracking.exportacion.*`, yml de test | — | build en verde |
| **2. Fundación** | V15/V16 con RLS; `EscritorCsv` con sus tests; `PlazoConservacion` extraído del job; entidad y repositorio; `VALIDACION` en `common` (D-019); corrección del resumen mensual (D-011) | — | unitarios del escritor; test de recuento de consultas del resumen |
| **3. US1** | Exportación de una persona: streaming, instantánea, correcciones y originales, registro posterior | P1 | **SC-001, SC-002** |
| **4. US2** | Descarga mensual con total, contrato y estado del mes | P1 | **SC-003**; condición de FR-031d cumplida |
| **5. US3** | Plantilla entera, orden estable, personas dadas de baja, semáforo | P2 | **SC-008, SC-009, SC-011** |
| **6. US4** | `REPRESENTANTE`: sin documento ni ubicación | P2 | **SC-004** |
| **7. US5** | Consulta del registro y verificación de ficheros; depuración de exportaciones | P3 | **SC-007**, SC-008 (verificación) |
| **8. Pulido** | Tests en `app` (permisos, bytes, logs, `VALIDACION`), RLS, README, ARCHITECTURE, quickstart en marcha | — | suite completa; quickstart 10/10 |

## Huecos encontrados en código existente

### 1. Los `400` de validación no tenían el formato del contrato (D-019)

Verificado contra la aplicación en marcha: `POST /api/auth/login` con un correo mal
formado y `GET …/resumen` sin parámetros devuelven el cuerpo por defecto de Spring.
En `dev` incluye **la traza completa y el valor rechazado**, así que un correo o una
contraseña enviados vuelven en la respuesta. Ninguna clase del producto produce
`VALIDACION`, aunque el contrato de `auth` lo prometía; sus tests solo comprobaban
el código `400`. Se arregla en `common` para toda la API, con tests que comprueban
**el cuerpo**.

### 2. El resumen mensual leía todas las correcciones de la historia (D-011)

`FichajeService.resumenMensual` hace `findAllByEstado(APROBADA)` para cada resumen:
todas las correcciones aprobadas de todas las personas y de todos los tiempos. Su
comentario dice "una consulta sobre el mes", pero la consulta no está acotada. La
descarga mensual de la plantilla lo multiplicaría por el número de personas. Se
acota a los fichajes del mes, con un test que cuenta las consultas.

## Decisiones que conviene revisar

- **La depuración no se activa** (FR-024). Después de esta feature, activarla es
  cambiar `timetracking.retencion.habilitada` en cada entorno. Es una decisión tuya
  y es irreversible por naturaleza.
- **Dos exportaciones simultáneas como máximo** por defecto (D-002), configurable.
  Una exportación grande ocupa una conexión mientras se descarga, y el pool es de 10.
- **El motivo de las correcciones no se exporta** (D-010): no lo pide la spec y es
  texto libre.

## Complexity Tracking

Sin violaciones de la constitución que justificar.
