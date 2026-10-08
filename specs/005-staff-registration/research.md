# Research: Registro del personal

**Fecha**: 2026-10-08 | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

No quedaban incógnitas técnicas en el plan: todas las piezas existen ya en la
002. Lo que sigue son las decisiones de diseño y su porqué.

## D-001 — Aprobación con código de verificación, no por correo

**Decisión**: al registrarse, la persona recibe un código de 8 caracteres del
alfabeto `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (32 símbolos, sin `0/O`, `1/I`). El
`ADMIN` lo pide en persona y aprobar lo exige. Se guarda solo
`SHA-256(id_solicitud ‖ código)` y se compara en tiempo constante.

**Por qué**: sin correo saliente, nada demuestra que quien se registró con el
nombre y el correo de una compañera sea ella. El código es la prueba de que la
persona que el `ADMIN` tiene delante es la que hizo **esa** solicitud. Por eso
también se permiten varias solicitudes con el mismo correo (FR-006): el
impostor y la persona real tienen códigos distintos, y solo se aprueba la del
código que el `ADMIN` oye.

**Por qué SHA-256 y no Argon2**: el código vive 7 días como máximo, son 40 bits y
solo sirve para que un `ADMIN` —que ya tiene todo el poder— apruebe. Gastar 110
ms y 64 MiB por comprobación no compraría seguridad. La sal es el id de la
solicitud, para que dos solicitudes con el mismo código no tengan la misma huella.

**Fuerza bruta**: 5 intentos fallidos anulan la solicitud (FR-018). La
probabilidad de acertar en 5 intentos es 5/2⁴⁰.

**Alternativas**: correo con enlace (fuera de alcance, sin SMTP); aprobar a ciegas
(permite suplantar); que el `ADMIN` genere el código (invierte el sentido: el
`ADMIN` no sabe quién se registró).

## D-002 — Código de arranque para el primer `ADMIN`

**Decisión**: `AUTH_CODIGO_ARRANQUE`, variable de entorno **sin valor por
defecto**. Si está vacía, el arranque está desactivado. Un registro con
`codigoArranque` se resuelve así, bajo un **bloqueo consultivo de transacción**
(`pg_advisory_xact_lock`) para serializar dos arranques simultáneos:

1. Si no hay código configurado, o ya existe una cuenta `ADMIN`, o el código no
   coincide (comparación en tiempo constante) → `403 CODIGO_ARRANQUE_INVALIDO`,
   sin crear nada. Las tres causas responden igual: no se informa de si ya hay
   `ADMIN`.
2. Si el correo ya tiene cuenta → `409 EMAIL_YA_REGISTRADO`. Aquí no hay
   enumeración que proteger: quien tiene el código de arranque ya es quien
   despliega.
3. Ficha por documento o ficha nueva (D-005) y cuenta `ADMIN` con la contraseña
   elegida, sin cambio obligatorio. Las solicitudes pendientes con ese correo
   quedan `ANULADA` (FR-014).

**Por qué un código y no "el primero que llegue"**: en una instalación recién
desplegada y expuesta, el primero que llega puede ser cualquiera. **Por qué no
sembrar con correo y contraseña por entorno**: deja una contraseña en la
configuración del despliegue que alguien tiene que acordarse de rotar; el código
deja de servir solo, en cuanto hay un `ADMIN`.

**Por qué bloqueo consultivo**: sin él, dos arranques con correos distintos
leerían "no hay `ADMIN`" a la vez y crearían dos. Una restricción única no lo
impide porque son filas distintas.

## D-003 — Mismo tiempo con correo nuevo o existente

**Decisión**: el registro hace siempre el mismo trabajo caro —validar, generar el
código, **cifrar la contraseña con Argon2**— antes de mirar si el correo existe.
Si ya tiene cuenta, no se guarda nada y la respuesta es la misma `202` con un
código de la misma forma (que no corresponde a nada). Se registra el evento
`REGISTRO_DUPLICADO`, que solo ve quien consulta la base de datos.

**Por qué**: el hash es ~110 ms; insertar una fila, unos pocos milisegundos. Con
el hash en los dos caminos, la diferencia queda muy por debajo del 10% de SC-004.
Es el mismo principio que el login de la 002 con su hash ficticio.

**Medición**: `IndistinguibilidadRegistroIT` compara medias de 50 + 50 registros.

## D-004 — Límite de pendientes

**Decisión**: `auth.registro.max-pendientes` (50 por defecto). Si hay ese número
de pendientes, el registro responde `503 REGISTRO_NO_DISPONIBLE` con
`Retry-After`, se trate del correo que se trate (no delata nada). El arranque no
cuenta para el límite.

**Por qué**: el registro es público y escribe en la base de datos; sin límite, un
bucle llenaría la tabla. Lo que limita el **ritmo** desde un mismo origen es la
feature 006; esto acota el **volumen** aunque el ataque venga de muchos orígenes.

## D-005 — Contrato `FichasPersonal` en `common`

**Decisión**:

```kotlin
interface FichasPersonal {
    fun buscarPorDocumento(documento: String): EntityId?
    fun crear(alta: AltaFichaPersonal): EntityId
}
data class AltaFichaPersonal(nombre, documento, puesto, tipoContrato: String, fechaAlta: LocalDate)
```

Lo implementa `timetracking` con su `EmpleadoService.crear`, que ya normaliza y
comprueba la unicidad del documento. `tipoContrato` viaja como texto porque el
enum es de `timetracking`; `auth` valida en el borde que sea uno de
`JORNADA_COMPLETA`, `PARCIAL`, `POR_HORAS`.

**Por qué no ampliar `DirectorioEmpleados`**: ese contrato se diseñó
deliberadamente sin nombres ni documentos ("lo que no ofrece"). Mezclar lectura
mínima con escritura de datos personales lo desvirtuaría. Uno nuevo, con un
propósito, se audita de un vistazo.

**Transacción**: aprobar y arrancar crean ficha y cuenta en la **misma**
transacción (FR-019). La implementación es `@Transactional` con propagación por
defecto, así que se une a la de `auth`. No hay hash dentro: la contraseña se
cifró al registrarse.

## D-006 — Concurrencia de la aprobación

**Decisión**: aprobar y rechazar leen la solicitud con `SELECT … FOR UPDATE`.
Quien llega segundo la ve ya resuelta y recibe `409 SOLICITUD_NO_PENDIENTE`.

**Código incorrecto**: el incremento de `intentos_codigo` (y la anulación al
quinto) tiene que **quedar escrito aunque la petición falle**. Se hace en una
transacción que se confirma y, después, se lanza la excepción `422
CODIGO_INCORRECTO` (o `409 SOLICITUD_NO_PENDIENTE` si con ese intento se anuló).

## D-007 — Datos personales solo mientras está pendiente

**Decisión**: al pasar a cualquier estado final se ponen a `NULL` correo, nombre,
documento, huella de contraseña y huella de código. Dos `CHECK` en la tabla lo
garantizan (ver data-model). La fila no se borra nunca: queda estado, fechas,
quién resolvió y, si se aprobó, la cuenta.

**Caducidad**: un job diario (`auth.registro.caducidad-cron`, 04:50 Madrid)
caduca las pendientes de más de `auth.registro.caducidad-dias` (7) con una sola
sentencia `UPDATE`.

## D-008 — Ficha del primer `ADMIN`

**Decisión**: si no hay ficha con su documento, se crea con puesto `Dirección`,
`JORNADA_COMPLETA` y fecha de alta del día (Europe/Madrid). El `ADMIN` la corrige
con `PUT /api/empleados/{id}`.

**Por qué no pedirlos en el registro**: el formulario es el mismo para toda la
plantilla, y esos datos los decide el `ADMIN` al aprobar. Pedirlos solo cuando se
aporta el código de arranque complicaría el contrato para un caso que ocurre una
vez por instalación.

## D-009 — Eventos de seguridad nuevos

`REGISTRO_SOLICITADO`, `REGISTRO_DUPLICADO`, `ADMIN_INICIAL_CREADO`,
`ARRANQUE_RECHAZADO`, `REGISTRO_APROBADO`, `REGISTRO_RECHAZADO`,
`REGISTRO_ANULADO`, `REGISTRO_CADUCADO`. Sin datos personales: `cuenta_id` cuando
hay cuenta, nada cuando no. V21 sustituye el `CHECK` de la V14 por uno con la
lista ampliada.

## D-010 — Olvido de la contraseña del único `ADMIN`

No hay recuperación por correo (fuera de alcance) y el código de arranque no
sirve porque ya existe un `ADMIN`. **Recomendación operativa**, documentada en el
README: aprobar pronto un segundo `ADMIN`. Si aun así ocurre, el procedimiento
exige acceso a la base de datos, y no hace falta ninguna herramienta aparte para
generar el hash:

1. Registrarse con un correo cualquiera y la contraseña nueva: la solicitud
   pendiente guarda su hash Argon2, generado por la propia aplicación.
2. Copiar ese `password_hash` a la cuenta del `ADMIN` en `cuentas_acceso`, con
   `requiere_cambio_password = false` y el bloqueo a cero.
3. Dejar que la solicitud caduque, o anularla poniendo sus datos a `NULL`.

Así nunca viaja una contraseña en claro ni un hash calculado fuera.
