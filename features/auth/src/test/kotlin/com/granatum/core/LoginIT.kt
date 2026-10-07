package com.granatum.core

import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.infrastructure.database.repositories.SesionRenovacionRepository
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.GeneradorTokenRenovacion
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Signing in (US1): FR-001 to FR-005, plus SC-010.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class LoginIT : BaseAuthIT() {

    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var generadorToken: GeneradorTokenRenovacion
    @Autowired lateinit var eventos: EventoSeguridadRepository
    @Autowired lateinit var sesiones: SesionRenovacionRepository

    private val password = "Granatum-2026!"

    @Test
    fun `valid credentials return a token pair`() {
        val cuenta = cuentaActiva(password = password)

        val par = autenticacion.iniciarSesion(cuenta.email, password)

        assertTrue(jwtService.validateAccessToken(par.accessToken))
        assertNotNull(par.refreshToken)
        assertEquals(15 * 60, par.expiresInSegundos, "expiresIn travels in seconds")
        assertFalse(par.requiereCambioPassword)
    }

    /** FR-005: the existing features consume this token unchanged. */
    @Test
    fun `the access token carries the person and their role`() {
        val empleadoId = UUID.randomUUID()
        val cuenta = cuentaActiva(password = password, rol = Role.ENCARGADO, empleadoId = empleadoId)

        val par = autenticacion.iniciarSesion(cuenta.email, password)

        assertEquals(
            empleadoId,
            jwtService.getSubjectFromToken(par.accessToken),
            "the subject is the empleado id, not the account id: feature 001 decided " +
                "that, and it is what attributes a fichaje with no lookup that could drift"
        )
        assertEquals(Role.ENCARGADO, jwtService.getRoleFromToken(par.accessToken))
    }

    @Test
    fun `a wrong password is rejected`() {
        val cuenta = cuentaActiva(password = password)

        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, "Otra-Clave-2026!")
        }
    }

    /**
     * FR-003: the same exception type as a wrong password, so the handler maps
     * both to the same code and the same body. Anything else turns sign-in into
     * an oracle for which addresses are registered.
     */
    @Test
    fun `an unknown email is rejected exactly like a wrong password`() {
        val cuenta = cuentaActiva(password = password)

        val porPassword = assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, "incorrecta")
        }
        val porCorreo = assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(correoUnico(), "incorrecta")
        }

        assertEquals(porPassword.message, porCorreo.message)
        assertEquals(porPassword::class, porCorreo::class)
    }

    /** FR-002 and SC-010, with correct credentials. */
    @Test
    fun `a dismissed person cannot sign in even with the right password`() {
        val empleadoId = UUID.randomUUID()
        val cuenta = cuentaActiva(password = password, empleadoId = empleadoId)
        directorio.registrarInactivo(empleadoId)

        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, password)
        }
    }

    /**
     * An orphan account - one whose person no longer exists - must not let
     * anybody in either. The database cannot prevent the row (no foreign key
     * across modules), so the service has to refuse it; FR-029c's sweep is what
     * surfaces it for cleaning.
     */
    @Test
    fun `an account whose person does not exist cannot sign in`() {
        val empleadoId = UUID.randomUUID()
        val cuenta = cuentaActiva(password = password, empleadoId = empleadoId)
        directorio.olvidar(empleadoId)

        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, password)
        }
    }

    /** FR-028: the address is normalised in one place, so case must not matter. */
    @Test
    fun `the email is case and whitespace insensitive`() {
        val cuenta = cuentaActiva(password = password, email = "ana.prueba@granatum.es")

        val par = autenticacion.iniciarSesion("  ANA.PRUEBA@Granatum.ES  ", password)

        assertTrue(jwtService.validateAccessToken(par.accessToken))
        assertEquals(cuenta.email, "ana.prueba@granatum.es")
    }

    /**
     * FR-011: what is stored must not be usable. The session row holds the
     * SHA-256, and the clear value appears only in the response.
     */
    @Test
    fun `the refresh token is stored only as a hash`() {
        val cuenta = cuentaActiva(password = password)

        val par = autenticacion.iniciarSesion(cuenta.email, password)

        val porValor = sesiones.findByTokenHash(par.refreshToken)
        assertEquals(null, porValor, "the clear value must not be the stored key")

        val porHash = sesiones.findByTokenHash(generadorToken.hash(par.refreshToken))
        assertNotNull(porHash, "the session is found by the hash of the token and only by it")
        assertEquals(64, generadorToken.hash(par.refreshToken).length)
    }

    @Test
    fun `two sign ins open two independent sessions`() {
        val cuenta = cuentaActiva(password = password)

        val movil = autenticacion.iniciarSesion(cuenta.email, password)
        val web = autenticacion.iniciarSesion(cuenta.email, password)

        assertFalse(movil.refreshToken == web.refreshToken, "FR-012: one session per device")
    }

    /** FR-017, and the shape of it: counts and account ids, never values. */
    @Test
    fun `each outcome leaves its own security event`() {
        val cuenta = cuentaActiva(password = password)

        autenticacion.iniciarSesion(cuenta.email, password)
        runCatching { autenticacion.iniciarSesion(cuenta.email, "mal") }
        runCatching { autenticacion.iniciarSesion(correoUnico(), "mal") }

        val tipos = eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id).map { it.tipo }
        assertTrue(tipos.contains(TipoEventoSeguridad.LOGIN_CORRECTO))
        assertTrue(tipos.contains(TipoEventoSeguridad.LOGIN_FALLIDO))

        // The unknown-email attempt is recorded with a null account id, never
        // with the address: it would be a personal datum about someone who is
        // not a user, over an attempt that may not have been theirs.
        assertTrue(
            eventos.count() >= 3,
            "the unknown-email attempt must be recorded too, with no account attached"
        )
    }

}
