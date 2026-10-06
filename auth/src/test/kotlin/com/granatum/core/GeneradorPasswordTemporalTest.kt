package com.granatum.core

import com.granatum.core.domain.service.PoliticaPassword
import com.granatum.core.infrastructure.crypto.GeneradorPasswordTemporal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generator and the policy, held together.
 *
 * This test exists because the two are separate pieces that must never diverge.
 * The day they did, the system would generate passwords it then refuses itself:
 * sign-up would break with no way in, and the failure would look like a bug in
 * the account service rather than in the generator (D-011).
 *
 * It deliberately lives after `PoliticaPassword` in the task order, not next to
 * the generator: the policy is its only judge, so writing it earlier would be
 * writing it against nothing.
 */
class GeneradorPasswordTemporalTest {

    private val generador = GeneradorPasswordTemporal()

    @Test
    fun `a thousand generated passwords all satisfy the policy`() {
        val fallos = (1..1000)
            .map { generador.generar() }
            .map { it to PoliticaPassword.validar(it) }
            .filter { (_, incumplidos) -> incumplidos.isNotEmpty() }

        assertEquals(
            emptyList(),
            // Only the broken requirements, never the passwords themselves:
            // a failure message here would print generated secrets into CI logs.
            fallos.map { (_, incumplidos) -> incumplidos },
            "generator and policy have diverged - the system would be generating " +
                "passwords it refuses itself, and sign-up would break with no way in"
        )
    }

    @Test
    fun `every generated password has the declared length`() {
        repeat(50) {
            assertEquals(GeneradorPasswordTemporal.LONGITUD, generador.generar().length)
        }
    }

    @Test
    fun `two generated passwords are not the same`() {
        val muestras = (1..200).map { generador.generar() }.toSet()
        assertEquals(
            200,
            muestras.size,
            "a collision at this sample size would mean the generator is not using the " +
                "CSPRNG it claims to"
        )
    }

    /**
     * The required class always appears first in the pre-shuffle list, so an
     * unshuffled generator would put upper, lower, digit and symbol in fixed
     * positions and narrow the search space for anyone who read this code.
     */
    @Test
    fun `the required classes are not always in the same positions`() {
        val primerosCaracteres = (1..100).map { generador.generar().first() }

        assertTrue(
            primerosCaracteres.any { it.isUpperCase() } &&
                primerosCaracteres.any { !it.isLetterOrDigit() },
            "the first position must vary: unshuffled, it would always be an upper-case " +
                "letter and the effective search space would be smaller than it looks"
        )
    }

    /**
     * Characters that cannot be told apart are absent on purpose: this password
     * gets read aloud or copied by hand, and an ambiguous glyph becomes a
     * support call.
     */
    @Test
    fun `no generated password contains an ambiguous character`() {
        val ambiguos = setOf('I', 'l', 'O', '0', '1')
        repeat(200) {
            val password = generador.generar()
            assertTrue(
                password.none { it in ambiguos },
                "an ambiguous character reached a password that someone has to read aloud"
            )
        }
    }
}
