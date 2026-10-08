package com.granatum.core.service

import com.granatum.core.domain.contract.AltaFichaPersonal
import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.domain.contract.FichasPersonal
import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.domain.exception.CuentaNoEncontradaException
import com.granatum.core.domain.exception.CuentaYaExisteException
import com.granatum.core.domain.exception.DocumentoInvalidoException
import com.granatum.core.domain.exception.EmailYaRegistradoException
import com.granatum.core.domain.exception.EmpleadoNoEncontradoEnDirectorioException
import com.granatum.core.domain.exception.PasswordDebilException
import com.granatum.core.domain.model.ParTokens
import com.granatum.core.domain.service.PoliticaPassword
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.crypto.GeneradorPasswordTemporal
import com.granatum.core.infrastructure.crypto.VerificadorAcotado
import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import com.granatum.core.infrastructure.database.repositories.SesionRenovacionRepository
import com.granatum.core.validation.NifValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate

/**
 * Creating and changing credentials (US4): FR-018 to FR-023b, FR-027, FR-029b
 * to FR-029d.
 *
 * Not transactional at the method level, for the same reason as
 * [AutenticacionService]: Argon2 takes ~110 ms and a transaction held across it
 * would pin a connection for the whole hash. The individual writes are atomic
 * on their own.
 */
