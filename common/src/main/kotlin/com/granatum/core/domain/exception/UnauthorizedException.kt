package com.granatum.core.domain.exception

/**
 * The caller has not proved who they are, or the proof is no longer good.
 *
 * `open` and with an optional message, for the same reason as
 * [ForbiddenException]: a feature needs to say *which* kind of failure this was
 * - invalid credentials, an unusable refresh token, a dismissed person - and
 * those map to different error codes even though they share the 401.
 *
 * It was `final` with a fixed message until the `auth` feature needed to extend
 * it, which made it the odd one out among its three siblings here. That was not
 * a deliberate design: it was the one of the four nobody had had to subclass
 * yet.
 *
 * The message reaches clients and logs alike, so it must never carry an email
 * address, a password, a token or any fragment of one (principle VI). Say what
 * kind of authentication failed, never with which value.
 */
open class UnauthorizedException(
    override val message: String = "Missing or invalid authentication details"
) : RuntimeException(message)
