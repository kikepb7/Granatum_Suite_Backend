package com.granatum.core.service

import java.util.Locale

/**
 * The single place an email address is normalised.
 *
 * FR-028 requires two writes of the same address not to produce two accounts,
 * which is the same reasoning already applied to the identity document in
 * feature 001. In **one** place, because normalising in the controller and
 * forgetting in the reset service is precisely how one person ends up with two
 * accounts - and the symptom would appear months later as "I cannot sign in"
 * from someone who demonstrably has an account.
 *
 * `Locale.ROOT` and not a bare `lowercase()`: under the Turkish locale `I` does
 * not lower-case to `i`, so the uniqueness of an email address would depend on
 * the server's locale. That is a bug that cannot be reproduced anywhere the
 * developer looks.
 */
object NormalizadorEmail {

    fun normalizar(email: String): String = email.trim().lowercase(Locale.ROOT)
}
