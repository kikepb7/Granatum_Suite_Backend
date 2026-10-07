package com.granatum.core.service

import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.domain.exception.CuentaDeEmpleadoInactivoException
import com.granatum.core.domain.exception.TokenRenovacionInvalidoException
import com.granatum.core.domain.model.ParTokens
import com.granatum.core.domain.type.EstadoEmpleado
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import com.granatum.core.infrastructure.database.repositories.SesionRenovacionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Keeping and ending a session (US2): FR-007 to FR-012.
 */
@Service
class SesionService(
    private val sesiones: SesionRenovacionRepository,
    private val cuentas: CuentaAccesoRepository,
    private val generadorToken: GeneradorTokenRenovacion,
    private val directorio: DirectorioEmpleados,
    private val autenticacionService: AutenticacionService,
    private val eventos: RegistradorEventosSeguridad,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Rotates a refresh token: the one presented dies, a new pair is issued.
     *
     * ## The order of the checks is deliberate
     *
     * The employee's status is checked **before** the token is claimed. Claiming
     * first would burn the token of a dismissed person, so their second attempt
     * would answer `TOKEN_RENOVACION_INVALIDO` instead of `EMPLEADO_INACTIVO` -
     * a different error for the same situation, which is confusing and tells
     * them nothing useful. Not burning it costs nothing: they cannot use it
     * either way.
     *
     * ## Why the conditional UPDATE is the guarantee
     *
     * `marcarUsadaSiVigente` carries the whole "still usable" condition in its
     * `WHERE`, so the **database** picks the winner: of two simultaneous
     * requests presenting the same token, one affects a row and the other
     * affects none. Reading the row, checking it here and writing afterwards
     * would leave a window in which both pass and two pairs are issued - which
     * is exactly the hole task T060 of feature 001 had to be rewritten to close.
     * SC-004 asks for 100%, and only this gives it.
     */
    @Transactional
    fun rotar(refreshToken: String): ParTokens {
        val hash = generadorToken.hash(refreshToken)
        val ahora = clock.instant()

        val sesion = sesiones.findByTokenHash(hash)
        if (sesion == null) {
            eventos.registrar(TipoEventoSeguridad.RENOVACION_RECHAZADA)
            throw TokenRenovacionInvalidoException()
        }

        // Captured before the modifying query clears the persistence context.
        val cuentaId = sesion.cuenta.id
        val yaUsada = sesion.usadaEn != null

        // FR-010. Distinguishable from the generic rejection here, unlike at
        // sign-in: whoever presents a valid refresh token has already proved
        // they hold the account, so there is no existence left to hide from
        // them - and knowing the rejection is a dismissal rather than an expired
        // session saves them retyping their password over and over.
        if (directorio.estado(sesion.cuenta.empleadoId) != EstadoEmpleado.ACTIVO) {
            eventos.registrar(TipoEventoSeguridad.LOGIN_EMPLEADO_INACTIVO, cuentaId)
            throw CuentaDeEmpleadoInactivoException()
        }

        if (sesiones.marcarUsadaSiVigente(hash, ahora) != 1) {
            // Zero rows means used, revoked or expired. Reuse of an
            // already-used token is recorded as its own event because it means
            // something different: it is the only signal of possible theft this
            // feature leaves.
            //
            // It does **not** revoke the rest of the chain, although the OAuth
            // security BCP would. The far more frequent cause of reuse is not
            // theft but a mobile client retrying a renewal whose response was
            // lost; revoking the chain would throw somebody out of every device
            // because of a bad network, and here being thrown out means being
            // unable to clock in, which opens a gap in a record that carries
            // legal weight. The scenario that really calls for cutting
            // everything - a lost device - has its own route in FR-025.
            eventos.registrar(
                if (yaUsada) TipoEventoSeguridad.RENOVACION_TOKEN_REUTILIZADO
                else TipoEventoSeguridad.RENOVACION_RECHAZADA,
                cuentaId
            )
            throw TokenRenovacionInvalidoException()
        }

        // Re-read after the clear: the entity captured above is detached now.
        val cuenta = cuentas.findById(cuentaId).orElseThrow { TokenRenovacionInvalidoException() }

        eventos.registrar(TipoEventoSeguridad.RENOVACION_CORRECTA, cuentaId)
        return autenticacionService.emitirPar(cuenta)
    }

    /**
     * Ends one session (FR-009), and **only** that one: the person's other
     * devices keep working (FR-012, SC-006).
     *
     * Idempotent. An unknown or already-revoked token also succeeds, because
     * logging out twice is not a failure - and a `404` here would distinguish
     * tokens that exist from tokens that do not, which is information worth
     * nothing to a legitimate client and worth something to an attacker.
     *
     * The access token stays cryptographically valid until it expires; that
     * window is its lifetime, and the spec puts closing it out of scope.
     */
    @Transactional
    fun cerrar(refreshToken: String) {
        val hash = generadorToken.hash(refreshToken)
        val sesion = sesiones.findByTokenHash(hash)
        val cuentaId = sesion?.cuenta?.id

        val revocadas = sesiones.revocarPorTokenHash(hash, MOTIVO_LOGOUT, clock.instant())

        // Recorded only when something was actually closed. Logging a
        // no-op logout would fill the security log with events that mean
        // nothing happened, which makes the real ones harder to find.
        if (revocadas > 0) {
            eventos.registrar(TipoEventoSeguridad.CIERRE_SESION, cuentaId)
        }
    }

    companion object {
        const val MOTIVO_LOGOUT = "LOGOUT"
    }
}
