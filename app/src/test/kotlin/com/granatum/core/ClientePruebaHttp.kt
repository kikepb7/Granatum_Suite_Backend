package com.granatum.core

import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A minimal real-socket HTTP client for the cross-module tests in `app`.
 *
 * Shared because four test classes here need the same three operations, and the
 * alternative was copying the same `exchange` boilerplate into each.
 * `ContratoHttpIT` keeps its own copy on purpose: it predates this file and is
 * the regression guard for the Jackson 3 outage, so it is left untouched.
 */
class ClientePruebaHttp(puerto: Int) {

    data class Respuesta(val estado: Int, val cuerpo: String)

    private val cliente = RestClient.builder().baseUrl("http://localhost:$puerto").build()

    fun post(ruta: String, json: String = "{}", token: String? = null): Respuesta =
        cliente.post().uri(ruta)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .body(json)
            .exchange({ _, r -> Respuesta(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    fun get(ruta: String, token: String? = null): Respuesta =
        cliente.get().uri(ruta)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .exchange({ _, r -> Respuesta(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    companion object {
        /** Reads the raw wire bytes with a regex rather than parsing JSON, on purpose. */
        fun campo(cuerpo: String, nombre: String): String? =
            Regex("\"$nombre\"\\s*:\\s*\"?([^\",}]*)\"?").find(cuerpo)?.groupValues?.get(1)

        /** A DNI with a correct check letter; the validator rejects anything else. */
        fun dniValido(): String {
            val numero = (70_000_000..79_999_999).random()
            return "$numero${"TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]}"
        }

        /**
         * Recent and whole-second. The clock validator rejects anything more than
         * 72 hours off, so a fixed date in a test breaks the week after it is
         * written - which is how feature 001's quickstart went stale.
         */
        fun haceMinutos(n: Long): Instant =
            Instant.now().minus(n, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
    }
}
