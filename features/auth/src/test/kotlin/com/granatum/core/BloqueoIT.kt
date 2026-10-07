package com.granatum.core

import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.service.AutenticacionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lockout (US3): FR-013 to FR-016, plus SC-005.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class BloqueoIT : BaseAuthIT() {

    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var eventos: EventoSeguridadRepository

    private val password = "Granatum-2026!"

    private fun fallar(email: String, veces: Int) = repeat(veces) {
        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(email, "incorrecta-$it")
        }
    }

    private fun recargar(id: UUID) = cuentas.findById(id).orElseThrow()

    @Test
    fun `four failures do not lock the account`() {
        val cuenta = cuentaActiva(password = password)

        fallar(cuenta.email, 4)

        assertNull(recargar(cuenta.id).estadoBloqueo.bloqueadaHasta)
        assertNotNull(
            autenticacion.iniciarSesion(cuenta.email, password).accessToken,
            "an honest mistake four times over must not cost access"
        )
    }

    /** FR-013. */
    @Test
    fun `five consecutive failures lock the account`() {
        val cuenta = cuentaActiva(password = password)

        fallar(cuenta.email, 5)

        val recargada = recargar(cuenta.id)
        assertNotNull(recargada.estadoBloqueo.bloqueadaHasta)
        assertEquals(1, recargada.estadoBloqueo.nivelBloqueo.toInt())
        assertEquals(
            0,
            recargada.estadoBloqueo.intentosFallidos.toInt(),
            "the counter resets when the lockout is applied: the next lockout needs " +
                "another five failures once this one has expired (FR-016c)"
        )
    }

    /** FR-014 and SC-005: the sixth attempt fails **with the right password**. */
    @Test
    fun `a locked account refuses even the correct password`() {
        val cuenta = cuentaActiva(password = password)
        fallar(cuenta.email, 5)

        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, password)
        }
    }

    /**
     * ➕ The plan's own decision, not the spec's: the rejection for a lockout is
     * the same exception, so the handler maps it to the same code and body as a
     * wrong password. A specific "locked" response would reveal that the address
     * exists and re-open the oracle FR-003 closes.
     *
     * The cost is accepted openly: the person does not learn how long to wait.
     */
    @Test
    fun `a locked account answers exactly like a wrong password`() {
        val cuenta = cuentaActiva(password = password)
        val cuentaLibre = cuentaActiva(password = password)
        fallar(cuenta.email, 5)

        val porBloqueo = assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, password)
        }
        val porPassword = assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuentaLibre.email, "incorrecta")
        }

        assertEquals(porPassword::class, porBloqueo::class)
        assertEquals(porPassword.message, porBloqueo.message)
    }

    /** FR-016: the lockout lifts by itself, with no manual intervention. */
    @Test
    fun `once the deadline passes the correct password works again`() {
        val cuenta = cuentaActiva(password = password)
        fallar(cuenta.email, 5)

        // The deadline is moved into the past rather than waiting a minute:
        // what is under test is that nothing has to happen for the lockout to
        // lift, not the passage of time itself.
        val bloqueada = recargar(cuenta.id)
        bloqueada.estadoBloqueo.bloqueadaHasta = Instant.now().minusSeconds(1)
        cuentas.save(bloqueada)

        assertNotNull(autenticacion.iniciarSesion(cuenta.email, password).accessToken)
    }

    /** FR-015. */
    @Test
    fun `four failures and a success reset the counter`() {
        val cuenta = cuentaActiva(password = password)
        fallar(cuenta.email, 4)

        autenticacion.iniciarSesion(cuenta.email, password)

        assertEquals(0, recargar(cuenta.id).estadoBloqueo.intentosFallidos.toInt())
    }

    /**
     * The counter is consecutive, not cumulative. Without the reset, four
     * mistakes spread over a year would lock someone out on the fifth.
     */
    @Test
    fun `the counter is consecutive and not cumulative`() {
        val cuenta = cuentaActiva(password = password)

        repeat(3) {
            fallar(cuenta.email, 4)
            autenticacion.iniciarSesion(cuenta.email, password)
        }

        assertNull(
            recargar(cuenta.id).estadoBloqueo.bloqueadaHasta,
            "twelve failures total, never five in a row: the account must be open"
        )
    }

    @Test
    fun `an attempt against a locked account is recorded with its own type`() {
        val cuenta = cuentaActiva(password = password)
        fallar(cuenta.email, 5)

        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, password)
        }

        val tipos = eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id).map { it.tipo }
        assertTrue(tipos.contains(TipoEventoSeguridad.CUENTA_BLOQUEADA), "got $tipos")
        assertTrue(tipos.contains(TipoEventoSeguridad.LOGIN_CUENTA_BLOQUEADA), "got $tipos")
        assertEquals(
            1,
            tipos.count { it == TipoEventoSeguridad.CUENTA_BLOQUEADA },
            "the account closes once; the attempts against it are a different event"
        )
    }

    @Test
    fun `locking one account does not affect another`() {
        val bloqueada = cuentaActiva(password = password)
        val libre = cuentaActiva(password = password)

        fallar(bloqueada.email, 5)

        assertNotNull(autenticacion.iniciarSesion(libre.email, password).accessToken)
    }
}
