# Specification Quality Checklist: Inicio de sesión real (auth)

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

Los tres marcadores se resolvieron con el responsable del producto antes de
cerrar la spec:

| Requisito | Decisión | Consecuencia |
|-----------|----------|--------------|
| FR-029 | El correo vive en la cuenta de acceso, y dar acceso es **una sola operación** que recibe el identificador del empleado y su correo | FR-029a a FR-029d, SC-013. `auth` queda como módulo realmente independiente, apoyado en que el sujeto del token ya es el id del empleado. Contrapartida aceptada: sin clave ajena entre módulos hay que detectar cuentas huérfanas por otra vía (FR-029c) |
| FR-016 | Bloqueo **creciente**: 1, 5, 15, 60 minutos, con el nivel a cero tras un acceso correcto | FR-016a a FR-016c, SC-012. Lo decisivo es FR-016c: un fallo durante el bloqueo **no** lo prolonga, porque si lo hiciera bastaría seguir intentando para impedirle fichar a alguien indefinidamente |
| FR-023 | **8 caracteres** con mayúscula, minúscula, dígito y símbolo | FR-023 a FR-023b, SC-014. Elegida conociendo la alternativa (ver más abajo) |

### Sobre la política de contraseña

Se recomendó longitud mínima de 12 sin reglas de composición, que es la
recomendación actual del NIST: 8 caracteres con reglas tienen **menos** entropía
que 12 libres, y la obligación de símbolos tiende a producir la misma
contraseña en todas las cuentas. El responsable del producto eligió la política
clásica conociendo ese razonamiento, así que queda registrada como decisión
deliberada y no como descuido.

Tiene una consecuencia técnica que el plan debe recoger: con un espacio de
contraseñas más pequeño, **el coste del hash adaptativo importa más**. Sus
parámetros deben elegirse explícitamente y no dejarse por defecto.

### Un punto resuelto como supuesto, no como marcador

El responsable del producto pidió priorizar la **duración de los tokens** en
`/speckit-clarify`. Está en Assumptions con valores concretos —15 minutos el de
acceso, 30 días el de renovación— porque **el emisor actual del proyecto ya usa
exactamente esos**, así que adoptarlos no cambia ningún comportamiento y es
configuración antes que diseño. Conviene confirmarlo, pero no bloquea planificar.

Lo mismo con **qué se registra en los logs de seguridad**: el principio VI ya lo
determina (ni secretos ni datos personales), así que FR-017 lo recoge como
requisito en lugar de preguntarlo.

### Hallazgo al redactar

**`Empleado` no tiene campo de correo.** Verificado en
`EmpleadoEntity`: nombre, documento, puesto, tipo de contrato, fecha de alta y
activo. El inicio de sesión por correo necesita decidir dónde vive ese dato, y
de ahí sale FR-029 — que es el marcador con más consecuencia de los tres, porque
determina si `auth` es un módulo independiente de verdad.
