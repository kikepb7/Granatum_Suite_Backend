package com.granatum.core.service

import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.domain.model.ParTokens
import com.granatum.core.domain.type.EstadoEmpleado
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.crypto.HashSenuelo
import com.granatum.core.infrastructure.crypto.VerificadorAcotado
import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import com.granatum.core.infrastructure.database.entities.SesionRenovacionEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import com.granatum.core.infrastructure.database.repositories.SesionRenovacionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Signing in (US1): FR-001 to FR-005.
 *
 * ## Every failure looks the same
 *
 * Unknown email, wrong password and dismissed person all leave as
 * [CredencialesInvalidasException], with the same code and the same body. That
 * is FR-003 and SC-002: anything else turns sign-in into an oracle that reveals
 * which addresses are registered.
 *
 * ## And takes the same time
 *
 * A password hash is verified on **every** path - against the real hash when the
 * account exists, against the decoy when it does not. Without the decoy the
 * "no such account" path would skip ~110 ms of Argon2 and the difference would
 * be trivial to measure from outside.
 *
 * The decoy is verified for the dismissed-person case too, even though the
 * account exists: the check happens after the password has been verified
 * anyway, so the timing already matches.
 *
 * ## Why `iniciarSesion` is not transactional
 *
 * On purpose, and it is the single most important structural decision here.
 * Argon2 takes ~110 ms, and a transaction open across it would hold a database
 * connection - and, once the lockout transition took a row lock, that lock too -
 * for the whole verification. Someone hammering one address would then make that
 * person's own sign-ins queue behind theirs, turning the hardening into the
 * amplifier of a denial of service.
 *
 * So the verification runs with no transaction held, and the lockout transitions
 * live in [GestorBloqueoCuenta], each in its own short transaction. That is also
 * why they are in a separate bean: Spring's proxy does not intercept a call made
 * on `this`, so a `@Transactional` method invoked from inside this class would
 * run with no transaction at all - silently.
 *
 * ## The clock
 *
 * Injected with a default, following the pattern of `timetracking`'s services: a
 * test can hand it a fixed clock without a Spring bean having to exist for it.
 */
