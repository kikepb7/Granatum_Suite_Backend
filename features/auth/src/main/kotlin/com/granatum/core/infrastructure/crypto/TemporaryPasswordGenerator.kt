package com.granatum.core.infrastructure.crypto

import com.granatum.core.domain.service.PasswordPolicy
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
class TemporaryPasswordGenerator {

    private val random = SecureRandom()

    fun generate(): String {
        // One character from each class first, so the four requirements hold by
        // construction rather than by luck - with a retry loop, a bad draw
        // would make sign-up intermittently slow for no reason.
        val required = listOf(
            UPPERCASE.pick(),
            LOWERCASE.pick(),
            DIGITS.pick(),
            SYMBOLS.pick()
        )
        val allChars = UPPERCASE + LOWERCASE + DIGITS + SYMBOLS
        val rest = List(LENGTH - required.size) { allChars.pick() }

        // Shuffled with the CSPRNG, not with the default one: unshuffled, the
        // first four positions would always be upper, lower, digit, symbol,
        // which narrows the search space for anyone who knows the generator.
        return (required + rest).shuffled(random).joinToString("")
    }

    private fun String.pick(): Char = this[random.nextInt(length)]

    companion object {
        const val LENGTH = 16

        private const val UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val LOWERCASE = "abcdefghijkmnopqrstuvwxyz"
        private const val DIGITS = "23456789"

        /**
         * `I`, `O`, `l` and `1`/`0` are absent from the sets above on purpose:
         * this password gets read aloud or copied by hand, and a character
         * nobody can tell apart turns into a support call. The cost is a
         * slightly smaller alphabet, which sixteen characters absorbs easily.
         *
         * The symbols avoid quotes and backslashes for the same practical
         * reason - they get mangled by shells and spreadsheets.
         *
         * Every character here must satisfy [PasswordPolicy]'s notion of a
         * symbol; `TemporaryPasswordGeneratorTest` is what holds the two
         * together, because if they ever diverged the system would generate
         * passwords it then refuses itself.
         */
        private const val SYMBOLS = "!#%&*+-=?@_"
    }
}
