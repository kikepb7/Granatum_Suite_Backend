package com.granatum.core.domain.service

import com.granatum.core.domain.type.RequisitoIncumplido

/**
 * The password policy, as a pure function.
 *
 * Eight characters with an upper-case letter, a lower-case letter, a digit and a
 * symbol (FR-023). This was the product owner's decision, taken knowing the
 * alternative: current NIST guidance asks for length (12+) and no composition
 * rules, because eight characters with rules hold less entropy than twelve free
 * ones. The consequence is recorded in the spec and it lands on the hash: with a
 * smaller password space, the cost of the adaptive hash matters more, which is
 * why its parameters were measured rather than inherited.
 *
 * ## The maximum
 *
 * FR-023b only forbids a maximum **below 64**. 128 is comfortably above it,
 * allows a long passphrase, and stops a multi-megabyte request body from
 * becoming Argon2 work - which costs memory, not just CPU. With no maximum at
 * all the effective limit would be Tomcat's request size, which is an accidental
 * place for a business rule.
 *
 * It is also the requirement that ruled out BCrypt: a 64-character accented
 * passphrase is 128 bytes of UTF-8, and `BCryptPasswordEncoder` throws above 72.
 *
 * ## Why the whole list
 *
 * Returns every requirement that failed, not the first. Someone getting it wrong
 * deserves to learn everything that is missing in one attempt rather than
 * discovering it one rejection at a time.
 */
object PoliticaPassword {

    const val LONGITUD_MINIMA = 8
    const val LONGITUD_MAXIMA = 128

    /**
     * Anything that is not a letter, a digit or whitespace. Deliberately not a
     * fixed list of symbols: a list would reject a perfectly good character
     * just because nobody thought of it, and would differ from whatever the
     * client-side validation happened to allow.
     */
    private fun esSimbolo(c: Char): Boolean = !c.isLetterOrDigit() && !c.isWhitespace()

    fun validar(password: String): List<RequisitoIncumplido> {
        val incumplidos = mutableListOf<RequisitoIncumplido>()

        if (password.length < LONGITUD_MINIMA) incumplidos += RequisitoIncumplido.LONGITUD_MINIMA
        if (password.length > LONGITUD_MAXIMA) incumplidos += RequisitoIncumplido.LONGITUD_MAXIMA
        if (password.none { it.isUpperCase() }) incumplidos += RequisitoIncumplido.FALTA_MAYUSCULA
        if (password.none { it.isLowerCase() }) incumplidos += RequisitoIncumplido.FALTA_MINUSCULA
        if (password.none { it.isDigit() }) incumplidos += RequisitoIncumplido.FALTA_DIGITO
        if (password.none { esSimbolo(it) }) incumplidos += RequisitoIncumplido.FALTA_SIMBOLO

        return incumplidos
    }

    fun esValida(password: String): Boolean = validar(password).isEmpty()
}
