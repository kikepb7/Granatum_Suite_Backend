package com.granatum.core

import com.granatum.core.domain.exception.CuentaDeEmpleadoInactivoException
import com.granatum.core.domain.exception.TokenRenovacionInvalidoException
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.entities.SesionRenovacionEntity
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.infrastructure.database.repositories.SesionRenovacionRepository
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.GeneradorTokenRenovacion
import com.granatum.core.service.JwtService
import com.granatum.core.service.SesionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renewing a session (US2): FR-007, FR-008 and FR-010, plus SC-010.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class RenovacionIT : BaseAuthIT() {

    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var sesionService: SesionService
    @Autowired lateinit var sesiones: SesionRenovacionRepository
    @Autowired lateinit var eventos: EventoSeguridadRepository
    @Autowired lateinit var generadorToken: GeneradorTokenRenovacion
    @Autowired lateinit var jwtService: JwtService

    private val password = "Granatum-2026!"

    @Test
    fun `renewing returns a brand new pair`() {
        val cuenta = cuentaActiva(password = password, rol = Role.ENCARGADO)
        val original = autenticacion.iniciarSesion(cuenta.email, password)

        val renovado = sesionService.rotar(original.refreshToken)

        assertTrue(jwtService.validateAccessToken(renovado.accessToken))
        assertTrue(renovado.refreshToken != original.refreshToken, "the token rotates")
        assertEquals(
            Role.ENCARGADO,
            jwtService.getRoleFromToken(renovado.accessToken),
            "the role survives the rotation, or every renewal would silently demote somebody"
        )
        assertEquals(cuenta.empleadoId, jwtService.getSubjectFromToken(renovado.accessToken))
    }

    /** FR-008 and SC-004: one use, and one only. */
    @Test
    fun `the presented token stops working immediately`() {
        val cuenta = cuentaActiva(password = password)
        val original = autenticacion.iniciarSesion(cuenta.email, password)

        sesionService.rotar(original.refreshToken)

        assertFailsWith<TokenRenovacionInvalidoException> {
            sesionService.rotar(original.refreshToken)
        }
    }

    @Test
    fun `an unknown token is rejected`() {
        assertFailsWith<TokenRenovacionInvalidoException> {
            sesionService.rotar(generadorToken.generar())
        }
    }

    @Test
    fun `an expired token is rejected`() {
        val cuenta = cuentaActiva(password = password)
        val token = generadorToken.generar()
        sesiones.save(
            SesionRenovacionEntity(
                cuenta = cuenta,
                tokenHash = generadorToken.hash(token),
                // Inserted already expired: the clock validator cannot be
                // waited out in a test, and what is under test is the WHERE
                // clause, not the passage of time.
                expiraEn = Instant.now().minus(1, ChronoUnit.DAYS),
                creadaEn = Instant.now().minus(31, ChronoUnit.DAYS)
            )
        )

        assertFailsWith<TokenRenovacionInvalidoException> { sesionService.rotar(token) }
    }

    @Test
    fun `a revoked token is rejected`() {
        val cuenta = cuentaActiva(password = password)
        val par = autenticacion.iniciarSesion(cuenta.email, password)

        sesionService.cerrar(par.refreshToken)

        assertFailsWith<TokenRenovacionInvalidoException> { sesionService.rotar(par.refreshToken) }
    }

    /**
     * FR-010 and SC-010. Distinguishable from the generic rejection on purpose:
     * whoever presents a valid refresh token has already proved they hold the
     * account, so there is no existence to hide - and a clear answer stops them
     * retyping their password over and over.
     */
    @Test
    fun `renewing is refused when the person has been dismissed`() {
        val empleadoId = UUID.randomUUID()
        val cuenta = cuentaActiva(password = password, empleadoId = empleadoId)
        val par = autenticacion.iniciarSesion(cuenta.email, password)

        directorio.registrarInactivo(empleadoId)

        assertFailsWith<CuentaDeEmpleadoInactivoException> { sesionService.rotar(par.refreshToken) }
    }

    /**
     * The status check runs **before** the token is claimed, so a dismissed
     * person gets the same clear answer every time instead of
     * `TOKEN_RENOVACION_INVALIDO` from the second attempt onwards.
     */
    @Test
    fun `a refused renewal for dismissal does not burn the token`() {
        val empleadoId = UUID.randomUUID()
        val cuenta = cuentaActiva(password = password, empleadoId = empleadoId)
        val par = autenticacion.iniciarSesion(cuenta.email, password)
        directorio.registrarInactivo(empleadoId)

        assertFailsWith<CuentaDeEmpleadoInactivoException> { sesionService.rotar(par.refreshToken) }
        assertFailsWith<CuentaDeEmpleadoInactivoException> { sesionService.rotar(par.refreshToken) }

        assertNull(
            sesiones.findByTokenHash(generadorToken.hash(par.refreshToken))?.usadaEn,
            "the token must still be unused: burning it would answer a different error " +
                "for the same situation on the second try"
        )
    }

    /**
     * Reusing an already-used token is its own event: it is the only signal of
     * possible theft the feature leaves, so collapsing it into the generic
     * rejection would erase the one thing worth investigating.
     */
    @Test
    fun `reusing a consumed token is recorded distinctly from an unknown one`() {
        val cuenta = cuentaActiva(password = password)
        val par = autenticacion.iniciarSesion(cuenta.email, password)
        sesionService.rotar(par.refreshToken)

        assertFailsWith<TokenRenovacionInvalidoException> { sesionService.rotar(par.refreshToken) }

        val tipos = eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id).map { it.tipo }
        assertTrue(
            tipos.contains(TipoEventoSeguridad.RENOVACION_TOKEN_REUTILIZADO),
            "got $tipos"
        )
    }

    /**
     * D-007: the chain is deliberately **not** revoked on reuse. The usual
     * cause is a mobile client retrying a renewal whose response was lost, and
     * revoking everything would throw someone out of every device because of a
     * bad network - which here means being unable to clock in.
     */
    @Test
    fun `reusing a consumed token does not revoke the rest of the chain`() {
        val cuenta = cuentaActiva(password = password)
        val primero = autenticacion.iniciarSesion(cuenta.email, password)
        val segundo = sesionService.rotar(primero.refreshToken)

        assertFailsWith<TokenRenovacionInvalidoException> { sesionService.rotar(primero.refreshToken) }

        val tercero = sesionService.rotar(segundo.refreshToken)
        assertNotNull(
            tercero.accessToken,
            "the live token must survive the reuse of a dead one, or a flaky network " +
                "would log the person out of everything"
        )
    }

    @Test
    fun `each renewal leaves its own session row`() {
        val cuenta = cuentaActiva(password = password)
        val primero = autenticacion.iniciarSesion(cuenta.email, password)
        sesionService.rotar(primero.refreshToken)

        assertEquals(
            1,
            sesiones.countByCuentaIdAndUsadaEnIsNullAndRevocadaEnIsNull(cuenta.id),
            "exactly one live session after a rotation: the old one is consumed and the " +
                "new one replaces it"
        )
    }
}
