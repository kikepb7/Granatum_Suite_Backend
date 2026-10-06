package com.granatum.core

import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.domain.exception.PasswordDebilException
import com.granatum.core.domain.exception.TokenRenovacionInvalidoException
import com.granatum.core.domain.type.RequisitoIncumplido
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.CuentaAccesoService
import com.granatum.core.service.JwtService
import com.granatum.core.service.SesionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Changing a password (US4): FR-021 to FR-023b, plus SC-014.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class CambioPasswordIT : BaseAuthIT() {

    @Autowired lateinit var cuentaAccesoService: CuentaAccesoService
    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var sesionService: SesionService
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var eventos: EventoSeguridadRepository

    private val nueva = "Granatum-2027!"

    /** Grants access and returns the account plus its temporary password. */
    private fun conAcceso(): Pair<UUID, String> {
        val empleadoId = UUID.randomUUID().also { directorio.registrarActivo(it) }
        val (_, temporal) = cuentaAccesoService.darAcceso(empleadoId, correoUnico(), Role.EMPLEADO)
        return empleadoId to temporal
    }

    /** FR-021: the flag comes off and the person can operate normally. */
    @Test
    fun `changing the password clears the pending flag`() {
        val (empleadoId, temporal) = conAcceso()

        val par = cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, temporal, nueva)

        assertFalse(par.requiereCambioPassword)
        assertFalse(
            jwtService.requiereCambioPassword(par.accessToken),
            "the new token must not carry the claim, or the person stays locked to the " +
                "change-password screen for ever"
        )
        assertEquals(
            Role.EMPLEADO,
            jwtService.getRoleFromToken(par.accessToken),
            "and it must carry the real role again"
        )
    }

    @Test
    fun `the new password is the one that works afterwards`() {
        val (empleadoId, temporal) = conAcceso()
        val cuenta = cuentas.findByEmpleadoId(empleadoId)!!

        cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, temporal, nueva)

        assertNotNull(autenticacion.iniciarSesion(cuenta.email, nueva).accessToken)
        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, temporal)
        }
    }

    /** FR-022. */
    @Test
    fun `a wrong current password is refused`() {
        val (empleadoId, _) = conAcceso()

        assertFailsWith<CredencialesInvalidasException> {
            cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, "no-es-la-actual", nueva)
        }
    }

    /** FR-023 and SC-014. */
    @Test
    fun `a weak password is refused with the list of broken requirements`() {
        val (empleadoId, temporal) = conAcceso()

        val error = assertFailsWith<PasswordDebilException> {
            cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, temporal, "abc")
        }

        assertContains(error.requisitos, RequisitoIncumplido.LONGITUD_MINIMA)
        assertContains(error.requisitos, RequisitoIncumplido.FALTA_MAYUSCULA)
        assertContains(error.requisitos, RequisitoIncumplido.FALTA_DIGITO)
        assertContains(error.requisitos, RequisitoIncumplido.FALTA_SIMBOLO)
    }

    /** FR-023: the rejection must not reproduce the password anywhere. */
    @Test
    fun `the rejection never quotes the password`() {
        val (empleadoId, temporal) = conAcceso()
        val secreta = "zzSECRETOzz"

        val error = assertFailsWith<PasswordDebilException> {
            cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, temporal, secreta)
        }

        assertFalse(error.message.contains("SECRETO", ignoreCase = true))
        assertFalse(error.requisitos.any { it.name.contains("SECRETO", ignoreCase = true) })
    }

    /**
     * The policy is checked **before** the current password, so a weak new one
     * does not cost a ~110 ms Argon2 verification. It cannot leak anything: the
     * verdict depends only on the new password, which the caller already knows.
     */
    @Test
    fun `a weak password is refused before the current one is even checked`() {
        val (empleadoId, _) = conAcceso()

        assertFailsWith<PasswordDebilException> {
            cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, "actual-incorrecta", "abc")
        }
    }

    /** FR-023b: the requirement that chose Argon2 over BCrypt, end to end. */
    @Test
    fun `a 64 character accented passphrase is accepted and works`() {
        val (empleadoId, temporal) = conAcceso()
        val cuenta = cuentas.findByEmpleadoId(empleadoId)!!
        val frase = "Á".repeat(30) + "á".repeat(32) + "1!"

        cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, temporal, frase)

        assertNotNull(
            autenticacion.iniciarSesion(cuenta.email, frase).accessToken,
            "126 bytes of UTF-8: BCrypt throws above 72, which is why the algorithm is " +
                "Argon2id"
        )
    }

    /**
     * ➕ An addition of the plan, not a requirement. The spec only demands this
     * on reset (FR-025). Changing a password is often the reaction to a
     * suspicion of theft, and leaving the other devices signed in empties the
     * gesture. The cost was accepted openly: changing it on the phone closes the
     * browser session.
     */
    @Test
    fun `changing the password closes the other sessions but not the new one`() {
        val (empleadoId, temporal) = conAcceso()
        val cuenta = cuentas.findByEmpleadoId(empleadoId)!!
        val navegador = autenticacion.iniciarSesion(cuenta.email, temporal)

        val tras = cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, temporal, nueva)

        assertFailsWith<TokenRenovacionInvalidoException> {
            sesionService.rotar(navegador.refreshToken)
        }
        assertNotNull(
            sesionService.rotar(tras.refreshToken).accessToken,
            "the session handed back by the change itself must survive, or the person is " +
                "logged out of the very device they just used"
        )
    }

    @Test
    fun `the change is recorded`() {
        val (empleadoId, temporal) = conAcceso()
        val cuenta = cuentas.findByEmpleadoId(empleadoId)!!

        cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, temporal, nueva)

        val tipos = eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id).map { it.tipo }
        assertTrue(tipos.contains(TipoEventoSeguridad.PASSWORD_CAMBIADA), "got $tipos")
    }

    @Test
    fun `the new hash is salted and differs from the old one`() {
        val (empleadoId, temporal) = conAcceso()
        val antes = cuentas.findByEmpleadoId(empleadoId)!!.passwordHash

        cuentaAccesoService.cambiarPasswordDeEmpleado(empleadoId, temporal, nueva)

        val despues = cuentas.findByEmpleadoId(empleadoId)!!.passwordHash
        assertFalse(antes == despues)
        assertTrue(despues.startsWith("{argon2}"))
    }
}