@Service
class CuentaAccesoService(
    private val cuentas: CuentaAccesoRepository,
    private val sesiones: SesionRenovacionRepository,
    private val verificador: VerificadorAcotado,
    private val generadorPassword: GeneradorPasswordTemporal,
    private val directorio: DirectorioEmpleados,
    private val escritor: EscritorCredenciales,
    private val autenticacionService: AutenticacionService,
    private val eventos: RegistradorEventosSeguridad,
    // Feature 009: onboarding creates the staff record too, through the
    // contract timetracking implements (principle I).
    private val fichas: FichasPersonal,
    private val transacciones: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC()
) {

    /** The staff record and the account an ADMIN asks for in one onboarding (feature 009). */
    data class DatosAlta(
        val nombre: String,
        val documento: String,
        val puesto: String,
        val tipoContrato: String,
        val fechaAlta: LocalDate,
        val email: String,
        val rol: Role
    ) {
        override fun toString(): String = "DatosAlta(tipoContrato=$tipoContrato, rol=$rol)"
    }

    /** What onboarding produced, and the clear temporary password - the only moment it exists. */
    data class AltaHecha(val cuenta: CuentaAccesoEntity, val fichaCreada: Boolean, val passwordTemporal: String) {
        override fun toString(): String = "AltaHecha(cuentaId=${cuenta.id}, fichaCreada=$fichaCreada)"
    }

    /**
     * An ADMIN onboards a person in one operation: staff record and account,
     * with a generated temporary password that must be changed on first
     * sign-in (feature 009, FR-004 to FR-009).
     *
     * ## Order
     *
     * The temporary password is generated and hashed first, outside any
     * transaction - Argon2 takes ~110 ms and must not pin a connection. Then,
     * in one transaction: the address is free, the staff record with this
     * document is found or created, it has no account yet, and the account is
     * stored. Any refusal rolls the whole thing back: never a staff record
     * without its account because the email was taken (FR-009).
     *
     * A record that already exists is linked rather than duplicated (FR-007):
     * the ADMIN may have registered the person in the staff register earlier,
     * before they needed access.
     */
    fun darDeAlta(datos: DatosAlta): AltaHecha {
        val documento = NifValidator.normalizar(datos.documento)
        if (!NifValidator.esDniONieValido(documento)) throw DocumentoInvalidoException()
        val email = NormalizadorEmail.normalizar(datos.email)

        val temporal = generadorPassword.generar()
        val hash = verificador.codificar(temporal)

        val (cuenta, fichaCreada) = transacciones.execute {
            if (cuentas.existsByEmail(email)) throw EmailYaRegistradoException()

            val existente = fichas.buscarPorDocumento(documento)
            if (existente != null && cuentas.existsByEmpleadoId(existente)) throw CuentaYaExisteException()
            val empleadoId = existente ?: fichas.crear(
                AltaFichaPersonal(datos.nombre.trim(), documento, datos.puesto.trim(), datos.tipoContrato, datos.fechaAlta)
            )

            cuentas.save(
                CuentaAccesoEntity(
                    empleadoId = empleadoId,
                    email = email,
                    rol = datos.rol,
                    passwordHash = hash,
                    // FR-006: the ADMIN knows this password; only the person's
                    // own, set on first sign-in, is theirs.
                    requiereCambioPassword = true
                )
            ) to (existente == null)
        }!!

        eventos.registrar(TipoEventoSeguridad.CUENTA_CREADA, cuenta.id)
        return AltaHecha(cuenta, fichaCreada, temporal)
    }

    /**
     * Grants access to a person who is already on the staff register, in one
     * operation (FR-029b).
     *
     * The temporary password is **generated**, not chosen by the `ADMIN`.
     * FR-023a requires the policy to apply to "the temporary password an `ADMIN`
     * generates or accepts", and generating it closes the dangerous half of that
     * sentence: if the administrator picks it, nothing stops them using the same
     * one for the whole workforce, and the hash would be irrelevant because the
     * password would be guessable by being known.
     *
     * @return the created account **and the clear temporary password**, which is
     * the only moment it exists.
     */
    fun darAcceso(empleadoId: EntityId, email: String, rol: Role): Pair<CuentaAccesoEntity, String> {
        // FR-029c. The database cannot enforce this: there is no foreign key
        // across modules, so the check goes through the DirectorioEmpleados
        // contract and the sweep in FR-029c is what catches the rows that go
        // stale afterwards.
        if (directorio.estado(empleadoId) == null) {
            throw EmpleadoNoEncontradoEnDirectorioException(empleadoId)
        }

        // Two distinct conflicts on purpose: the ADMIN's way out differs - reset
        // in one case, use another address in the other.
        if (cuentas.existsByEmpleadoId(empleadoId)) throw CuentaYaExisteException()

        val normalizado = NormalizadorEmail.normalizar(email)
        if (cuentas.existsByEmail(normalizado)) throw EmailYaRegistradoException()

        val temporal = generadorPassword.generar()
        val cuenta = cuentas.save(
            CuentaAccesoEntity(
                empleadoId = empleadoId,
                email = normalizado,
                rol = rol,
                passwordHash = verificador.codificar(temporal),
                // FR-019. Also the column default: every account is born from a
                // temporary password and there is no route that skips the change.
                requiereCambioPassword = true
            )
        )

        eventos.registrar(TipoEventoSeguridad.CUENTA_CREADA, cuenta.id)
        return cuenta to temporal
    }

    /**
     * The person changes their own password (FR-021, FR-022, FR-023).
     *
     * ## Why the policy is checked first
     *
     * Rejecting a weak password does not require knowing whether the current one
     * is right, and checking it first saves a ~110 ms Argon2 verification on
     * every typo. It also cannot leak anything: the policy verdict depends only
     * on the new password, which the caller already knows.
     *
     * ## Why the other sessions are revoked
     *
     * **This is an addition of the plan, not a requirement.** The spec only
     * demands it on reset (FR-025). The reason: changing a password is often the
     * reaction to a suspicion of theft, and leaving the other devices signed in
     * empties the gesture. The cost is visible and was accepted deliberately -
     * changing it on the phone closes the browser session.
     */
    fun cambiarPasswordDeEmpleado(empleadoId: EntityId, actual: String, nueva: String): ParTokens {
        // Looked up by empleado id because that is what the JWT subject carries
        // (decided in feature 001). The account id never travels in a request.
        val cuenta = cuentas.findByEmpleadoId(empleadoId) ?: throw CuentaNoEncontradaException()
        val cuentaId = cuenta.id

        val incumplidos = PoliticaPassword.validar(nueva)
        if (incumplidos.isNotEmpty()) throw PasswordDebilException(incumplidos)

        // FR-022. The same exception as a failed sign-in, so it maps to the same
        // 401: this is a credential check, and there is nothing else to say
        // about it.
        if (!verificador.coincide(actual, cuenta.passwordHash)) {
            throw CredencialesInvalidasException()
        }

        // Hash first, outside any transaction; then a short locked write that
        // touches only the credential. See EscritorCredenciales for the lost
        // update this avoids.
        val actualizada = escritor.aplicar(
            cuentaId = cuentaId,
            hash = verificador.codificar(nueva),
            requiereCambio = false,
            levantarBloqueo = false
        )
        eventos.registrar(TipoEventoSeguridad.PASSWORD_CAMBIADA, cuentaId)

        // Revoked before the new pair is issued, so the session opened below
        // survives and the person is not logged out of the device they just
        // used.
        sesiones.revocarTodasDeCuenta(cuentaId, MOTIVO_CAMBIO_PASSWORD, clock.instant())

        return autenticacionService.emitirPar(actualizada)
    }

    /**
     * An `ADMIN` resets somebody's password (FR-024 to FR-026).
     *
     * The way back when everything else fails: there is no recovery by email,
     * so this is how a forgotten password is replaced. Same mechanism as
     * granting access - a generated temporary password, pending change - plus
     * two things only a reset does:
     *
     *  - **every** live session is revoked (FR-025, SC-007), which is also the
     *    route for a lost device, and the reason D-007 can afford not to revoke
     *    the chain on token reuse;
     *  - the lockout is lifted (FR-026), or resetting the password of somebody
     *    who locked themselves out would leave them locked out with a password
     *    they cannot use yet.
     *
     * @return the account and the clear temporary password - the only moment it
     * exists.
     */
    fun restablecer(empleadoId: EntityId): Pair<CuentaAccesoEntity, String> {
        val cuenta = cuentas.findByEmpleadoId(empleadoId) ?: throw CuentaNoEncontradaException()

        val temporal = generadorPassword.generar()
        val actualizada = escritor.aplicar(
            cuentaId = cuenta.id,
            hash = verificador.codificar(temporal),
            requiereCambio = true,
            levantarBloqueo = true
        )

        sesiones.revocarTodasDeCuenta(cuenta.id, MOTIVO_RESET, clock.instant())
        eventos.registrar(TipoEventoSeguridad.PASSWORD_RESTABLECIDA, cuenta.id)

        return actualizada to temporal
    }

    companion object {
        const val MOTIVO_CAMBIO_PASSWORD = "CAMBIO_PASSWORD"
        const val MOTIVO_RESET = "RESET"
    }
}
