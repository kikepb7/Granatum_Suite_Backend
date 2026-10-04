# Specification Quality Checklist: Registro horario de personal (timetracking)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-05
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

**Pasa las 16 comprobaciones.** La spec está lista para `/speckit-plan`.

### Aclaraciones resueltas

Los tres marcadores `[NEEDS CLARIFICATION]` del primer borrador se resolvieron
con el usuario antes de cerrar la spec:

| Requisito | Decisión | Consecuencia |
|-----------|----------|--------------|
| FR-012 | Proceso programado diario a hora fija configurable marca `INCOMPLETO` los fichajes abiertos de días anteriores; se completan solo por el flujo de correcciones | Introduce una tarea programada (FR-012 a FR-012c, SC-009, SC-010). Un fichaje `INCOMPLETO` deja de bloquear nuevas jornadas. |
| FR-020 | Corregibles: entrada, salida y pausas (añadir, eliminar, modificar). La ubicación NO es corregible | FR-020 a FR-020b. La ubicación se preserva como evidencia; las correcciones deben respetar las mismas reglas de coherencia que un fichaje normal. |
| FR-026 | Tolerancia asimétrica: 5 min hacia el futuro, 72 h hacia el pasado, ambos configurables | FR-026 a FR-026b. El rechazo por reloj debe ser distinguible para que la app móvil pueda explicarlo. |

### Puntos resueltos por defecto y confirmados

De los temas que el usuario pidió priorizar en `/speckit-clarify`, estos tenían
un valor por defecto defendible, así que se documentaron en Assumptions y Edge
Cases en lugar de bloquear la spec. **Confirmados expresamente por el
responsable del producto el 2026-10-05**:

- **Ubicación denegada** → el fichaje se registra sin ubicación. Bloquearlo
  impediría cumplir la obligación legal por un motivo accesorio.
- **Zona horaria y cambio de hora** → FR-008 exige que las horas sean las
  realmente transcurridas; el tratamiento técnico corresponde al plan.
- **Jornadas que cruzan la medianoche** → un único fichaje, atribuido a la
  fecha de entrada.
- **Pausas solapadas** → prohibidas (FR-004), por simetría con "un solo fichaje
  en curso".
- **Quién da de alta al personal** → solo `ADMIN` (FR-027), coherente con el
  principio IV de la constitución.

### Cumplimiento de la constitución

La spec desarrolla el principio III (inmutabilidad del registro horario) en
FR-013 a FR-020b y SC-002/SC-003, y el principio IV (roles) en FR-022, FR-023 y
FR-027. La retención de 4 años está en FR-031.
