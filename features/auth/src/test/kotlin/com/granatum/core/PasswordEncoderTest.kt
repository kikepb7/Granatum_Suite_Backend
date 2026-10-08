package com.granatum.core

import com.granatum.core.infrastructure.crypto.HashSenuelo
import com.granatum.core.infrastructure.crypto.PasswordEncoderConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The invariant of constitution principle VI that had no test (SC-003).
 *
 * The principle requires passwords to be stored with an **adaptive, salted**
 * hash and never with a fast one. `PasswordEncoderConfig` does that, but until
 * this test existed nothing would have broken if somebody swapped the encoder
 * for `NoOpPasswordEncoder`, for a plain SHA, or simply kept Spring Security's
 * own Argon2 defaults - which cost 16 ms, a 13x regression against BCrypt(12).
 * A rule without a test is an intention, not a guarantee (principle V).
 *
 * `/speckit-analyze` is what found this gap; it was not in the original plan.
 *
 * Runs on a plain Spring context with only the configuration class, so it also
 * exercises the property binding - a typo in `auth.password.argon2.memory-kb`
 * would fail here - without needing a database or a web server.
 */
@SpringJUnitConfig(PasswordEncoderConfig::class)
@TestPropertySource(
    properties = [
        // Production values deliberately, not the module's reduced test ones:
        // the stored prefix carries the parameters, so this is what asserts that
        // what reaches the database is what was configured.
        "auth.password.argon2.memory-kb=65536",
        "auth.password.argon2.iterations=3",
        "auth.password.argon2.parallelism=1"
    ]
)
class PasswordEncoderTest {

    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var hashSenuelo: HashSenuelo

    private val password = "Granatum-2026!"

    /**
     * `PasswordEncoder.encode` is declared @Nullable in Spring Security 7, so
     * the non-null check is an assertion in its own right: a null would mean an
     * account nobody can ever sign in to.
     */
    private fun cifrar(valor: CharSequence): String =
        assertNotNull(passwordEncoder.encode(valor), "el encoder devolvio null")

    @Test
    fun `the stored hash declares argon2id and its parameters`() {
        val hash = cifrar(password)

        assertTrue(
            hash.startsWith("{argon2}\$argon2id\$"),
            "the prefix is what lets the parameters change later without a schema " +
                "migration, and it is also the only way to see from the database which " +
                "algorithm a row was written with: $hash"
        )
        assertTrue(
            hash.contains("m=65536,t=3,p=1"),
            "the configured parameters must travel inside the hash; if they do not, the " +
                "property binding is wrong and every password is being hashed with " +
                "whatever the library defaults to: $hash"
        )
    }

    /**
     * SC-003, the half that is easy to lose: salt is **per encoding**, not per
     * application. Two people choosing the same password must not end up with
     * the same stored value, or one cracked hash cracks every account that
     * shared it.
     */
    @Test
    fun `hashing the same password twice gives different results`() {
        val primero = cifrar(password)
        val segundo = cifrar(password)

        assertNotEquals(
            primero,
            segundo,
            "identical hashes mean there is no per-row salt, so two people with the " +
                "same password would be visibly identical in the database"
        )
        assertTrue(passwordEncoder.matches(password, primero))
        assertTrue(passwordEncoder.matches(password, segundo), "both must still verify")
    }

    @Test
    fun `matches accepts the original and rejects anything else`() {
        val hash = cifrar(password)

        assertTrue(passwordEncoder.matches(password, hash))
        assertFalse(passwordEncoder.matches("Granatum-2027!", hash))
        assertFalse(passwordEncoder.matches("", hash))
    }

    /**
     * The password must not be recoverable from what is stored (FR-004). A
     * substring check is a crude proxy, but it is the one that catches the
     * accident that matters: storing the value in clear, or with a reversible
     * encoding that leaves it visible.
     */
    @Test
    fun `the stored value contains no part of the password`() {
        val hash = cifrar(password)

        assertFalse(hash.contains("Granatum", ignoreCase = true))
        assertFalse(hash.contains("2026"))
    }

    /**
     * FR-023b, enforced all the way down: a 64-character accented passphrase is
     * 126 bytes of UTF-8, and `BCryptPasswordEncoder` throws above 72. This is
     * the requirement that chose the algorithm, so it is asserted against the
     * real encoder and not only against the policy.
     */
    @Test
    fun `a long accented passphrase hashes without truncation`() {
        val frase = "Á".repeat(30) + "á".repeat(32) + "1!"

        val hash = cifrar(frase)

        assertTrue(passwordEncoder.matches(frase, hash))
        assertFalse(
            passwordEncoder.matches(frase.substring(0, 36), hash),
            "a 36-character prefix is 72 bytes: if it matched, the encoder would be " +
                "silently truncating and the tail of the passphrase would be decoration"
        )
    }

    /**
     * The decoy for sign-in attempts against an unknown email (FR-003, SC-002).
     *
     * It has to be encoded with the **configured** encoder, because
     * `Argon2PasswordEncoder.matches` reads the parameters from the stored hash:
     * a decoy with different ones would take a different amount of time and
     * re-open the timing oracle it exists to close.
     */
    @Test
    fun `the decoy hash carries the same parameters as a real one`() {
        val real = cifrar(password)

        val parametros = Regex("""\$(m=\d+,t=\d+,p=\d+)\$""")
        assertEquals(
            parametros.find(real)?.groupValues?.get(1),
            parametros.find(hashSenuelo.valor)?.groupValues?.get(1),
            "decoy and real hashes must cost the same, or the 'no such account' path is " +
                "measurably faster and sign-in enumerates registered addresses"
        )
    }

    @Test
    fun `nothing verifies against the decoy`() {
        assertFalse(passwordEncoder.matches(password, hashSenuelo.valor))
        assertFalse(passwordEncoder.matches("", hashSenuelo.valor))
    }

    private fun assertEquals(esperado: String?, real: String?, mensaje: String) =
        kotlin.test.assertEquals(esperado, real, mensaje)

    /**
     * Feature 009, recovery of the only ADMIN: without sign-up requests the
     * hash can no longer come from one, so it is generated with the standard
     * `argon2` command-line tool. Its encoded output is the format this encoder
     * reads, parameters included - checked with the reference implementation's
     * published vector (`echo -n password | argon2 somesalt -t 2 -m 16 -p 4 -l 24`),
     * behind the `{argon2}` prefix the database stores.
     */
    @Test
    fun `a hash from the argon2 command-line tool is accepted`() {
        val deLaHerramienta = "{argon2}\$argon2i\$v=19\$m=65536,t=2,p=4\$c29tZXNhbHQ\$RdescudvJCsgt3ub+b+dWRWJTmaaJObG"

        assertTrue(passwordEncoder.matches("password", deLaHerramienta))
        assertFalse(passwordEncoder.matches("otra", deLaHerramienta))
    }
}
