# Quickstart: validar la exportación del registro de jornada

**Spec**: [spec.md](./spec.md) | **Contrato**: [contracts/README.md](./contracts/README.md) | **Decisiones**: [research.md](./research.md)

Guía para comprobar la feature contra la aplicación en marcha. Los detalles de
cada respuesta están en el contrato y no se repiten aquí.

> Usa **instantes recientes** al fichar: el validador de reloj rechaza
> desviaciones de más de 72 horas. Es la lección del quickstart de la feature 001.

## 0. Preparación

```bash
docker compose up -d
./gradlew :app:bootRun
```

Datos de partida, reutilizando el quickstart de la feature 002: una persona con
cuenta, que ha fichado al menos tres jornadas (una normal, una corregida y
aprobada, y una abierta), y una segunda persona dada de baja con fichajes.

```bash
ADMIN=$(curl -s -X POST 'http://localhost:8080/api/dev/token?role=ADMIN' | jq -r .accessToken)
REPR=$(curl -s -X POST 'http://localhost:8080/api/dev/token?role=REPRESENTANTE' | jq -r .accessToken)
# ACCESO = token de la persona empleada, obtenido con POST /api/auth/login
```

## 1. Mi propio registro — US1, SC-001, SC-002

```bash
curl -s -D cabeceras.txt -o mio.csv \
  "http://localhost:8080/api/fichajes/export?desde=2026-10-01&hasta=2026-10-31" \
  -H "Authorization: Bearer $ACCESO"
grep -i 'content-disposition' cabeceras.txt     # nombre con el id, no con el nombre
head -c 3 mio.csv | xxd                          # ef bb bf: el BOM
```

**Esperado**: una fila por fichaje. La jornada abierta tiene las horas **vacías**,
no `0`. La corregida muestra los valores vigentes, los originales y quién la
solicitó y quién la aprobó. Pidiendo el `empleadoId` de otra persona → `403`.

**Ábrelo en Excel o LibreOffice con configuración española** (SC-005): columnas
separadas sin asistente de importación y tildes correctas.

## 2. Descarga mensual — US2, SC-003

```bash
curl -s -o mes.csv \
  "http://localhost:8080/api/fichajes/empleado/$EMPLEADO/resumen/descarga?anio=2026&mes=10" \
  -H "Authorization: Bearer $ACCESO"
tail -4 mes.csv
curl -s "http://localhost:8080/api/fichajes/empleado/$EMPLEADO/resumen?anio=2026&mes=10" \
  -H "Authorization: Bearer $ACCESO" | jq .totalMinutosTrabajados
```

**Esperado**: el total del fichero es **exactamente** el del resumen en pantalla.
Como el mes no ha terminado, `Mes cerrado;No`.

## 3. Toda la plantilla — US3, SC-008, SC-011

```bash
for i in 1 2; do
  curl -s -o plantilla-$i.csv "http://localhost:8080/api/fichajes/export?desde=2026-10-01&hasta=2026-10-31" \
    -H "Authorization: Bearer $ADMIN"
done
cmp plantilla-1.csv plantilla-2.csv && echo "idénticos"
```

**Esperado**: `idénticos` (FR-011). La persona dada de baja aparece. Con el token
de la persona empleada, la misma llamada **no** da la plantilla: un `EMPLEADO` sin
`empleadoId` recibe solo su propio registro (FR-013), y con el `empleadoId` de otra
persona, `403`.

## 4. Representación legal — US4, SC-004

```bash
curl -s -o repr.csv "http://localhost:8080/api/fichajes/export?desde=2026-10-01&hasta=2026-10-31" \
  -H "Authorization: Bearer $REPR"
head -1 repr.csv | tr ';' '\n' | grep -c Documento    # 0
```

**Esperado**: no existe la columna `Documento` y no aparece ninguna ubicación.

## 5. Inyección de fórmulas — SC-006

Crea una persona llamada `=HYPERLINK("http://ejemplo","x")`, ficha y exporta.
**Esperado**: al abrir el fichero el nombre aparece como texto, precedido de `'`.

## 6. Auditoría y verificación — US5, SC-007, SC-008

```bash
curl -s "http://localhost:8080/api/exportaciones" -H "Authorization: Bearer $ADMIN" | jq '.[0]'

curl -s -X POST "http://localhost:8080/api/exportaciones/verificar" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: text/csv' \
  --data-binary @plantilla-1.csv | jq '{coincide, n: (.exportaciones | length)}'

sed 's/8:30/8:31/' plantilla-1.csv > alterado.csv
curl -s -X POST "http://localhost:8080/api/exportaciones/verificar" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: text/csv' \
  --data-binary @alterado.csv | jq .coincide
```

**Esperado**: el registro de la exportación sin nombres ni documentos; el fichero
original coincide (con **dos** exportaciones, las dos del apartado 3); el alterado
→ `false`.

> El nombre de quien aprobó una corrección sale del registro de personas. Con un
> token de `/api/dev/token` sin `subject`, el aprobador no es ninguna persona y en
> la columna *Correcciones* aparece su id en lugar del nombre. Con cuentas reales
> sale el nombre.

## 7. Rango que llega más allá del plazo — FR-017

```bash
curl -s -D - -o /dev/null "http://localhost:8080/api/fichajes/export?desde=2015-01-01&hasta=2026-10-31" \
  -H "Authorization: Bearer $ADMIN" | grep -iE 'x-registro|content-disposition'
```

**Esperado**: `X-Registro-Disponible-Desde` con la fecha de hace cuatro años, y el
nombre del fichero empezando en esa fecha, no en 2015.

## 8. Errores de validación con el formato del contrato — D-019

```bash
curl -s "http://localhost:8080/api/fichajes/export?desde=2026-10-31&hasta=2026-10-01" -H "Authorization: Bearer $ADMIN"
curl -s "http://localhost:8080/api/fichajes/export" -H "Authorization: Bearer $ADMIN"
curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"no-es-correo","password":"x"}'
```

**Esperado**: los tres con `{"code": …, "message": …}` —el primero
`422 VALORES_INCOHERENTES`, los otros dos `400 VALIDACION`— y **ninguno con traza
ni con el valor enviado**. Antes de esta feature, los dos
últimos devolvían el cuerpo por defecto de Spring con la traza completa.

## 9. Rendimiento — SC-009

Con 100 personas sintéticas y un año de fichajes:

```bash
time curl -s -o /dev/null "http://localhost:8080/api/fichajes/export?desde=2025-10-01&hasta=2026-09-30" \
  -H "Authorization: Bearer $ADMIN"
```

**Esperado**: menos de 30 s. Medido el 2026-10-07: **0,7–0,8 s** para 26.100 filas
(2,8 MB). Detalle en `research.md` (D-018).

## 10. RLS — principio VII

```bash
# El nombre del contenedor depende de cómo se levantó: `docker ps` lo dice.
docker exec granatum_suite_backend-postgres-1 psql -U granatum -d granatum -tAc \
  "SELECT relname, relrowsecurity FROM pg_class WHERE relname = 'exportaciones'"
```

**Esperado**: `exportaciones|t`.
