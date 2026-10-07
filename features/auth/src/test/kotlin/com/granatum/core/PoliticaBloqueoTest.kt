package com.granatum.core

import com.granatum.core.domain.model.EstadoBloqueo
import com.granatum.core.domain.service.PoliticaBloqueo
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The escalating lockout (FR-013 to FR-016c).
 *
 * A pure unit test with a fixed instant, which is only possible because the
 * policy is a pure function. Expressed as a SQL `CASE` the four durations would
 * live where no unit test could reach them.
 */
class PoliticaBloqueoTest {

    private val ahora: Instant = Instant.parse("2026-10-05T09:00:00Z")

    private fun fallarVeces(veces: Int, desde: EstadoBloqueo = EstadoBloqueo()): EstadoBloqueo {
        var estado = desde
        repeat(veces) { estado = PoliticaBloqueo.trasFallo(estado, ahora) }
        return estado
    }

    @Test
    fun `four failures do not lock the account`() {
        val estado = fallarVeces(4)

        assertEquals(4, estado.intentosFallidos)
        assertEquals(0, estado.nivel)
        assertNull(estado.bloqueadaHasta)
        assertFalse(estado.estaBloqueada(ahora))
    }

    @Test
    fun `the fifth failure locks it for one minute`() {
        val estado = fallarVeces(5)

        assertEquals(1, estado.nivel)
        assertEquals(ahora.plus(Duration.ofMinutes(1)), estado.bloqueadaHasta)
        assertTrue(estado.estaBloqueada(ahora))
        assertEquals(
            0,
            estado.intentosFallidos,
            "the counter resets when the lockout is applied: FR-016c says the next " +
                "lockout needs another five failures once this one has expired, and " +
                "without the reset the first failure afterwards would re-lock instantly"
        )
    }

    /** FR-016a and SC-012: 1, 5, 15, 60. */
    @Test
    fun `the lockout escalates one five fifteen and sixty minutes`() {
        val esperadas = listOf(1L, 5L, 15L, 60L)
        var estado = EstadoBloqueo()
        var instante = ahora

        esperadas.forEachIndexed { indice, minutos ->
            estado = fallarVeces(5, estado.copy(bloqueadaHasta = null))
            assertEquals(indice + 1, estado.nivel)
            assertEquals(
                instante.plus(Duration.ofMinutes(minutos)),
                estado.bloqueadaHasta,
                "lockout number ${indice + 1} must last $minutos minutes"
            )
            instante = ahora
        }
    }

    @Test
    fun `the fifth and later lockouts stay at sixty minutes`() {
        var estado = EstadoBloqueo(nivel = 4)
        estado = fallarVeces(5, estado)

        assertEquals(4, estado.nivel, "the level caps at 4")
        assertEquals(ahora.plus(Duration.ofMinutes(60)), estado.bloqueadaHasta)
    }

    /**
     * **FR-016c, the rule the whole protection rests on.**
     *
     * A failure while a lockout is active must leave the state byte for byte as
     * it was. If it extended the deadline, anyone could keep a person locked out
     * indefinitely without ever knowing their password - a free denial of
     * service, and in this product being locked out means being unable to clock
     * in, which opens a gap in a record that carries legal weight.
     */
    @Test
    fun `a failure during an active lockout changes absolutely nothing`() {
        val bloqueado = fallarVeces(5)
        val deadlineOriginal = bloqueado.bloqueadaHasta

        var estado = bloqueado
        repeat(50) { estado = PoliticaBloqueo.trasFallo(estado, ahora.plusSeconds(10)) }

        assertEquals(
            bloqueado,
            estado,
            "fifty failures during the lockout must not change the state at all - " +
                "otherwise the protection becomes a denial of service against the person"
        )
        assertEquals(deadlineOriginal, estado.bloqueadaHasta)
    }

    @Test
    fun `once the lockout expires failures count again`() {
        val bloqueado = fallarVeces(5)
        val despues = bloqueado.bloqueadaHasta!!.plusSeconds(1)

        val tras = PoliticaBloqueo.trasFallo(bloqueado, despues)

        assertEquals(1, tras.intentosFallidos)
        assertEquals(1, tras.nivel, "the level survives the expiry: that is what makes the next one longer")
    }

    /** FR-015 and FR-016b. */
    @Test
    fun `a successful sign in clears the counter and the level`() {
        val tras = PoliticaBloqueo.trasExito()

        assertEquals(0, tras.intentosFallidos)
        assertEquals(
            0,
            tras.nivel,
            "resetting the level is the part that matters: somebody who mistyped once " +
                "should not still carry a one-hour penalty weeks later"
        )
        assertNull(tras.bloqueadaHasta)
    }

    @Test
    fun `the lockout lifts exactly at its deadline and not a moment later`() {
        val estado = fallarVeces(5)
        val limite = estado.bloqueadaHasta!!

        assertTrue(estado.estaBloqueada(limite.minusMillis(1)))
        assertFalse(estado.estaBloqueada(limite), "at the deadline the account is usable again")
    }

    @Test
    fun `a state that was never locked is never blocked`() {
        assertFalse(EstadoBloqueo().estaBloqueada(ahora))
    }
}
