package com.granatum.core

import com.granatum.core.domain.service.PasswordPolicy
import com.granatum.core.domain.type.PasswordRequirement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The password policy (FR-023, FR-023b).
 *
 * Pure unit tests: no Spring, no database, no MockK needed - the policy is an
 * `object` with one function, which is the shape principle V asks of a business
 * rule.
 */
class PasswordPolicyTest {

    @Test
    fun `a password meeting every requirement is accepted`() {
        assertTrue(PasswordPolicy.isValid("Granatum1!"))
        assertEquals(emptyList(), PasswordPolicy.validate("Granatum1!"))
    }

    @Test
    fun `too short is reported even when every class is present`() {
        // 7 characters, all four classes: the only thing missing is length.
        assertEquals(
            listOf(PasswordRequirement.MIN_LENGTH),
            PasswordPolicy.validate("Ab3!cde")
        )
    }

    @Test
    fun `each missing class is reported on its own`() {
        assertEquals(listOf(PasswordRequirement.MISSING_UPPERCASE), PasswordPolicy.validate("granatum1!"))
        assertEquals(listOf(PasswordRequirement.MISSING_LOWERCASE), PasswordPolicy.validate("GRANATUM1!"))
        assertEquals(listOf(PasswordRequirement.MISSING_DIGIT), PasswordPolicy.validate("Granatum!!"))
        assertEquals(listOf(PasswordRequirement.MISSING_SYMBOL), PasswordPolicy.validate("Granatum12"))
    }

    /**
     * The whole list, not the first failure. Someone getting it wrong deserves
     * to learn everything that is missing in one attempt rather than
     * discovering it one rejection at a time.
     */
    @Test
    fun `several failures are all reported at once`() {
        val unmet = PasswordPolicy.validate("abc")

        assertEquals(4, unmet.size, "short, no upper case, no digit, no symbol")
        assertContains(unmet, PasswordRequirement.MIN_LENGTH)
        assertContains(unmet, PasswordRequirement.MISSING_UPPERCASE)
        assertContains(unmet, PasswordRequirement.MISSING_DIGIT)
        assertContains(unmet, PasswordRequirement.MISSING_SYMBOL)
    }

    @Test
    fun `an empty password fails everything except the maximum`() {
        val unmet = PasswordPolicy.validate("")

        assertEquals(5, unmet.size)
        assertFalse(unmet.contains(PasswordRequirement.MAX_LENGTH))
    }

    /**
     * **The case that ruled out BCrypt.** 64 accented characters are 128 bytes
     * of UTF-8, and `BCryptPasswordEncoder.encode` throws above 72 - so BCrypt
     * could not satisfy FR-023b at all, which only forbids a maximum *below*
     * 64. This is the requirement that drove the choice of Argon2id, so it gets
     * a test rather than only a note in research.md.
     */
    @Test
    fun `a 64 character accented passphrase is accepted`() {
        val passphrase = "Á".repeat(30) + "á".repeat(32) + "1!"

        assertEquals(64, passphrase.length)
        assertEquals(
            126,
            passphrase.toByteArray(Charsets.UTF_8).size,
            "62 accented characters at two bytes each plus two ASCII: well past the 72 " +
                "bytes where BCrypt throws, which is the whole point of this case"
        )
        assertTrue(
            PasswordPolicy.isValid(passphrase),
            "FR-023b forbids a maximum below 64 characters, and this is exactly the " +
                "input BCrypt cannot hash"
        )
    }

    @Test
    fun `the maximum is 128 and 129 is rejected`() {
        val limit = "Aa1!" + "x".repeat(124)
        assertEquals(128, limit.length)
        assertTrue(PasswordPolicy.isValid(limit))

        val past = limit + "x"
        assertEquals(
            listOf(PasswordRequirement.MAX_LENGTH),
            PasswordPolicy.validate(past)
        )
    }

    @Test
    fun `whitespace is allowed inside but does not count as a symbol`() {
        assertEquals(
            listOf(PasswordRequirement.MISSING_SYMBOL),
            PasswordPolicy.validate("Granatum 12"),
            "a space must not pass for a symbol: it is the one character people type " +
                "by accident, and accepting it would weaken the rule without anyone noticing"
        )
        assertTrue(PasswordPolicy.isValid("Granatum 12!"))
    }

    /**
     * FR-023 forbids reproducing the password in the rejection, and these values
     * travel into an HTTP body and into logs.
     */
    @Test
    fun `no returned value contains any fragment of the password`() {
        val password = "zzSECRETOzz"
        val names = PasswordPolicy.validate(password).map { it.name }

        names.forEach { name ->
            assertFalse(
                name.contains("SECRETO", ignoreCase = true),
                "the policy must name the requirement, never the value: $name"
            )
        }
    }
}
