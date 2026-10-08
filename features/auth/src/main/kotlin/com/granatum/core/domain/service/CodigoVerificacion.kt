package com.granatum.core.domain.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/**
 * The code a person gets when signing up and tells the ADMIN in person
 * (feature 005, FR-004, research.md D-001). It is what proves that the person in
 * front of the ADMIN made *this* request, since there is no email to prove it.
 *
 * Pure: no Spring, no database.
 *
 * ## Why SHA-256 and not Argon2
 *
 * 40 bits that live at most a week, whose only use is to let an ADMIN - who
 * already holds every power - approve. Five wrong attempts cancel the request,
 * so the online guess rate is 5 per request. Spending 110 ms and 64 MiB per
 * check would buy nothing. The request id salts it, so two requests that
 * happened to draw the same code do not share a fingerprint.
 */
object CodigoVerificacion {

    /** 32 symbols: no 0/O or 1/I, which are confused when read aloud. */
    const val ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LONGITUD = 8

    private val aleatorio = SecureRandom()

    fun generar(): String =
        buildString(LONGITUD) { repeat(LONGITUD) { append(ALFABETO[aleatorio.nextInt(ALFABETO.length)]) } }

    /** SHA-256 of `id || code`, in lowercase hex: 64 characters. */
    fun huella(solicitudId: UUID, codigo: String): String =
        HexFormat.of().formatHex(digest(solicitudId, normalizar(codigo)))

    /**
     * Constant-time comparison: the ADMIN types it, so a timing leak is not a
     * realistic channel, but the comparison costs nothing to get right.
     * Accepts lowercase and spaces, because people read it out in groups.
     */
    fun coincide(solicitudId: UUID, codigo: String, huellaGuardada: String): Boolean {
        val normalizado = normalizar(codigo)
        if (normalizado.isEmpty()) return false
        val esperada = runCatching { HexFormat.of().parseHex(huellaGuardada) }.getOrNull() ?: return false
        return MessageDigest.isEqual(digest(solicitudId, normalizado), esperada)
    }

    private fun normalizar(codigo: String): String =
        codigo.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)

    private fun digest(solicitudId: UUID, codigo: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("$solicitudId$codigo".toByteArray(Charsets.UTF_8))
}
