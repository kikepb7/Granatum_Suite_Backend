package com.granatum.core

import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.domain.exception.CuentaNoEncontradaException
import com.granatum.core.domain.exception.TokenRenovacionInvalidoException
import com.granatum.core.domain.service.PoliticaPassword
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.infrastructure.database.repositories.SesionRenovacionRepository
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.CuentaAccesoService
import com.granatum.core.service.GeneradorTokenRenovacion
import com.granatum.core.service.SesionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Resetting a password (US5): FR-024 to FR-026, plus SC-007.
 *
 * "Only an ADMIN may reset" (US5 scenario 4) is authorisation, and lives in
 * `app`, the only module with the real filter chain: see `AutorizacionCuentasIT`.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class RestablecimientoIT : BaseAuthIT() {

    @Autowired lateinit var cuentaAccesoService: CuentaAccesoService
    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var sesionService: SesionService
    @Autowired lateinit var sesiones: SesionRenovacionRepository
    @Autowired lateinit var eventos: EventoSeguridadRepository
    @Autowired lateinit var generadorToken: GeneradorTokenRenovacion

    private val password = "Granatum-2026!"

    /** FR-024. */
    @Test
    fun `a reset leaves a temporary password pending change`() {
        val cuenta = cuentaActiva(password = password)

        val (restablecida, temporal) = cuentaAccesoService.restablecer(cuenta.empleadoId)

        assertTrue(restablecida.requiereCambioPassword)
        assertEquals(emptyList(), PoliticaPassword.validar(temporal), "FR-023a holds on reset too")

        val par = autenticacion.iniciarSesion(cuenta.email, temporal)
        assertTrue(par.requiereCambioPassword, "the next sign-in must demand the change")
    }

    @Test
    fun `the old password stops working`() {
        val cuenta = cuentaActiva(password = password)

        cuentaAccesoService.restablecer(cuenta.empleadoId)

        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, password)
        }
    }

    /**
     * FR-025 and SC-007: **100%** of open sessions die. Two are opened so the
     * test cannot pass by accident on a reset that closes only the newest one.
     */
    @Test
    fun `a reset closes every open session`() {
        val cuenta = cuentaActiva(password = password)
        val movil = autenticacion.iniciarSesion(cuenta.email, password)
        val navegador = autenticacion.iniciarSesion(cuenta.email, password)

        cuentaAccesoService.restablecer(cuenta.empleadoId)

        assertFailsWith<TokenRenovacionInvalidoException> { sesionService.rotar(movil.refreshToken) }
        assertFailsWith<TokenRenovacionInvalidoException> { sesionService.rotar(navegador.refreshToken) }
        assertEquals(0, sesiones.countByCuentaIdAndUsadaEnIsNullAndRevocadaEnIsNull(cuenta.id))
    }

    @Test
    fun `revoked sessions record the reset as their reason`() {
        val cuenta = cuentaActiva(password = password)
        val par = autenticacion.iniciarSesion(cuenta.email, password)

        cuentaAccesoService.restablecer(cuenta.empleadoId)

        assertEquals(
            "RESET",
            sesiones.findByTokenHash(generadorToken.hash(par.refreshToken))!!.motivoRevocacion,
            "the reason is what distinguishes 'an ADMIN cut this off' from 'they logged out' " +
                "when somebody later asks why a session ended"
        )
    }

    /**
     * FR-026. Without this, resetting the password of somebody who locked
     * themselves out would leave them locked out holding a password they cannot
     * use yet.
     */
    @Test
    fun `a reset lifts the lockout`() {
        val cuenta = cuentaActiva(password = password)
        repeat(5) {
            assertFailsWith<CredencialesInvalidasException> {
                autenticacion.iniciarSesion(cuenta.email, "incorrecta-$it")
            }
        }
        assertNotNull(cuentas.findById(cuenta.id).orElseThrow().estadoBloqueo.bloqueadaHasta)

        val (_, temporal) = cuentaAccesoService.restablecer(cuenta.empleadoId)

        val estado = cuentas.findById(cuenta.id).orElseThrow().estadoBloqueo
        assertNull(estado.bloqueadaHasta)
        assertEquals(0, estado.nivelBloqueo.toInt(), "the level goes too, not only the deadline")
        assertEquals(0, estado.intentosFallidos.toInt())
        assertNotNull(autenticacion.iniciarSesion(cuenta.email, temporal).accessToken)
    }

    @Test
    fun `resetting somebody without an account is refused`() {
        assertFailsWith<CuentaNoEncontradaException> {
            cuentaAccesoService.restablecer(UUID.randomUUID())
        }
    }

    @Test
    fun `two resets give two different temporary passwords`() {
        val cuenta = cuentaActiva(password = password)

        val (_, primera) = cuentaAccesoService.restablecer(cuenta.empleadoId)
        val (_, segunda) = cuentaAccesoService.restablecer(cuenta.empleadoId)

        assertTrue(primera != segunda)
        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, primera)
        }
    }

    @Test
    fun `the reset is recorded`() {
        val cuenta = cuentaActiva(password = password)

        cuentaAccesoService.restablecer(cuenta.empleadoId)

        val tipos = eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id).map { it.tipo }
        assertTrue(tipos.contains(TipoEventoSeguridad.PASSWORD_RESTABLECIDA), "got $tipos")
    }
}
