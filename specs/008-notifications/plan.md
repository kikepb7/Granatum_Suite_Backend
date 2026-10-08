# Implementation Plan: Notificaciones

**Branch**: `backlog-feature` | **Date**: 2026-10-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-notifications/spec.md`

## Summary

Un módulo nuevo, `features/notifications`, que solo depende de `common`. Las
features que originan avisos **publican un evento de dominio** (`AvisoDominio`,
en `common`) con el `ApplicationEventPublisher` de Spring; este módulo los
escucha **después de confirmarse la transacción** que los originó, decide los
destinatarios y guarda una fila por destinatario (V23, con RLS). Para saber
quién es `ENCARGADO` o `ADMIN` usa un contrato nuevo, `DirectorioRoles`, que
implementa `auth`. Un aviso no guarda texto: el mensaje se compone al leerlo, a
partir del tipo.

## Technical Context

**Language/Version**: Kotlin 2.2 sobre JVM 21

**Primary Dependencies**: Spring Boot 4. Sin dependencias nuevas: eventos de
aplicación de Spring, sin mensajería (constitución, "simplicidad deliberada").

**Storage**: PostgreSQL. `notificaciones` (V23).

**Testing**: integración con Testcontainers; en los módulos que publican,
`@RecordApplicationEvents` comprueba qué se publica; punta a punta en `app`.

**Target Platform / Project Type**: servicio web, una instancia.

**Constraints**: un aviso no puede deshacer la operación que lo originó, ni
existir si esta se deshizo; ningún dato personal en el aviso.

## Constitution Check

| Principio | Cumplimiento |
|---|---|
| I. Features independientes | El evento y `DirectorioRoles` viven en `common`. Quien publica no conoce a quien escucha; `notifications` no importa ninguna feature. Es la segunda forma de "contrato explícito" del principio I, junto a las interfaces. |
| II. Flyway | V23, nueva. |
| III. Registro horario | El aviso de salida olvidada **lee** fichajes abiertos; no escribe nada en el registro. Marcar incompleto sigue igual y además publica un evento. |
| IV. Roles | Los cuatro roles consultan **su** bandeja; la propiedad, contra el sujeto del token. |
| V. Tests | Destinatarios, deduplicación, transacción, bandeja, propiedad, limpieza, RLS; publicación en cada feature. |
| VI. Datos personales | El aviso guarda tipo y referencia; ningún texto libre ni nombre. |
| VII. RLS | `notificaciones`, en V23. |
| VIII. Contrato API | `/api/notificaciones`, `{code, message}`. |
| IX. Documentación | Spec, plan, tareas; README y ARCHITECTURE (eventos de dominio como contrato). |

**Resultado**: pasa.

## Decisiones

- **D-001 Evento tras la confirmación**: `@TransactionalEventListener(AFTER_COMMIT,
  fallbackExecution = true)` y la escritura en una transacción propia. Si la
  operación se deshace, no hay aviso (FR-008); si el aviso falla, la operación
  ya está confirmada y el fallo se registra sin datos personales.
  `fallbackExecution` cubre a quien publica sin transacción (el registro de la
  005, que cifra fuera de transacción).
- **D-002 Deduplicación**: índice único `(destinatario_id, tipo, referencia_id)`
  e `INSERT … ON CONFLICT DO NOTHING`: la comprobación periódica de salidas
  olvidadas puede volver a ver el mismo fichaje sin duplicar el aviso.
- **D-003 Sin texto guardado**: el mensaje es fijo por tipo y se compone al
  leer; cambiar la redacción no exige migrar filas, y no hay texto libre que
  pueda arrastrar datos personales.
- **D-004 Destinatarios por rol**: `DirectorioRoles.empleadosConRol(roles)`,
  implementado por `auth` sobre `cuentas_acceso`. Se excluye a quien originó el
  evento.
- **D-005 Limpieza**: un `DELETE` diario; un aviso no es un documento con valor
  legal, así que, a diferencia del registro de jornada, sí se borra.

## Project Structure

```text
common/src/main/kotlin/com/granatum/core/domain/
├── event/AvisoDominio.kt            # evento + TipoAviso
└── contract/DirectorioRoles.kt
features/notifications/             # módulo nuevo
features/auth/…/DirectorioRolesJpa.kt
features/timetracking/…/AvisoSalidaOlvidadaJob.kt, + publicación en correcciones e incompletos
features/absences/…                  # publicación en solicitar/aprobar/rechazar
features/auth/…/RegistroService.kt   # publicación del registro pendiente
```

## Complexity Tracking

Sin violaciones.
