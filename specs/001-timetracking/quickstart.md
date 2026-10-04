# Phase 1 — Guía de validación: 001-timetracking

Escenarios ejecutables que demuestran que la feature funciona de punta a punta.
Esto es una guía de validación, no de implementación: el detalle del contrato
está en [`contracts/`](contracts/) y el del modelo en
[`data-model.md`](data-model.md).

---

## Requisitos previos

```bash
docker compose up -d
```

```bash
./gradlew build
```

El build debe quedar en verde, incluidas las migraciones nuevas aplicadas sobre
el Postgres local y `RowLevelSecurityIT` confirmando que ninguna tabla del
módulo se quedó sin RLS.

```bash
./gradlew :app:bootRun
```

Token de desarrollo con el rol que haga falta (perfil `dev`):

```bash
TOKEN_ADMIN=$(curl -s -X POST "http://localhost:8080/api/dev/token?role=ADMIN" | jq -r .accessToken)
```

Para fichar como una persona concreta hace falta que el sujeto del token sea su
identificador de empleado, porque la identidad sale del token y no del cuerpo:

```bash
TOKEN_EMPLEADO=$(curl -s -X POST "http://localhost:8080/api/dev/token?role=EMPLEADO&subject=$EMPLEADO_ID" | jq -r .accessToken)
```

---

## Escenario 1 — Jornada completa con dos pausas (SC-001)

Es el criterio de éxito principal. Alta, entrada a las 07:00, dos pausas que
suman 60 minutos, salida a las 16:00 → **480 minutos**.

```bash
EMPLEADO_ID=$(curl -s -X POST http://localhost:8080/api/empleados \
  -H "Authorization: Bearer $TOKEN_ADMIN" -H "Content-Type: application/json" \
  -d '{"nombre":"Maria Lopez","documentoIdentidad":"12345678Z","puesto":"Florista","tipoContrato":"JORNADA_COMPLETA","fechaAlta":"2026-10-01"}' \
  | jq -r .id)
```

Entrada, pausas y salida con `clientEventId` distinto en cada operación y
`occurredAt` dentro de la tolerancia de reloj (ver `contracts/fichajes.md`).

**Resultado esperado**: el fichaje queda `CERRADO` con `minutosTrabajados: 480`
y dos pausas cerradas. Si sale otro número, el cálculo de FR-007 está mal.

**Comprobación adicional sin pausas** (FR-005): una jornada de 07:00 a 15:00 sin
ninguna pausa debe dar 480 también.

---

## Escenario 2 — Un fichaje cerrado no se puede modificar (SC-002)

El invariante legal del principio III. **No hay ningún endpoint de modificación
directa**: la prueba es que no existe.

```bash
curl -i -X PUT http://localhost:8080/api/fichajes/$FICHAJE_ID \
  -H "Authorization: Bearer $TOKEN_ADMIN" -H "Content-Type: application/json" \
  -d '{"salida":"2026-10-05T20:00:00Z"}'
```

**Resultado esperado**: `405` o `404`. Lo que **no** debe ocurrir nunca es un
`200` con el fichaje alterado.

Lo que verifica de verdad el invariante es el test de integración: comprueba que
tras intentar todas las vías de escritura, los valores del fichaje y sus minutos
siguen idénticos. `/speckit-analyze` debe confirmar que ese test existe.

---

## Escenario 3 — Corrección aprobada, original conservado (SC-003)

```bash
CORRECCION_ID=$(curl -s -X POST http://localhost:8080/api/fichajes/$FICHAJE_ID/correcciones \
  -H "Authorization: Bearer $TOKEN_EMPLEADO" -H "Content-Type: application/json" \
  -d '{"motivo":"Olvide fichar la pausa de comida","valoresPropuestos":{"entrada":"2026-10-05T07:00:00Z","salida":"2026-10-05T16:00:00Z","pausas":[{"tipo":"COMIDA","inicio":"2026-10-05T11:00:00Z","fin":"2026-10-05T11:45:00Z"}]}}' \
  | jq -r .id)
```

```bash
curl -s -X POST http://localhost:8080/api/correcciones/$CORRECCION_ID/aprobar \
  -H "Authorization: Bearer $TOKEN_ADMIN" | jq '{estado, resueltaPorId, resueltaEn, valoresOriginales}'
```

**Resultado esperado**: `estado: "APROBADA"`, `resueltaPorId` y `resueltaEn`
rellenos, `valoresOriginales` con el estado anterior, y los minutos del fichaje
recalculados descontando la pausa añadida.

**Caso negativo (FR-020a)**: una propuesta que incluya `ubicacion` debe devolver
`422 UBICACION_NO_CORREGIBLE`.

**Caso negativo (FR-019)**: aprobar otra vez la misma solicitud debe devolver
`409 SOLICITUD_YA_RESUELTA`.

---

## Escenario 4 — Un empleado no ve lo de otro (SC-004)

```bash
curl -i "http://localhost:8080/api/fichajes/empleado/$OTRO_EMPLEADO_ID?desde=2026-10-01&hasta=2026-10-31" \
  -H "Authorization: Bearer $TOKEN_EMPLEADO"
```

