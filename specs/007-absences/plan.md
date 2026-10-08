# Implementation Plan: Ausencias y vacaciones

**Branch**: `backlog-feature` | **Date**: 2026-10-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-absences/spec.md`

## Summary

Un módulo nuevo, `features/absences`, que solo depende de `common`. Una tabla
de ausencias y otra de derechos de vacaciones (V22, con RLS). Solicitar,
aprobar, rechazar, cancelar, registrar bajas y cerrarlas; saldo anual en días
naturales. El no solapamiento y el saldo se comprueban bajo un **bloqueo
consultivo por persona**, de modo que dos peticiones simultáneas de la misma
persona se serializan (D-002). La existencia y actividad de la persona, por el
contrato `DirectorioEmpleados` que ya existe.

## Technical Context

**Language/Version**: Kotlin 2.2 sobre JVM 21

**Primary Dependencies**: Spring Boot 4 (Web, Data JPA, Validation, Security). Sin dependencias nuevas.

**Storage**: PostgreSQL. `ausencias` y `derechos_vacaciones` (V22).

**Testing**: unitarios del cálculo de saldo (puro); integración con Testcontainers y un doble de `DirectorioEmpleados`; permisos en `app`.

**Target Platform**: servidor Linux (la imagen de la 006).

**Project Type**: servicio web.

**Performance Goals**: decenas de personas; consultas por rango con índice.

**Constraints**: sin datos de salud; sin borrado; sin extensiones de Postgres.

**Scale/Scope**: una plantilla de decenas de personas.

## Constitution Check

| Principio | Cumplimiento |
|---|---|
| I. Features independientes | Módulo nuevo en `features/absences`, solo depende de `common`; la persona se comprueba con `DirectorioEmpleados`. |
| II. Flyway | V22, nueva. |
| III. Registro horario | No se toca: una ausencia no crea ni modifica fichajes. |
| IV. Roles | **Amplía la tabla del principio IV** (enmienda MINOR): `EMPLEADO` pide y ve las suyas; `ENCARGADO` y `ADMIN` resuelven y ven todas; `REPRESENTANTE` nada. Las reglas por ruta en `SecurityConfig`; la propiedad, en el servicio contra el sujeto del token. |
| V. Tests | Unitarios del saldo; integración de solicitud, resolución, solapamiento concurrente, bajas, cancelación y saldo; RLS; sin borrado; permisos en `app`. |
| VI. Datos personales | Una baja no guarda texto (CHECK en base de datos). Comentarios y motivos fuera de los logs. |
| VII. RLS | Las dos tablas, en V22. |
| VIII. Contrato API | `/api/ausencias`, DTO separados, errores `{code, message}`. |
| IX. Documentación | Spec, plan, tareas; README y ARCHITECTURE. |

**Resultado**: pasa, con la enmienda del principio IV (roles) registrada en la constitución.

## Project Structure

```text
features/absences/
├── build.gradle.kts
└── src/main/
    ├── kotlin/com/granatum/core/
    │   ├── api/controllers/AusenciaController.kt
    │   ├── api/dto/AusenciaDtos.kt
    │   ├── api/exception_handling/AusenciasExceptionHandler.kt
    │   ├── domain/model/Ausencias.kt                 # Ausencia, SaldoVacaciones, tipos y estados
    │   ├── domain/service/CalculadoraSaldo.kt        # puro
    │   ├── domain/exception/AusenciasExceptions.kt
    │   ├── infrastructure/database/entities/…
    │   ├── infrastructure/database/repositories/…    # sin delete
    │   └── service/AusenciaService.kt, SaldoVacacionesService.kt
    └── resources/db/migration/V22__create_ausencias_tables.sql
```

## Fases

1. **Setup**: módulo, `settings.gradle.kts`, `app` lo incluye.
2. **Fundación**: V22, entidades, repositorios sin `delete`, reglas de `SecurityConfig`, doble del directorio.
3. **US1** vacaciones y resolución · **US2** solapamiento · **US3** bajas y permisos · **US4** cancelar · **US5** calendario y saldo.
4. **Pulido**: permisos en `app`, RLS global, colisión de clases, logs, README, ARCHITECTURE, constitución.

## Complexity Tracking

Sin violaciones; una enmienda MINOR del principio IV.
