package com.granatum.core

import com.granatum.core.domain.exception.TokenRenovacionInvalidoException
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.infrastructure.database.repositories.SesionRenovacionRepository
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.GeneradorTokenRenovacion
import com.granatum.core.service.SesionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ending a session (US2): FR-009 and FR-012, plus SC-006.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class CierreSesionIT : BaseAuthIT() {

    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var sesionService: SesionService
    @Autowired lateinit var sesiones: SesionRenovacionRepository
    @Autowired lateinit var eventos: EventoSeguridadRepository
    @Autowired lateinit var generadorToken: GeneradorTokenRenovacion

    private val password = "Granatum-2026!"

    /**
     * SC-006, and the assertion that matters is the second one. A logout that
     * closed everything would also pass "the closed session no longer works",
     * which is why both halves are checked.
     */
    @Test
    fun `closing one session leaves the others working`() {
        val cuenta = cuentaActiva(password = password)
        val movil = autenticacion.iniciarSesion(cuenta.email, password)
        val navegador = autenticacion.iniciarSesion(cuenta.email, password)

        sesionService.cerrar(movil.refreshToken)

        assertFailsWith<TokenRenovacionInvalidoException> {
            sesionService.rotar(movil.refreshToken)
        }
        assertNotNull(
            sesionService.rotar(navegador.refreshToken).accessToken,
            "the other device must keep working: a lost phone is revoked by logging it " +
                "out, not by throwing the person off every screen they own"
        )
    }

    /**
     * Idempotent. Logging out twice is not a failure, and a 404 here would
     * distinguish tokens that exist from tokens that do not - information worth
     * nothing to a legitimate client and worth something to an attacker.
     */
    @Test
    fun `closing twice is not an error`() {
        val cuenta = cuentaActiva(password = password)
        val par = autenticacion.iniciarSesion(cuenta.email, password)

        sesionService.cerrar(par.refreshToken)
        sesionService.cerrar(par.refreshToken)
    }

    @Test
    fun `closing an unknown token is not an error either`() {
        sesionService.cerrar(generadorToken.generar())
    }

    @Test
    fun `the closed session is marked revoked with its reason`() {
        val cuenta = cuentaActiva(password = password)
        val par = autenticacion.iniciarSesion(cuenta.email, password)

        sesionService.cerrar(par.refreshToken)

        val sesion = sesiones.findByTokenHash(generadorToken.hash(par.refreshToken))
        assertNotNull(sesion)
        assertNotNull(sesion.revocadaEn)
        assertEquals(
            "LOGOUT",
            sesion.motivoRevocacion,
            "the reason distinguishes a logout from a reset, which is the difference " +
                "that matters when someone asks why a session ended"
        )
        assertEquals(null, sesion.usadaEn, "revoked is not the same as rotated")
    }

    @Test
    fun `an already used token can still be logged out without error`() {
        val cuenta = cuentaActiva(password = password)
        val primero = autenticacion.iniciarSesion(cuenta.email, password)
        sesionService.rotar(primero.refreshToken)

        // The consumed row is left alone: it cannot be presented again anyway,
        // and overwriting it would lose the record of how it ended.
        sesionService.cerrar(primero.refreshToken)

        val sesion = sesiones.findByTokenHash(generadorToken.hash(primero.refreshToken))!!
        assertNotNull(sesion.usadaEn)
        assertEquals(null, sesion.revocadaEn)
    }

    @Test
    fun `a real logout is recorded and a no-op one is not`() {
        val cuenta = cuentaActiva(password = password)
        val par = autenticacion.iniciarSesion(cuenta.email, password)

        sesionService.cerrar(par.refreshToken)
        val trasPrimero = eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id)
            .count { it.tipo == TipoEventoSeguridad.CIERRE_SESION }

        sesionService.cerrar(par.refreshToken)
        val trasSegundo = eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id)
            .count { it.tipo == TipoEventoSeguridad.CIERRE_SESION }

        assertEquals(1, trasPrimero)
        assertEquals(
            1,
            trasSegundo,
            "a no-op logout must not be recorded: filling the security log with events " +
                "that mean nothing happened makes the real ones harder to find"
        )
    }

    @Test
    fun `closing a session does not touch other accounts`() {
        val mia = cuentaActiva(password = password)
        val ajena = cuentaActiva(password = password)
        val miPar = autenticacion.iniciarSesion(mia.email, password)
        val suPar = autenticacion.iniciarSesion(ajena.email, password)

        sesionService.cerrar(miPar.refreshToken)

        assertTrue(
            sesionService.rotar(suPar.refreshToken).refreshToken.isNotBlank(),
            "the revocation must be scoped to one row, not one account and certainly " +
                "not the whole table"
        )
    }
}
