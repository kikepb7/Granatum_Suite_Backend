package com.granatum.core.domain.exception

import com.granatum.core.domain.type.RequisitoIncumplido

/**
 * This feature's business exceptions, mapped to stable error codes in
 * `AuthExceptionHandler`.
 *
 * ## Why two of these names are so long
 *
 * `CuentaDeEmpleadoInactivoException` and
 * `EmpleadoNoEncontradoEnDirectorioException` are named that way to avoid
 * colliding with `EmpleadoInactivoException` and `EmpleadoNotFoundException`,
 * which **already exist** in `timetracking` inside this very package.
 *
 * It is not a question of style. Every module shares the `com.granatum.core`
 * namespace by decision of the constitution, and each is packaged into its own
 * jar; in `app`'s classpath both are present. Two classes with the same
 * qualified name mean **only one is loaded** and the other is shadowed.
 * Concretely, with `EmpleadoInactivoException`:
 *
 *  - `timetracking`'s extends `InvalidOperationException` -> HTTP **400**;
 *  - the one this feature needs extends `UnauthorizedException` -> HTTP **401**.
 *
 * `auth` would compile against its own and the build would pass, but at runtime
 * `timetracking`'s would be resolved and a rejection for dismissal would answer
 * `400 INVALID_OPERATION` instead of `401 EMPLEADO_INACTIVO` - or blow up with
 * `NoSuchMethodError` if the constructors differ. **No module-level test can see
 * this**, because only its own class is on its own classpath. It is the same
 * shape of failure as the Jackson 3 outage in feature 001: suite green,
 * application broken. `SinColisionDeClasesIT` in `app` is what guards it.
 *
 * ## No message carries a secret
 *
 * Error bodies reach clients and logs alike (principle VI), so no message here
 * contains an email address, a password, a token, or any fragment of one.
 */

/**
 * Every unsuccessful sign-in, whatever the real reason: unknown email, wrong
 * password, dismissed person, **and locked account**.
 *
 * One exception for four causes is deliberate and is FR-003: the response has to
 * be indistinguishable in code, body and time, or sign-in becomes an oracle that
 * reveals which addresses are registered. Including the lockout is this plan's
 * own decision, with a cost accepted openly - someone who mistypes five times
 * does not learn they must wait a minute.
 */
class CredencialesInvalidasException :
    UnauthorizedException("Credenciales invalidas")

/** Unknown, expired, already used or revoked - all four look the same (FR-008). */
class TokenRenovacionInvalidoException :
    UnauthorizedException("El token de renovacion no es valido")

/**
 * The person is no longer employed, rejected at renewal time (FR-010).
 *
 * Distinguishable here, unlike at sign-in: whoever presents a valid refresh
 * token has already proved they hold the account, so there is no existence left
 * to hide from them - and knowing the rejection is a dismissal rather than an
 * expired session saves them retyping their password over and over.
 */
class CuentaDeEmpleadoInactivoException :
    UnauthorizedException("La persona empleada esta inactiva")

/** FR-029c: no person is registered under that id. */
class EmpleadoNoEncontradoEnDirectorioException(empleadoId: Any) :
    NotFoundException("No existe ninguna persona empleada con el id $empleadoId")

class CuentaNoEncontradaException :
    NotFoundException("Esa persona no tiene cuenta de acceso")

/** FR-027, FR-029d: the way out of this situation is to reset, not to recreate. */
class CuentaYaExisteException :
    InvalidOperationException("Esa persona ya tiene cuenta de acceso")

class EmailYaRegistradoException :
    InvalidOperationException("Ese correo ya pertenece a otra cuenta")

/**
 * FR-023. Carries the list of failed requirements and **never the password**,
 * not even a fragment of it.
 */
class PasswordDebilException(val requisitos: List<RequisitoIncumplido>) :
    InvalidOperationException("La contrasena no cumple la politica")

/**
 * The hash semaphore is full. See `VerificadorAcotado`: it is the price of
 * bounding memory on a public endpoint, and it answers 503 with `Retry-After`
 * because the condition is transient by definition.
 */
class VerificacionSaturadaException :
    InvalidOperationException("Servicio saturado, reintentelo en unos segundos")
