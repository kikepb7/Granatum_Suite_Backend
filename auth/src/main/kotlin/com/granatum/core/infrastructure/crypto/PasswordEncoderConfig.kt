package com.granatum.core.infrastructure.crypto

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id, with parameters that were measured rather than inherited.
 *
 * ## Why Argon2 and not BCrypt
 *
 * Not a preference - a requirement. FR-023b forbids a maximum password length
 * below 64 characters, and `BCryptPasswordEncoder` throws
 * `IllegalArgumentException: password cannot be more than 72 bytes`. A
 * 64-character accented passphrase is 128 bytes of UTF-8, and 36 accented
 * characters already exceed 72, so BCrypt cannot satisfy FR-023b at all.
 *
 * The second reason is the policy: eight characters with composition rules leave
 * a small password space, and Argon2id is memory-hard, which raises the cost of
 * a GPU attack far more than BCrypt does for the same CPU time. That is the only
 * lever left when the minimum length is short.
 *
 * ## Why these numbers
 *
 * Measured on the development machine (Mac mini, 10 cores, JDK 21), minimum of
 * seven runs:
 *
 * ```
 *  16 MiB / t=2  (Spring Security defaults)   16 ms
 *  19 MiB / t=2  (OWASP)                      19 ms
 *  64 MiB / t=3  <- chosen                   110 ms
 * 128 MiB / t=3                              236 ms
 * BCrypt cost 12 (for reference)             217 ms
 * ```
 *
 * Spring Security's own defaults cost 16 ms: adopting Argon2 and keeping them
 * would be a **13x regression** against BCrypt(12), so the library's defaults
 * are exactly what must not be used here. 64 MiB / t=3 costs half the time of
 * BCrypt(12) but demands 64 MiB per verification, and it is that memory which
 * does not parallelise cheaply on a GPU. It also leaves ample room under SC-011
 * (< 2 s): even on hardware five times slower it would be ~550 ms.
 *
 * `parallelism = 1` and not 2: measured, it makes no difference to the time
 * (110 ms either way) because Argon2 splits the same memory between lanes, so
 * two threads per sign-in would only consume more of the pool under load without
 * making an attack any more expensive.
 *
 * **Recalibrate on the target hardware before deploying.** The table above
 * describes a 10-core desktop; a small container will differ a lot. The
 * measurement procedure is in `specs/002-auth/research.md`.
 *
 * ## Why the delegating encoder
 *
 * Principle VI names it. The stored hash carries its prefix
 * (`{argon2}$argon2id$v=19$m=65536,t=3,p=1$...`), so changing the parameters -
 * or the algorithm - later needs no schema migration and no version column:
 * every existing hash still verifies with its own parameters, which travel
 * inside it.
 */
@Configuration
class PasswordEncoderConfig(
    @param:Value("\${auth.password.argon2.memory-kb}") private val memoriaKb: Int,
    @param:Value("\${auth.password.argon2.iterations}") private val iteraciones: Int,
    @param:Value("\${auth.password.argon2.parallelism}") private val paralelismo: Int
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        val argon2 = Argon2PasswordEncoder(
            LONGITUD_SAL,
            LONGITUD_HASH,
            paralelismo,
            memoriaKb,
            iteraciones
        )
        return DelegatingPasswordEncoder(ID_ARGON2, mapOf(ID_ARGON2 to argon2))
    }

    /**
     * A hash of a random password, computed at startup, for sign-in attempts
     * against an email that does not exist.
     *
     * FR-003 and SC-002 require an unknown email and a wrong password to be
     * indistinguishable in code, body **and time**. Without something to verify,
     * the "no such account" path would skip the ~110 ms of Argon2 entirely and
     * the difference would be trivial to measure, turning sign-in into an oracle
     * that enumerates which addresses are registered.
     *
     * It must be produced by the **configured** encoder and not be a constant:
     * `Argon2PasswordEncoder.matches` reads the parameters *from the stored
     * hash*, so a decoy encoded with different ones would take a different
     * amount of time and re-open the very side channel it exists to close.
     *
     * The password itself is thrown away - it is never needed again, and nothing
     * must be able to present it.
     */
    @Bean
    fun hashSenuelo(passwordEncoder: PasswordEncoder): HashSenuelo {
        val aleatoria = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = passwordEncoder.encode(Base64.getEncoder().encodeToString(aleatoria))
        // `PasswordEncoder.encode` is declared @Nullable in Spring Security 7.
        // A null here would mean the decoy does not exist, and the "no such
        // account" path would then skip the hash entirely and re-open the timing
        // oracle FR-003 closes. Failing at startup is the only safe answer:
        // silently carrying on would leave a side channel nobody would notice.
        return HashSenuelo(
            requireNotNull(hash) { "el encoder no produjo hash para el senuelo" }
        )
    }

    companion object {
        const val ID_ARGON2 = "argon2"

        /** Not reduced in tests: it is part of the format `PasswordEncoderTest` checks. */
        const val LONGITUD_SAL = 16
        const val LONGITUD_HASH = 32
    }
}

/**
 * Wrapper around the decoy hash so it can be injected by type.
 *
 * A bare `String` bean would be ambiguous with any other string bean and would
 * read, at the injection site, as though it were somebody's actual password.
 */
data class HashSenuelo(val valor: String)
