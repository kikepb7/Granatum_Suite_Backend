# Quickstart: validar la facturación

**Spec**: [spec.md](./spec.md) | **Contrato**: [contracts/README.md](./contracts/README.md) | **Decisiones**: [research.md](./research.md)

Guía para comprobar la feature contra la aplicación en marcha. Los detalles de
cada respuesta están en el contrato y no se repiten aquí.

Hay dos recorridos: **sin clave de API** (modo manual, apartados 1–8), que
cualquiera puede hacer sin coste, y **con clave** (apartado 9), que llama a Claude
de verdad y cuesta unos céntimos por factura (research.md D-002).

## 0. Preparación

```bash
docker compose up -d
./gradlew :app:bootRun
ADMIN=$(curl -s -X POST 'http://localhost:8080/api/dev/token?role=ADMIN' | jq -r .accessToken)
ENC=$(curl -s -X POST 'http://localhost:8080/api/dev/token?role=ENCARGADO' | jq -r .accessToken)
F=http://localhost:8080/api/facturacion
```

Ten a mano dos o tres facturas reales: una foto de móvil (JPEG), una captura
(PNG) y un PDF, al menos una con dos tipos de IVA.

## 1. Datos de la empresa — FR-029

```bash
curl -s -X PUT $F/empresa -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"razonSocial":"Floristería Granatum S.L.","nif":"B12345674"}' | jq
```

**Esperado**: `reconocimientoActivo: false` sin clave. Con un NIF de control
incorrecto (`B12345675`) → `422 NIF_INVALIDO`.

## 2. Subida — US1, FR-001, FR-002

```bash
curl -s -X POST $F/facturas -H "Authorization: Bearer $ADMIN" \
  -F ficheros=@foto.jpg -F ficheros=@factura.pdf -F ficheros=@notas.txt | jq
```

**Esperado**: `202`, dos `ACEPTADA` y un `FORMATO_NO_ADMITIDO`. Las dos aceptadas
en `PENDIENTE_RECONOCER`. Subir otra vez `foto.jpg` → `DUPLICADA`, con el id de la
primera (SC-006).

## 3. El original es el mismo — FR-025

```bash
ID=<id de la foto>
curl -s -o original.jpg $F/facturas/$ID/original -H "Authorization: Bearer $ADMIN"
cmp foto.jpg original.jpg && echo "idéntico"
```

## 4. Rellenar a mano, avisos y confirmación — US2, FR-010 a FR-014

Con `PUT …/facturas/$ID` (con la `version` que devuelve el `GET`), rellena los
datos con un total que no cuadre y con un NIF de letra incorrecta. **Esperado**: `GET` muestra los avisos `NO_CUADRA` y
`NIF_INVALIDO`, y `…/confirmar` → `422 FACTURA_INCOHERENTE`. Corrige ambos y
confirma → `CONFIRMADA`, con `tipo` deducido del NIF de la empresa (D-014).

Rellena también la del PDF, con fecha de octubre a diciembre de 2026, pero no la
confirmes: queda en `BORRADOR`.

## 5. Reporte — US3, SC-004, SC-010

```bash
curl -s "$F/reportes?periodo=TRIMESTRAL&anio=2026&trimestre=4" -H "Authorization: Bearer $ADMIN" | jq
curl -s -o t4.csv "$F/reportes?periodo=TRIMESTRAL&anio=2026&trimestre=4&formato=csv" -H "Authorization: Bearer $ADMIN"
curl -s -o t4.pdf "$F/reportes?periodo=TRIMESTRAL&anio=2026&trimestre=4&formato=pdf" -H "Authorization: Bearer $ADMIN"
```

**Esperado**: los totales son la suma exacta de lo confirmado, con el IVA por
tipo; la del PDF cuenta en `pendientes`. Una factura sin fecha todavía (sin
reconocer ni rellenar) no pertenece a ningún periodo y no cuenta. El CSV se abre en Excel o
LibreOffice en español sin asistente y con los importes como números. El PDF
muestra las mismas cifras.

## 6. Cierre de trimestre — US6, FR-030 a FR-033

```bash
curl -s -X POST $F/trimestres/2026/4/cerrar -H "Authorization: Bearer $ADMIN"
```

**Esperado**: `409 TRIMESTRE_CON_PENDIENTES` mientras quede el borrador del PDF;
descártalo (`POST …/facturas/{id}/descartar` con su `version`) y cierra. Después, corregir la confirmada → `409 TRIMESTRE_CERRADO`.
Reabrir sin motivo → `400 VALIDACION`; con motivo, sí, y
`GET $F/trimestres?anio=2026` muestra el cierre y la reapertura.

## 7. Solo `ADMIN` — US4, SC-005

```bash
for ruta in empresa facturas "facturas/$ID" "facturas/$ID/original" "reportes?periodo=ANUAL&anio=2026" "trimestres?anio=2026"; do
  curl -s -o /dev/null -w "%{http_code} $ruta\n" "$F/$ruta" -H "Authorization: Bearer $ENC"
done
```

**Esperado**: todo `403`, y sin token `401`.

## 8. RLS — principio VII

```bash
docker compose exec postgres psql -U granatum -d granatum -tAc \
  "SELECT relname, relrowsecurity FROM pg_class WHERE relname IN ('empresa','trimestres','trimestre_eventos','facturas','factura_lineas_iva','factura_documentos','factura_reconocimientos','factura_cambios')"
```

**Esperado**: las ocho con `t`.

## 9. Reconocimiento real — SC-001, SC-002, SC-003

Con `ANTHROPIC_API_KEY` definida (y nunca escrita en ningún fichero versionado),
reinicia la aplicación y sube las facturas reales:

**Esperado**:

- `GET $F/empresa` → `reconocimientoActivo: true`.
- En menos de 30 s cada una pasa a `BORRADOR` (SC-001).
- Los campos legibles están rellenos, la de dos tipos de IVA tiene dos líneas, y
  lo ilegible está vacío y con el aviso `DUDOSO`, nunca inventado.
- Una foto que no es una factura → aviso `NO_ES_FACTURA` y ninguna cifra.
- Registrar una de la subida a la confirmación lleva menos de 2 minutos (SC-003).
- `GET …/historial` muestra el modelo y la propuesta original, aunque luego se
  corrija (FR-005).

**SC-002** (precisión con 30 facturas reales) se mide con el test etiquetado de
research.md D-019, no a mano.
