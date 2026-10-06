package com.granatum.core

import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.service.AutenticacionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The escalation (SC-012) and FR-016c, both end to end against the database.
 *
 * `PoliticaBloqueoTest` already proves the state machine as a pure function.
 * This test proves the **wiring**: that the computed state is written to all
 * three columns, together, and read back. The policy could be perfect and the
 * persistence still wrong - writing `bloqueada_hasta` without `nivel_bloqueo`
 * would make every lockout last one minute forever, and no unit test of the
 * policy would notice.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class BloqueoCrecienteIT : BaseAuthIT() {

    @Autowired lateinit var autenticacion: AutenticacionService

    private val password = "Granatum-2026!"

    private fun fallar(email: String, veces: Int) = repeat(veces) {
        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(email, "incorrecta-$it")
        }
    }

    private fun recargar(id: UUID) = cuentas.findById(id).orElseThrow()

    /** Expires the current lockout without waiting it out. */
    private fun cumplirBloqueo(id: UUID) {
        val cuenta = recargar(id)
        cuenta.estadoBloqueo.bloqueadaHasta = Instant.now().minusSeconds(1)
        cuentas.save(cuenta)
    }

    private fun minutosDeBloqueo(id: UUID): Long {
        val cuenta = recargar(id)
        val hasta = cuenta.estadoBloqueo.bloqueadaHasta!!
        return Duration.between(Instant.now(), hasta).toMinutes() + 1
    }

    @Test
    fun `the lockout escalates one five fifteen and sixty minutes`() {
        val cuenta = cuentaActiva(password = password)

        listOf(1L, 5L, 15L, 60L).forEachIndexed { indice, esperados ->
            fallar(cuenta.email, 5)

            assertEquals(
                indice + 1,
                recargar(cuenta.id).estadoBloqueo.nivelBloqueo.toInt(),
                "lockout number ${indice + 1} must be at level ${indice + 1}"
            )
            assertEquals(
                esperados,
                minutosDeBloqueo(cuenta.id),
                "lockout number ${indice + 1} must last $esperados minutes"
            )

            cumplirBloqueo(cuenta.id)
        }
    }

    @Test
    fun `from the fifth lockout on it stays at sixty minutes`() {
        val cuenta = cuentaActiva(password = password)

        repeat(6) {
            fallar(cuenta.email, 5)
            cumplirBloqueo(cuenta.id)
        }
        fallar(cuenta.email, 5)

        assertEquals(4, recargar(cuenta.id).estadoBloqueo.nivelBloqueo.toInt(), "the level caps at 4")
        assertEquals(60L, minutosDeBloqueo(cuenta.id))
    }

    /**
     * **FR-016c, the rule the whole protection rests on, end to end.**
     *
     * A failure while the lockout is active must leave all three columns
     * untouched. If it extended the deadline, anyone could keep a person locked
     * out indefinitely without ever knowing their password - a free denial of
     * service, and in this product being locked out means being unable to clock
     * in, which opens a gap in a record that carries legal weight.
     */
    @Test
    fun `fifty failures during an active lockout change nothing in the database`() {
        val cuenta = cuentaActiva(password = password)
        fallar(cuenta.email, 5)

        val antes = recargar(cuenta.id).estadoBloqueo
        val nivelAntes = antes.nivelBloqueo
        val intentosAntes = antes.intentosFallidos
        val hastaAntes = antes.bloqueadaHasta!!

        fallar(cuenta.email, 50)

        val despues = recargar(cuenta.id).estadoBloqueo
        assertEquals(nivelAntes, despues.nivelBloqueo, "the level must not move")
        assertEquals(intentosAntes, despues.intentosFallidos, "the counter must not move")
        assertTrue(
            abs(Duration.between(hastaAntes, despues.bloqueadaHasta!!).toMillis()) < 1000,
            "the deadline must not move: if it did, anyone could keep somebody locked " +
                "out for ever without knowing their password, and here that means " +
                "stopping them clocking in. antes=$hastaAntes despues=${despues.bloqueadaHasta}"
        )
    }

    /** FR-016b: a successful sign-in sends the next lockout back to one minute. */
    @Test
    fun `a successful sign in resets the escalation`() {
        val cuenta = cuentaActiva(password = password)

        fallar(cuenta.email, 5)
        cumplirBloqueo(cuenta.id)
        fallar(cuenta.email, 5)
        assertEquals(2, recargar(cuenta.id).estadoBloqueo.nivelBloqueo.toInt())

        cumplirBloqueo(cuenta.id)
        autenticacion.iniciarSesion(cuenta.email, password)
        assertEquals(0, recargar(cuenta.id).estadoBloqueo.nivelBloqueo.toInt())

        fallar(cuenta.email, 5)
        assertEquals(
            1L,
            minutosDeBloqueo(cuenta.id),
            "back to one minute: somebody who mistyped one morning should not still be " +
                "serving an hour weeks later"
        )
    }

    /**
     * The level survives the expiry of the lockout - that is the whole point of
     * keeping it in a column rather than deriving it. A reset on expiry would
     * make every lockout last one minute.
     */
    @Test
    fun `the level survives the expiry of the lockout`() {
        val cuenta = cuentaActiva(password = password)
        fallar(cuenta.email, 5)
        cumplirBloqueo(cuenta.id)

        assertEquals(1, recargar(cuenta.id).estadoBloqueo.nivelBloqueo.toInt())

        fallar(cuenta.email, 5)
        assertEquals(5L, minutosDeBloqueo(cuenta.id), "the second lockout is five minutes")
    }
}
