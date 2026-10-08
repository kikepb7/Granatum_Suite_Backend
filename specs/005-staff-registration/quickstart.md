# Quickstart: validar el registro

**Spec**: [spec.md](./spec.md) | **Contrato**: [contracts/README.md](./contracts/README.md)

## 0. Preparación

Base de datos **sin cuentas**, y un código de arranque en `.env` (nunca en un
fichero versionado):

```bash
echo "AUTH_CODIGO_ARRANQUE=$(openssl rand -base64 24)" >> .env
docker compose up -d
./gradlew :app:bootRun
A=http://localhost:8080/api/auth
CODIGO=$(grep ^AUTH_CODIGO_ARRANQUE= .env | cut -d= -f2-)
```

## 1. Primer `ADMIN` — US1

```bash
curl -s -X POST $A/registro -H 'Content-Type: application/json' -d '{"email":"jefe@granatum.es","password":"Granatum-Jefe-2026!","nombre":"Jefe","documentoIdentidad":"12345678Z","codigoArranque":"'$CODIGO'"}'
ADMIN=$(curl -s -X POST $A/login -H 'Content-Type: application/json' -d '{"email":"jefe@granatum.es","password":"Granatum-Jefe-2026!"}' | jq -r .accessToken)
```

**Esperado**: `201` con `ACTIVA`, y el login da un token con rol `ADMIN`. Repetir
el arranque con otro correo → `403 CODIGO_ARRANQUE_INVALIDO`.

## 2. Registro y aprobación — US2

```bash
curl -s -X POST $A/registro -H 'Content-Type: application/json' -d '{"email":"ana@granatum.es","password":"Ana-Florista-2026!","nombre":"Ana","documentoIdentidad":"00000000T"}'
```

**Esperado**: `202` con un `codigoVerificacion`. El login de Ana → `401`.

```bash
curl -s $A/registros -H "Authorization: Bearer $ADMIN" | jq
curl -s -X POST $A/registros/<id>/aprobar -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"codigoVerificacion":"<código>","rol":"EMPLEADO","puesto":"Florista","tipoContrato":"PARCIAL","fechaAlta":"2026-10-01"}'
```

**Esperado**: la lista muestra a Ana con `empleadoExistenteId: null`; con un
código incorrecto → `422 CODIGO_INCORRECTO`; con el bueno → `201`
`fichaCreada: true`. Ahora el login de Ana funciona.

## 3. Sin enumeración — US3

Registrar otra vez `ana@granatum.es`: misma respuesta `202` que un correo nuevo y
ninguna solicitud nueva en la lista.

## 4. Rechazo y conservación — US4, US5

Registrar a alguien, rechazarlo (`204`) y comprobar en la base de datos que la
fila está `RECHAZADA` con correo, nombre, documento y huellas a `NULL`.

## 5. Solo `ADMIN`

`GET $A/registros` con un token de `ENCARGADO` → `403`; sin token → `401`.
