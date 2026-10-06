package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `503` that bounds the memory a flood of sign-ins can reserve.
 *
 * ## Why this test exists
 *
 * The semaphore is the plan's answer to a hole the Argon2 parameters open:
 * `POST /api/auth/login` is public and every verification reserves 64 MiB, so
 * Tomcat's 200 default threads are **12.8 GiB** anyone can trigger without a
 * credential. Per-origin rate limiting is out of scope by decision of the spec.
 *
 * `VerificadorAcotadoTest` already proves the semaphore in isolation with MockK.
 * What is untested without this file is the **HTTP end** of it: that saturation
 * surfaces as `503 SERVICIO_SATURADO` with `Retry-After` rather than as a 500,
 * and in particular that `AuthExceptionHandler` wins over
 * `CommonExceptionHandler` - `VerificacionSaturadaException` extends
 * `InvalidOperationException`, so without the explicit `@Order` it would come
 * out as `400 INVALID_OPERATION`. That is the mistake feature 001 already made
 * once.
 *
 * One permit and a very short wait, so saturation is reachable in a test rather
 * than a thought experiment.
 */
@Testcontainers
@SpringBootTest(
    classes = [AuthTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(SeguridadPermisivaTestConfig::class)
class SaturacionHashIT : BaseAuthIT() {

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun acotarAlMaximo(registry: DynamicPropertyRegistry) {
            registry.add("auth.hash.concurrencia") { "1" }
            registry.add("auth.hash.espera-ms") { "1" }
            // Real-ish cost, so one verification actually holds the permit long
            // enough for the others to time out. With 1 MiB / t=1 they would all
            // sail through and the test would prove nothing.
            registry.add("auth.password.argon2.memory-kb") { "32768" }
            registry.add("auth.password.argon2.iterations") { "3" }
        }
    }

    @LocalServerPort
    var puerto: Int = 0

    private val password = "Granatum-2026!"

    private data class Respuesta(val estado: Int, val cuerpo: String, val retryAfter: String?)

    private fun login(email: String): Respuesta =
        RestClient.builder().baseUrl("http://localhost:$puerto").build()
            .post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"email":"$email","password":"$password"}""")
            .exchange({ _, response ->
                Respuesta(
                    response.statusCode.value(),
                    response.body.readAllBytes().decodeToString(),
                    response.headers.getFirst("Retry-After")
                )
            }, false)!!

    @Test
    fun `under saturation some requests answer 503 with Retry-After`() {
        val cuenta = cuentaActiva(password = password)

        val pool = Executors.newFixedThreadPool(8)
        val respuestas = pool.invokeAll(
            (1..8).map { Callable { login(cuenta.email) } }
        ).map { it.get() }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        val saturadas = respuestas.filter { it.estado == 503 }
        assertTrue(
            saturadas.isNotEmpty(),
            "with one permit and a 1 ms wait, eight simultaneous sign-ins must not all " +
                "get through: that is the whole point of the bound. Estados: " +
                respuestas.map { it.estado }
        )
        assertEquals("SERVICIO_SATURADO", codigo(saturadas.first().cuerpo))
        assertEquals(
            "1",
            saturadas.first().retryAfter,
            "the condition is transient by definition, so the client is told when to " +
                "come back instead of falling back to asking for the password"
        )
    }

    /**
     * The precedence assertion. `VerificacionSaturadaException` extends
     * `InvalidOperationException`, which `CommonExceptionHandler` also matches:
     * without `@Order(HIGHEST_PRECEDENCE)` on `AuthExceptionHandler` this would
     * be `400 INVALID_OPERATION`, and a client would treat a transient overload
     * as a bad request it should never retry.
     */
    @Test
    fun `saturation is never reported as a bad request`() {
        val cuenta = cuentaActiva(password = password)

        val pool = Executors.newFixedThreadPool(8)
        val estados = pool.invokeAll((1..8).map { Callable { login(cuenta.email).estado } })
            .map { it.get() }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertTrue(
            estados.none { it == 400 },
            "a 400 here means the generic handler won and the explicit @Order was lost: " +
                "$estados"
        )
        assertTrue(estados.all { it == 200 || it == 503 }, "$estados")
    }

    /** Capacity comes back: the semaphore must release its permits. */
    @Test
    fun `after the flood a normal sign in works again`() {
        val cuenta = cuentaActiva(password = password)

        val pool = Executors.newFixedThreadPool(8)
        pool.invokeAll((1..8).map { Callable { login(cuenta.email) } }).map { it.get() }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertEquals(
            200,
            login(cuenta.email).estado,
            "a semaphore that does not give its permits back is a permanent outage, " +
                "worse than the one it prevents"
        )
    }

    private fun codigo(cuerpo: String): String? =
        Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(cuerpo)?.groupValues?.get(1)
}
