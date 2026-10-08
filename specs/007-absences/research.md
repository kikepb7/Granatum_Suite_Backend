# Research: Ausencias y vacaciones

**Fecha**: 2026-10-08 | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

## D-001 — Días naturales

El art. 38 del Estatuto fija el mínimo en 30 días naturales. Sin calendario de
festivos (fuera de alcance), contar laborables exigiría inventar uno. El saldo
cuenta los días naturales de cada ausencia **dentro del año** consultado: unas
vacaciones del 28-12 al 04-01 son 4 días de un año y 4 del siguiente.

## D-002 — Solapamiento y saldo bajo un bloqueo por persona

**Decisión**: crear, registrar y cerrar una baja toman
`pg_advisory_xact_lock(7007, hashtext(empleado_id::text))` dentro de su
transacción; con el bloqueo, comprueban solapamiento y saldo y escriben.

**Por qué no una restricción de exclusión** (`EXCLUDE USING gist (empleado_id
WITH =, daterange(desde, hasta, '[]') WITH &&)`): exige la extensión
`btree_gist`. En Supabase las extensiones se crean en otro esquema y con un rol
que la migración puede no tener; una migración que falla en producción es justo
lo que el principio II quiere evitar. Y el saldo necesita el mismo bloqueo de
todas formas: dos peticiones de vacaciones simultáneas leerían el mismo saldo.

**Coste**: el bloqueo es por persona; dos personas distintas no se esperan.
`hashtext` puede colisionar entre dos personas: se esperarían un instante, sin
efecto en la corrección.

## D-003 — Bajas sin datos de salud

Un `CHECK` impide comentario y causa en `BAJA_MEDICA`. El tipo "baja médica" ya
es un dato de salud (art. 9 RGPD), y es el mínimo imprescindible para que el
calendario no mienta; el diagnóstico, la duración prevista o el parte no se
guardan.

## D-004 — Estados

`PENDIENTE` → `APROBADA` | `RECHAZADA` | `CANCELADA`; `APROBADA` →
`CANCELADA` solo si no ha empezado. Lo registrado por un `ENCARGADO` o `ADMIN`
nace `APROBADA`. Bloqueo optimista (`version`) para que dos resoluciones a la vez
no se pisen.

## D-005 — Derecho anual

`derechos_vacaciones (empleado_id, anio, dias)`; sin fila, el valor configurado
(`absences.vacaciones.dias-anuales`, 30). Sin prorrateo automático: la fecha de
alta es de `timetracking` y el contrato `DirectorioEmpleados` no la expone a
propósito. El `ADMIN` ajusta el derecho de quien entra a mitad de año.

## D-006 — Rango máximo

366 días (`hasta - desde <= 365`). Una baja larga se registra abierta y se
cierra con el alta; una solicitud de más de un año es un error de tecleo.
