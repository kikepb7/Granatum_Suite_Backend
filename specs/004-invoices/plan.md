# Implementation Plan: Facturación con reconocimiento automático

**Branch**: `invoices-feature` | **Date**: 2026-10-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-invoices/spec.md`

## Summary

Quien administra la empresa sube fotos, capturas o PDF de facturas. Claude las
lee y propone sus datos, el `ADMIN` los revisa y los confirma, y con las
confirmadas se sacan reportes mensuales, trimestrales y anuales en pantalla, CSV y
PDF. Los trimestres se cierran cuando se declaran y, desde entonces, sus cifras no
cambian.

**Enfoque técnico**: un módulo nuevo, `features/invoices`, que solo depende de
`common`. El reconocimiento es **una** llamada a la API de Claude
(`claude-opus-5-5`) con el SDK oficial de Java, que le envía la imagen o el PDF tal
cual y recibe una salida estructurada validada contra un esquema. La llamada corre
en segundo plano y **fuera de cualquier transacción**: la subida responde `202` en
cuanto el original está guardado. Sin clave de API, la feature funciona en modo
manual. Originales en Postgres, en una tabla aparte, detrás de una interfaz para
poder moverlos a almacenamiento de objetos. Tres migraciones, V17–V19, con RLS.

## Technical Context

**Language/Version**: Kotlin 2.2 sobre JVM 21

**Primary Dependencies**: Spring Boot 4.0.0-SNAPSHOT (Web, Data JPA, Validation,
Security), como el resto. **Nuevas**, solo en `features/invoices`:

- `com.anthropic:anthropic-java`, el SDK oficial, que se usa directamente desde
  Kotlin. La versión se fija en `libs.versions.toml` al implementar, comprobando la
  última publicada.
- `org.apache.pdfbox:pdfbox` 3.x, para examinar los PDF subidos y generar el PDF de
  los reportes (D-010).
- Para las imágenes WebP, un lector de ImageIO solo si hace falta reducir una WebP
  grande antes de enviarla (D-007). Se decide con la prueba de la foto real.

**Storage**: PostgreSQL. Ocho tablas nuevas (V17–V19); originales en `BYTEA`
(D-008).

**Testing**: JUnit 5 + MockK para las funciones puras (validador, calculadora de
reportes, NIF, CSV); Testcontainers con Postgres real; HTTP real en `app` para
permisos, multipart, descargas y logs. El reconocimiento se sustituye por un
**doble** en toda la suite (D-019); la medición con la API real es un test
etiquetado aparte.

**Target Platform**: servidor Linux; Postgres local o Supabase

**Project Type**: servicio web multi-módulo

**Performance Goals**: borrador en menos de 30 s en el 95% de los casos (SC-001);
reportes instantáneos con miles de facturas (una consulta agregada por grupo).

**Constraints**:
- Ninguna conexión de la base de datos abierta mientras se espera a Claude (D-005).
- Importes exactos al céntimo: `BigDecimal` y `NUMERIC(12,2)` (D-013).
- El contenido de una factura nunca actúa como instrucción (D-004).
- Nada de las facturas en los logs (D-018).
- La clave de la API, solo como variable de entorno (principio VI).

**Scale/Scope**: una empresa; decenas o pocos cientos de facturas al mes; 14 rutas,
8 tablas.

## Constitution Check

*GATE: comprobado antes de la Fase 0 y revisado tras la Fase 1.*

| Principio | Cómo lo cumple este plan | Estado |
|---|---|---|
| **I. Features independientes** | Módulo `:features:invoices`, solo con `common`. Lo que comparte con `timetracking` (formato CSV, validación de DNI/NIE) **sube a `common`** en lugar de copiarse (D-011, D-012). | ✅ |
| **II. Flyway** | V17, V18 y V19 nuevas, sin tocar ninguna aplicada. | ✅ |
| **III. Inmutabilidad** | Gobierna el registro de jornada, no las facturas, pero se aplica el mismo criterio: repositorios sin `delete`, historial de solo inserción (`factura_cambios`, `factura_reconocimientos`, `trimestre_eventos`), descartar no borra, y un trimestre cerrado no cambia. | ✅ |
| **IV. Autorización** | Una regla `hasRole("ADMIN")` para `/api/facturacion/**` en `SecurityConfig`, antes del comodín (D-017). Test de todas las rutas con todos los roles. | ✅ |
| **V. Tests** | Funciones puras con tests unitarios; reglas de base de datos con Testcontainers; permisos, multipart y logs por HTTP real; cada invariante con un test que falla si se rompe. El doble del reconocimiento evita coste y red en la suite (D-019). | ✅ |
| **VI. Secretos y datos personales** | `ANTHROPIC_API_KEY` solo como variable de entorno, sin valor en ningún yml (D-006). Ni ficheros, ni importes, ni NIF en los logs, con test en `DEBUG` (D-018). A Claude solo va el documento (FR-028). | ✅ |
| **VII. RLS** | Las ocho tablas con `ENABLE ROW LEVEL SECURITY` en su migración. `EsquemaCompletoRlsIT` amplía su lista y el módulo tiene su `RowLevelSecurityIT`. | ✅ |
| **VIII. Contrato de la API** | Errores en `{code, message}`. Los problemas de validación van **dentro del recurso** como `avisos`, no como campos extra del error, así que no hace falta otra desviación como la de `PASSWORD_DEBIL`. | ✅ |
| **IX. Documentación** | `specs/004-…` completa; README (feature, variables, coste, aviso de Supabase); ARCHITECTURE (módulo nuevo, integración externa, lo que sube a `common`). | ✅ pendiente de ejecución |

**Sin violaciones**, así que *Complexity Tracking* queda vacío.

## Project Structure

### Documentation (this feature)

```text
specs/004-invoices/
├── plan.md              # Este fichero
├── research.md          # 20 decisiones con su porqué
├── data-model.md        # 8 tablas, estados y modelo de dominio
├── quickstart.md        # validación contra la aplicación en marcha
├── contracts/README.md  # 14 rutas
├── checklists/requirements.md   # 16/16
└── tasks.md             # /speckit-tasks — no lo crea este comando
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/granatum/core/
├── csv/FormatoCsv.kt                    # sube desde timetracking (D-011)
└── validation/NifValidator.kt           # sube desde timetracking y añade CIF (D-012)

features/timetracking/                   # pasa a usar los dos de common, sin cambiar comportamiento

features/invoices/
├── build.gradle.kts                     # solo :common + SDK de Anthropic + PDFBox
└── src/main/
    ├── kotlin/com/granatum/core/
    │   ├── api/controllers/
    │   │   ├── EmpresaController.kt
    │   │   ├── FacturaController.kt         # subida multipart, CRUD de revisión, original, historial
    │   │   ├── ReporteFacturacionController.kt  # json / csv / pdf
    │   │   └── TrimestreController.kt
    │   ├── api/dto/FacturacionDtos.kt       # toString seguro (D-018)
    │   ├── api/exception_handling/InvoicesExceptionHandler.kt
    │   ├── domain/model/                    # Factura, LineaIva, Periodo, Reporte, PropuestaReconocida
    │   ├── domain/service/
    │   │   ├── ValidadorFactura.kt          # FR-011 a FR-014 → avisos
    │   │   ├── CalculadoraReporte.kt
    │   │   └── ClasificadorFactura.kt       # emitida / recibida (D-014)
    │   ├── domain/port/
    │   │   ├── ReconocedorFacturas.kt       # interfaz; el doble la implementa en los tests
    │   │   └── AlmacenDocumentos.kt         # interfaz (D-008)
    │   ├── infrastructure/claude/ReconocedorClaude.kt   # SDK de Anthropic, salida estructurada
    │   ├── infrastructure/documentos/       # firma de fichero, PDFBox, reducción de imágenes
    │   ├── infrastructure/database/         # entidades y repositorios sin delete
    │   ├── infrastructure/reportes/         # EscritorReporteCsv, EscritorReportePdf
    │   ├── service/
    │   │   ├── SubidaFacturas.kt            # guarda y responde 202
    │   │   ├── ColaReconocimiento.kt        # ejecutor acotado, fuera de transacción (D-005)
    │   │   ├── RevisionFacturas.kt          # corregir, confirmar, descartar, con bloqueo de trimestre
    │   │   ├── ReportesFacturacion.kt
    │   │   └── Trimestres.kt
    │   └── scheduling/ReintentoReconocimientoJob.kt   # retoma las pendientes tras un reinicio
    └── resources/db/migration/
        ├── V17__create_empresa_and_trimestres.sql
        ├── V18__create_facturas.sql
        └── V19__create_factura_trazabilidad.sql

app/src/main/kotlin/…/api/security/SecurityConfig.kt   # /api/facturacion/** → ADMIN
app/src/main/resources/application.yml                 # multipart, invoices.*, sin la clave
app/src/test/kotlin/com/granatum/core/                 # HTTP real: permisos, multipart, descargas, logs
```

**Structure Decision**: un módulo nuevo en `features/`, como fija el principio I
desde la v2.0.2. La interfaz `ReconocedorFacturas` en `domain/port` es lo que
permite el doble de los tests y, más adelante, cambiar de modelo o de proveedor
sin tocar el resto.

## Fases de entrega

| Fase | Contenido | Historias | Entregable comprobable |
|---|---|---|---|
| **1. Setup** | Módulo, dependencias, `settings.gradle.kts`, `app`, configuración (multipart, `invoices.*`), `.env.example` | — | build en verde |
| **2. Fundación** | `FormatoCsv` (con celda numérica) y NIF a `common` (los tests de `timetracking` siguen en verde); V17–V19 con RLS; entidades y repositorios sin `delete`; modelo de dominio; regla `ADMIN`; handler de errores; datos de la empresa | — | RLS y sin borrado con test; `timetracking` intacto |
| **3. US1** | Subida, firma de fichero, PDF, duplicados exactos, almacén, cola de reconocimiento, `ReconocedorClaude` y su doble, reintento programado | P1 | facturas `PENDIENTE` → `BORRADOR` con el doble; modo manual sin clave |
| **4. US2** | Validador y avisos, corrección, confirmación, descarte, clasificación, duplicado lógico, historial | P1 | **SC-006, SC-007** |
| **5. US4** | Permisos de todas las rutas, inyección de instrucciones, logs en `DEBUG` | P2 | **SC-005**; FR-007, FR-027 |
| **6. US3** | Calculadora, reportes JSON, CSV y PDF | P2 | **SC-004, SC-010** |
| **7. US6** | Trimestres: cierre con instantánea, reapertura con motivo, bloqueo | P2 | **SC-009** |
| **8. US5** | Listado con filtros, original, historial | P3 | FR-024, FR-025 |
| **9. Pulido** | README, ARCHITECTURE, quickstart en marcha (sin y con clave), medición de SC-001 y SC-002 si hay clave y facturas de referencia | — | quickstart 9/9 |

US4 va antes que los reportes: los permisos y los logs se prueban en cuanto existen
las primeras rutas, en lugar de al final.

## Huecos encontrados al planificar

1. **HEIC no se puede enviar a Claude.** La spec lo admitía; se retira de FR-001
   (D-007).
2. **Los límites de multipart por defecto de Spring son 1 MB por fichero y 10 MB
   por petición.** Sin configurarlos, cualquier foto de móvil daría error al
   subirla. El de la petición sube a 50 MB, configurable; el de 10 MB por fichero
   lo aplica el servicio, para que un fichero grande no haga fallar a los demás;
   y el exceso responde `413 PETICION_DEMASIADO_GRANDE` (con
   `server.tomcat.max-swallow-size` al mismo valor, para que el 413 llegue).
3. **Jackson 2 está fijado en la 2.17** en `libs.versions.toml`, y el SDK de
   Anthropic trae su propio Jackson 2. Al añadirlo hay que comprobar que la versión
   que resuelve Gradle es la que el SDK necesita, y que el `ObjectMapper` propio de
   `timetracking` (D-006 de la feature 001) sigue igual.
4. **El `.env` se carga en los tests** (`build-logic/.../DotEnv.kt`). Una
   `ANTHROPIC_API_KEY` puesta ahí haría que la suite llamara a la API real y
   gastara dinero en cada build. Los tests de `app` fijan la clave vacía y usan
   el doble, y un test lo comprueba (tarea T054). *(Encontrado al generar las
   tareas.)*

## Decisiones que conviene revisar

- **Coste**: unos 0,05 $ por factura con Opus 5.5, unos 10 $ al mes con 200
  facturas (D-002). Pasar a Haiku 5.5 es configuración y cuesta unas 40 veces
  menos, pero solo con la medición de SC-002 delante.
- **Originales en Postgres** (D-008): bien en local; en el **plan gratuito de
  Supabase (500 MB)** se llenaría en unos meses. Si producción va a Supabase,
  hace falta un plan de pago o una implementación de `AlmacenDocumentos` sobre
  Supabase Storage.
- **Protección de datos**: Anthropic actúa como encargado del tratamiento de lo
  que contienen las facturas (nombres y NIF de autónomos). Antes de activar la
  clave en producción, la empresa tiene que aceptar sus condiciones de tratamiento
  de datos. Es una decisión legal tuya, no técnica.
- **SC-002 necesita 30 facturas reales** de la empresa para medirse. No se
  versionan; las aportas tú cuando quieras medir.

## Complexity Tracking

Sin violaciones de la constitución que justificar.
