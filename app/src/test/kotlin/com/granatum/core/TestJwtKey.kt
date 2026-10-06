package com.granatum.core

import java.security.SecureRandom
import java.util.Base64

/**
 * A fresh 256-bit base64 key for tests in this module that construct
 * `JwtService` directly.
 *
 * `common` has an identical helper in **its** test source set, and the
 * duplication is deliberate: test source sets are not shared across modules, and
 * four lines repeated beats either adding the java-test-fixtures plugin or
 * shipping test helpers inside `common`'s production jar. That reasoning was
 * already recorded on the private copy this file replaces.
 *
 * What is *not* deliberate is duplicating it twice inside the same source set,
 * which is what happened when a second test here needed it - hence this file.
 *
 * Generated rather than fixed so no signing key lives in the repository. The
 * value once hard-coded in `common` was the same string that sat as the
 * production fallback in `application.yml`, which is how a "test only" key
 * becomes a production one.
 */
fun randomTestJwtKeyBase64(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}
