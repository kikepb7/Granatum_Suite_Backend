package com.granatum.core

import com.granatum.core.domain.service.PasswordPolicy
import com.granatum.core.infrastructure.crypto.TemporaryPasswordGenerator
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
 * It deliberately lives after `PasswordPolicy` in the task order, not next to
 * the generator: the policy is its only judge, so writing it earlier would be
 * writing it against nothing.
 */
class TemporaryPasswordGeneratorTest {

    private val generator = TemporaryPasswordGenerator()

    @Test
    fun `a thousand generated passwords all satisfy the policy`() {
        val failures = (1..1000)
            .map { generator.generate() }
            .map { it to PasswordPolicy.validate(it) }
            .filter { (_, unmet) -> unmet.isNotEmpty() }

        assertEquals(
            emptyList(),
            // Only the broken requirements, never the passwords themselves:
            // a failure message here would print generated secrets into CI logs.
            failures.map { (_, unmet) -> unmet },
            "generator and policy have diverged - the system would be generating " +
                "passwords it refuses itself, and sign-up would break with no way in"
        )
    }

    @Test
    fun `every generated password has the declared length`() {
        repeat(50) {
            assertEquals(TemporaryPasswordGenerator.LENGTH, generator.generate().length)
        }
    }

    @Test
    fun `two generated passwords are not the same`() {
        val samples = (1..200).map { generator.generate() }.toSet()
        assertEquals(
            200,
            samples.size,
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
        val firstCharacters = (1..100).map { generator.generate().first() }

        assertTrue(
            firstCharacters.any { it.isUpperCase() } &&
                firstCharacters.any { !it.isLetterOrDigit() },
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
        val ambiguous = setOf('I', 'l', 'O', '0', '1')
        repeat(200) {
            val password = generator.generate()
            assertTrue(
                password.none { it in ambiguous },
                "an ambiguous character reached a password that someone has to read aloud"
            )
        }
    }
}