**Resultado esperado**: `403`. Es el escenario que demuestra FR-022: la
identidad se toma del token y el identificador de la ruta no es fuente de
autoridad. Un `200` aquí es a la vez un fallo de autorización y una brecha de
datos personales.

Con `TOKEN_ENCARGADO` la misma petición debe devolver `200`.

---

## Escenario 5 — Reenvío que no duplica (SC-005)

El escenario que justifica la idempotencia. **La misma petición, dos veces**:

```bash
BODY='{"clientEventId":"11111111-1111-1111-1111-111111111111","occurredAt":"2026-10-07T07:00:00Z"}'
curl -s -X POST http://localhost:8080/api/fichajes/entrada -H "Authorization: Bearer $TOKEN_EMPLEADO" -H "Content-Type: application/json" -d "$BODY"
curl -s -X POST http://localhost:8080/api/fichajes/entrada -H "Authorization: Bearer $TOKEN_EMPLEADO" -H "Content-Type: application/json" -d "$BODY"
```

**Resultado esperado**: **el mismo `id` de fichaje** en las dos respuestas, y un
único fichaje en la base de datos. Si la segunda devolviera `409
FICHAJE_YA_EN_CURSO`, la idempotencia no está implementada: estaría tratando el
reintento como una entrada nueva.

**Caso negativo**: el mismo `clientEventId` con un `occurredAt` distinto debe
devolver `409 CLIENT_EVENT_ID_REUTILIZADO`.

**Hora del hecho frente a hora de llegada (FR-025)**: con un `occurredAt` de
hace dos horas, el fichaje debe constar con esa hora, no con la de recepción.

**Desviación de reloj (FR-026)**: `occurredAt` 10 minutos en el futuro →
`422 DESVIACION_RELOJ`. Con 48 horas en el pasado → se acepta. Con 5 días en el
pasado → `422`.

---

## Escenario 6 — Fichaje olvidado pasa a INCOMPLETO (SC-009)

El proceso programado se ejecuta una vez al día, así que para validarlo a mano
hay que invocar el componente directamente desde un test de integración en lugar
de esperar a su hora.

**Resultado esperado**: un fichaje `EN_CURSO` cuya entrada es de un día anterior
pasa a `INCOMPLETO` con `fueIncompleto: true`; uno cuya entrada es de hoy **no
se toca**.

Después, esa persona debe poder fichar entrada con normalidad (FR-012a): el
`INCOMPLETO` no cuenta como jornada en curso. Y al completarlo con una
corrección aprobada, pasa a `CERRADO` conservando `fueIncompleto: true`
(SC-010), que es lo que permite distinguir una jornada reconstruida de una
cerrada en su momento.

---

## Escenario 7 — Medianoche y cambio de hora (SC-006)

Casos que un mock de base de datos no detecta; van obligatoriamente contra
Postgres real.

| Caso | Entrada | Salida | Minutos esperados |
|------|---------|--------|-------------------|
| Cruza medianoche | 2026-10-07 22:00 Madrid | 2026-10-08 06:00 Madrid | 480 |
| Cambio a horario de invierno | 2026-10-25 00:00 Madrid | 2026-10-25 08:00 Madrid | **540** |

El segundo es el importante: la madrugada del 25 de octubre de 2026 el reloj
retrocede una hora, así que entre las 00:00 y las 08:00 civiles transcurren
**9 horas reales**. Si el resultado fuesen 480, el almacenamiento no está
preservando el instante absoluto y el registro legal sería incorrecto.

La jornada que cruza la medianoche debe aparecer **solo** en el día de su
entrada al consultar por rango, no en los dos.

---

## Escenario 8 — RLS en las tablas nuevas (principio VII)

```bash
./gradlew :inventory:test --tests "*RowLevelSecurityIT"
```

**Resultado esperado**: verde. Ese test recorre **todas** las tablas del esquema
`public`, así que cubre automáticamente las cinco nuevas de este módulo sin
tocarlo. Si una migración olvida el `ENABLE ROW LEVEL SECURITY`, falla ahí con el
nombre de la tabla.

Importa más que en inventario: estas tablas contienen DNI, ubicación y jornada
—datos personales—, y la API de datos de Supabase las publicaría.

---

## Resumen de cobertura

| Criterio | Escenario |
|----------|-----------|
| SC-001 horas correctas con 2 pausas | 1 |
| SC-002 fichaje cerrado inmutable | 2 |
| SC-003 original recuperable con autoría | 3 |
| SC-004 aislamiento entre empleados | 4 |
| SC-005 reenvío sin duplicar | 5 |
| SC-006 medianoche y cambio de hora | 7 |
| SC-009 paso a INCOMPLETO | 6 |
| SC-010 jornada reconstruida distinguible | 6 |

SC-007 y SC-008 son objetivos de latencia: se miden en el escenario 1 y en el 4
respectivamente, pero no son verificables de forma fiable a mano en local.
