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
 * `HttpContractIT` keeps its own copy on purpose: it predates this file and is
 * the regression guard for the Jackson 3 outage, so it is left untouched.
 */
class HttpTestClient(port: Int) {

    data class Response(val status: Int, val body: String)

    private val client = RestClient.builder().baseUrl("http://localhost:$port").build()

    fun post(path: String, json: String = "{}", token: String? = null): Response =
        client.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .body(json)
            .exchange({ _, r -> Response(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    fun get(path: String, token: String? = null): Response =
        client.get().uri(path)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .exchange({ _, r -> Response(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    companion object {
        /** Reads the raw wire bytes with a regex rather than parsing JSON, on purpose. */
        fun field(body: String, name: String): String? =
            Regex("\"$name\"\\s*:\\s*\"?([^\",}]*)\"?").find(body)?.groupValues?.get(1)

        /** A DNI with a correct check letter; the validator rejects anything else. */
        fun validDni(): String {
            val number = (70_000_000..79_999_999).random()
            return "$number${"TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]}"
        }

        /**
         * Recent and whole-second. The clock validator rejects anything more than
         * 72 hours off, so a fixed date in a test breaks the week after it is
         * written - which is how feature 001's quickstart went stale.
         */
        fun minutesAgo(n: Long): Instant =
            Instant.now().minus(n, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
    }
}
