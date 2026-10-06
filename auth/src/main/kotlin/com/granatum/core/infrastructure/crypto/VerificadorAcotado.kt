package com.granatum.core.infrastructure.crypto

import com.granatum.core.domain.exception.VerificacionSaturadaException
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Caps how many password hashes run at once.
 *
 * ## Why this exists
 *
 * It is introduced by the choice of Argon2id with 64 MiB, and without it that
 * choice would open a hole the spec never contemplated.
 * `POST /api/auth/login` is public and every call reserves 64 MiB. Tomcat
 * allows 200 threads by default: 200 simultaneous sign-ins are **12.8 GiB** of
 * memory reservation that anyone can trigger **without any credential**. The
 * natural defence - a per-origin rate limit - is explicitly out of scope by
 * decision of the spec, where it belongs to the hardening feature.
 *
 * With four permits the ceiling is 4 x 64 MiB = 256 MiB whatever the load.
 * Measured: ten unbounded simultaneous sign-ins take 365 ms of wall clock on a
 * ten-core machine, so a queue of four does not penalise real use - a small
 * workforce does not sign in four times within the same second - and it turns a
 * memory exhaustion into a wait.
 *
 * ## Why the timeout is configuration
 *
 * `auth.hash.espera-ms` is the only thing that decides whether a queued sign-in
 * breaches SC-011 (< 2 s). The default of 1000 ms leaves a full second of margin
 * over that budget; as a hard-coded constant it could not be adjusted when the
 * Argon2 parameters are recalibrated for the target hardware, which is exactly
 * when it would need to move.
 *
 * ## What it does not leak
 *
 * Saturation does not depend on the account, so it introduces no side channel
 * that would distinguish existing emails from absent ones (FR-003).
 */
@Component
class VerificadorAcotado(
    private val passwordEncoder: PasswordEncoder,
    @param:Value("\${auth.hash.concurrencia}") private val concurrencia: Int,
    @param:Value("\${auth.hash.espera-ms}") private val esperaMs: Long
) {

    // Fair, so a queued request is served in order instead of being overtaken
    // indefinitely and timing out while later arrivals succeed.
    private val permisos = Semaphore(concurrencia, true)

    fun codificar(password: CharSequence): String = acotado {
        // @Nullable in Spring Security 7's signature. Storing a null hash would
        // create an account nobody can ever sign in to, so it fails loudly.
        requireNotNull(passwordEncoder.encode(password)) {
            "el encoder no produjo hash"
        }
    }

    fun coincide(password: CharSequence, hashAlmacenado: String): Boolean =
        acotado { passwordEncoder.matches(password, hashAlmacenado) }

    private fun <T> acotado(bloque: () -> T): T {
        if (!permisos.tryAcquire(esperaMs, TimeUnit.MILLISECONDS)) {
            throw VerificacionSaturadaException()
        }
        // The release must survive any failure: a semaphore that does not give
        // its permits back is a worse outage than the one it prevents, because
        // it is permanent and needs a restart.
        try {
            return bloque()
        } finally {
            permisos.release()
        }
    }
}
