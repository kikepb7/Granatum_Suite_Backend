package com.granatum.core.service

import java.security.SecureRandom
import java.util.Base64

/**
 * A fresh 256-bit base64 key for tests that construct [JwtService] directly.
 *
 * These tests only round-trip tokens, so they have no need of a stable key, and
 * generating one keeps signing keys out of the repository entirely. The value
 * previously hard-coded here was the same string that sat as the production
 * fallback in application.yml - which is how a "test only" key becomes a
 * production one.
 */
fun randomTestJwtKeyBase64(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}
