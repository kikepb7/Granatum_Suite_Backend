package com.granatum.core.service

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Mints opaque refresh tokens and hashes them for storage.
 *
 * ## Why opaque and not a JWT
 *
 * FR-011 requires the value not to be recoverable from the database, and FR-008
 * requires each token to work exactly once. The second forces a database lookup
 * on every renewal, and from there a signed JWT adds nothing: its
 * self-containment - the only reason to use one - goes unused. An opaque value
 * is also **impossible to mistake for an access token**: it has no signature and
 * no claims, so it cannot be presented in an `Authorization: Bearer` header and
 * pass the filter.
 *
 * ## Why SHA-256 here and never for a password
 *
 * Constitution principle VI forbids fast hashes "of the MD5/SHA kind" - **for
 * passwords**. The reason behind that ban is that a password has little entropy
 * and is guessable by brute force, so hashing it must be deliberately slow. A
 * refresh token is 256 random bits: there is nothing to guess, and a slow hash
 * would only make every legitimate renewal more expensive. What SHA-256 does
 * give is exactly what FR-011 asks: whoever reads the table cannot use what
 * they see.
 *
 * This distinction is documented because it is precisely the kind of decision a
 * future review would read as a violation of principle VI if it were not
 * explained.
 */
@Component
class GeneradorTokenRenovacion {

    private val aleatorio = SecureRandom()

    /** 32 bytes of CSPRNG output, base64url without padding so it is URL-safe. */
    fun generar(): String {
        val bytes = ByteArray(LONGITUD_BYTES)
        aleatorio.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * SHA-256 in lower-case hexadecimal: always exactly 64 characters, which is
     * what the `CHECK` on `sesiones_renovacion.token_hash` asserts.
     *
     * A fresh `MessageDigest` per call, not a shared field: the class is not
     * thread-safe, and a shared instance would interleave digests under
     * concurrent renewals and produce hashes that match nothing.
     */
    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val LONGITUD_BYTES = 32
    }
}
