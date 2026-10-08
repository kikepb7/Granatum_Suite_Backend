package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

/**
 * FR-005: with no forwarding strategy configured - the default - an invented
 * `X-Forwarded-For` does not make a new client. If it did, anyone could walk
 * past every limit by changing a header on each request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["server.forward-headers-strategy=none", "seguridad.limites.login=2/1m"])
class LimiteSinProxyIT {

    @LocalServerPort
    var puerto: Int = 0

    private fun login(xff: String): Int =
        RestClient.create("http://localhost:$puerto").post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", xff)
            .body("""{"email":"nadie@granatum.es","password":"incorrecta"}""")
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    @Test
    fun `changing X-Forwarded-For on every request does not escape the limit`() {
        login("192.0.2.1")
        login("192.0.2.2")

        assertEquals(429, login("192.0.2.3"))
    }
}