@Service
class AutenticacionService(
    private val cuentas: CuentaAccesoRepository,
    private val sesiones: SesionRenovacionRepository,
    private val verificador: VerificadorAcotado,
    private val hashSenuelo: HashSenuelo,
    private val generadorToken: GeneradorTokenRenovacion,
    private val jwtService: JwtService,
    private val directorio: DirectorioEmpleados,
    private val gestorBloqueo: GestorBloqueoCuenta,
    private val eventos: RegistradorEventosSeguridad,
    @param:Value("\${jwt.expiration-minutes}") private val minutosAcceso: Long,
    @param:Value("\${auth.refresh-expiration-days}") private val diasRenovacion: Long,
    private val clock: Clock = Clock.systemUTC()
) {

    fun iniciarSesion(email: String, password: String): ParTokens {
        val cuenta = cuentas.findByEmail(NormalizadorEmail.normalizar(email))

        if (cuenta == null) {
            // The decoy exists for exactly this branch. Removing it would make
            // an unknown address answer measurably faster than a wrong
            // password, which is the enumeration oracle FR-003 closes.
            verificador.coincide(password, hashSenuelo.valor)
            eventos.registrar(TipoEventoSeguridad.LOGIN_CUENTA_DESCONOCIDA)
            throw CredencialesInvalidasException()
        }

        // FR-014: a locked account is refused **even with the correct
        // password**. Rejected before the real hash is verified, on an unlocked
        // read, so no row lock is held while Argon2 runs.
        //
        // The decoy is verified anyway, for two reasons. The timing has to match
        // the other rejections, and - this is the plan's own decision, not the
        // spec's - the response is byte-identical to a wrong password. A
        // specific "account locked" body would reveal that the address exists
        // and would re-open the enumeration oracle FR-003 closes by the side
        // door.
        //
        // The cost is real and worth naming: somebody who mistypes five times
        // does not see "try again in a minute" and has no idea how long to
        // wait. It is a usability loss at the most frustrating moment, accepted
        // in exchange for not leaking which addresses are registered.
        if (cuenta.estadoBloqueo.toModel().estaBloqueada(clock.instant())) {
            verificador.coincide(password, hashSenuelo.valor)
            eventos.registrar(TipoEventoSeguridad.LOGIN_CUENTA_BLOQUEADA, cuenta.id)
            throw CredencialesInvalidasException()
        }

        if (!verificador.coincide(password, cuenta.passwordHash)) {
            // The transition runs in its own short transaction and the events
            // are written here, outside it. Not a style choice: the event
            // recorder opens a REQUIRES_NEW transaction, so writing from inside
            // the locked one would make every thread hold two connections and
            // deadlock the pool under load - see GestorBloqueoCuenta.
            //
            // Distinct event types on purpose: LOGIN_FALLIDO is an attempt,
            // CUENTA_BLOQUEADA is the moment the account closes. An
            // investigation asks for the second and would otherwise have to
            // infer it by counting the first.
            val acabaDeBloquearse = gestorBloqueo.registrarFallo(cuenta.id)
            eventos.registrar(TipoEventoSeguridad.LOGIN_FALLIDO, cuenta.id)
            if (acabaDeBloquearse) {
                eventos.registrar(TipoEventoSeguridad.CUENTA_BLOQUEADA, cuenta.id)
            }
            throw CredencialesInvalidasException()
        }

        // FR-002: checked here and not against a column of our own, because
        // whether someone is employed is a fact owned by `timetracking`. The
        // answer arrives through the `DirectorioEmpleados` contract, so this
        // module never depends on that one (principle I).
        //
        // `null` - the person does not exist at all - is treated exactly like
        // INACTIVO: it means an orphan account, and an orphan account must not
        // let anybody in while FR-029c's sweep is what surfaces it.
        if (directorio.estado(cuenta.empleadoId) != EstadoEmpleado.ACTIVO) {
            eventos.registrar(TipoEventoSeguridad.LOGIN_EMPLEADO_INACTIVO, cuenta.id)
            throw CredencialesInvalidasException()
        }

        // FR-015 and FR-016b: counter and level back to zero.
        gestorBloqueo.registrarExito(cuenta.id)
        eventos.registrar(TipoEventoSeguridad.LOGIN_CORRECTO, cuenta.id)

        // `cuenta` is now stale with respect to the lockout columns, which is
        // harmless: the only fields read below are the role, the employee id
        // and the pending-change flag, and none of them was touched.
        return emitirPar(cuenta)
    }

    /**
     * Mints the pair and opens a session row.
     *
     * The access token's subject is the **empleado id**, not the account id:
     * feature 001 decided the JWT subject is the person whose working time is
     * recorded, so a fichaje is attributed to them with no lookup that could
     * drift. A reset replaces the credential without changing who the fichajes
     * belong to.
     */
    fun emitirPar(cuenta: CuentaAccesoEntity): ParTokens {
        val ahora = clock.instant()

        val accessToken = jwtService.generateAccessToken(
            subject = cuenta.empleadoId,
            role = cuenta.rol,
            requiereCambioPassword = cuenta.requiereCambioPassword
        )

        val refreshToken = generadorToken.generar()
        sesiones.save(
            SesionRenovacionEntity(
                cuenta = cuenta,
                // Only the hash is stored; the value above is the only time it
                // exists in clear (FR-011).
                tokenHash = generadorToken.hash(refreshToken),
                expiraEn = ahora.plus(diasRenovacion, ChronoUnit.DAYS),
                creadaEn = ahora
            )
        )

        return ParTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSegundos = minutosAcceso * 60,
            requiereCambioPassword = cuenta.requiereCambioPassword
        )
    }
}
