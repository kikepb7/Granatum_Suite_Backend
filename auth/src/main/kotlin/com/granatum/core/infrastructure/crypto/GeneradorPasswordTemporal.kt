package com.granatum.core.infrastructure.crypto

import com.granatum.core.domain.service.PoliticaPassword
import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * Generates the temporary password handed out on sign-up and on reset.
 *
 * ## Why the system generates it rather than the ADMIN choosing it
 *
 * FR-023a requires the same policy to apply to the temporary password "that an
 * `ADMIN` generates or accepts". Generating it closes the dangerous half of that
 * sentence: if the administrator picks it, nothing stops them using the same one
 * for the whole workforce, and the hash would be irrelevant because the password
 * would be guessable by being known. Generated, the `ADMIN` passes it on through
 * whatever channel they use but does not choose it.
 *
 * Sixteen characters rather than the eight the policy demands: there is no
 * usability argument for a short password nobody has to remember - it is typed
 * once and replaced.
 */
@Component
class GeneradorPasswordTemporal {

    private val aleatorio = SecureRandom()

    fun generar(): String {
        // One character from each class first, so the four requirements hold by
        // construction rather than by luck - with a retry loop, a bad draw
        // would make sign-up intermittently slow for no reason.
        val obligatorios = listOf(
            MAYUSCULAS.aleatorio(),
            MINUSCULAS.aleatorio(),
            DIGITOS.aleatorio(),
            SIMBOLOS.aleatorio()
        )
        val todos = MAYUSCULAS + MINUSCULAS + DIGITOS + SIMBOLOS
        val resto = List(LONGITUD - obligatorios.size) { todos.aleatorio() }

        // Shuffled with the CSPRNG, not with the default one: unshuffled, the
        // first four positions would always be upper, lower, digit, symbol,
        // which narrows the search space for anyone who knows the generator.
        return (obligatorios + resto).shuffled(aleatorio).joinToString("")
    }

    private fun String.aleatorio(): Char = this[aleatorio.nextInt(length)]

    companion object {
        const val LONGITUD = 16

        private const val MAYUSCULAS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val MINUSCULAS = "abcdefghijkmnopqrstuvwxyz"
        private const val DIGITOS = "23456789"

        /**
         * `I`, `O`, `l` and `1`/`0` are absent from the sets above on purpose:
         * this password gets read aloud or copied by hand, and a character
         * nobody can tell apart turns into a support call. The cost is a
         * slightly smaller alphabet, which sixteen characters absorbs easily.
         *
         * The symbols avoid quotes and backslashes for the same practical
         * reason - they get mangled by shells and spreadsheets.
         *
         * Every character here must satisfy [PoliticaPassword]'s notion of a
         * symbol; `GeneradorPasswordTemporalTest` is what holds the two
         * together, because if they ever diverged the system would generate
         * passwords it then refuses itself.
         */
        private const val SIMBOLOS = "!#%&*+-=?@_"
    }
}
