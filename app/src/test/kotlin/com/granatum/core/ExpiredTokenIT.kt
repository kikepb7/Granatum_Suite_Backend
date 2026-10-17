package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FR-006: an expired access token must be rejected **distinguishably** from an
 * invalid or absent one.
 *
 * ## Why it matters
 *
 * The application has to know whether to renew silently or to ask for the
 * password. Until this feature, `SecurityConfig` answered every rejection with
 * `HttpStatusEntryPoint(UNAUTHORIZED)`: a `401` with an **empty body**, no
 * `code`, and nothing to tell the cases apart - so a mobile client's only
 * option was to send the person back to the login screen every fifteen minutes,
 * which is exactly what the refresh token exists to avoid.
 *
 * That empty body was also a breach of principle VIII, which requires a single
 * `{code, message}` shape for every error. So this closes both.
 *
 * ## Why it lives in `app`
 *
 * The entry point is part of the global filter chain, which only exists here.
 * In `auth` there is no `SecurityConfig` at all, so the same test would pass
 * without ever exercising the rejection it claims to check (D-016).
 *
 * The expired token is minted by a second `JwtService` with a negative lifetime,
 * built with the **same signing key** as the application: a different key would
 * make the token invalid rather than expired, and the test would pass for the
 * wrong reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExpiredTokenIT {

    @LocalServerPort
    var port: Int = 0

    @Value("\${jwt.secret}")
    lateinit var appKey: String

    private data class Response(val status: Int, val body: String)

    private fun get(path: String, auth: String?): Response =
        RestClient.builder().baseUrl("http://localhost:$port").build()
            .get().uri(path)
            .apply { if (auth != null) header("Authorization", auth) }
            .exchange({ _, response ->
                Response(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

    private fun field(body: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    /** A protected route; any one will do, the rejection happens before routing. */
    private val protectedPath = "/api/materials"

    @Test
    fun `an expired access token answers ACCESS_TOKEN_EXPIRED`() {
        val expired = JwtService(
            secretBase64 = appKey,
            expirationMinutes = -1,
            refreshExpirationDays = 30
        ).generateAccessToken(UUID.randomUUID(), Role.ADMIN)

        val response = get(protectedPath, "Bearer $expired")

        assertEquals(401, response.status)
        assertEquals(
            "ACCESS_TOKEN_EXPIRED",
            field(response.body, "code"),
            "without this the client cannot tell 'renew' from 'ask for the password', " +
                "which is the whole of FR-006"
        )
        assertNotNull(field(response.body, "message"))
    }

    @Test
    fun `no Authorization header answers NOT_AUTHENTICATED`() {
        val response = get(protectedPath, null)

        assertEquals(401, response.status)
        assertEquals("NOT_AUTHENTICATED", field(response.body, "code"))
    }

    /**
     * The distinction is the requirement, so it gets its own assertion: it would
     * be easy to satisfy both tests above with one code and lose the point.
     */
    @Test
    fun `the two rejections are distinguishable from each other`() {
        val expired = JwtService(
            secretBase64 = appKey,
            expirationMinutes = -1,
            refreshExpirationDays = 30
        ).generateAccessToken(UUID.randomUUID(), Role.EMPLOYEE)

        val byExpiry = field(get(protectedPath, "Bearer $expired").body, "code")
        val withoutToken = field(get(protectedPath, null).body, "code")

        assertTrue(
            byExpiry != withoutToken,
            "FR-006 is this inequality: $byExpiry vs $withoutToken"
        )
    }

    /**
     * A token signed with another key is **not** expired, it is forged, and it
     * must not be reported as a renewable session - that would tell an attacker
     * their guess was merely stale.
     */
    @Test
    fun `a token signed with another key answers NOT_AUTHENTICATED and not expired`() {
        val foreign = JwtService(
            secretBase64 = randomTestJwtKeyBase64(),
            expirationMinutes = 15,
            refreshExpirationDays = 30
        ).generateAccessToken(UUID.randomUUID(), Role.ADMIN)

        val response = get(protectedPath, "Bearer $foreign")

        assertEquals(401, response.status)
        assertEquals("NOT_AUTHENTICATED", field(response.body, "code"))
    }

    @Test
    fun `garbage in the header answers NOT_AUTHENTICATED`() {
        val response = get(protectedPath, "Bearer no-es-un-jwt")

        assertEquals(401, response.status)
        assertEquals("NOT_AUTHENTICATED", field(response.body, "code"))
    }

    /**
     * Principle VIII applies to the whole API, so the 403 must not be left as
     * the only mute response once the 401 carries a body.
     */
    @Test
    fun `a 403 also carries the stable error shape`() {
        val withoutPermission = JwtService(
            secretBase64 = appKey,
            expirationMinutes = 15,
            refreshExpirationDays = 30
        ).generateAccessToken(UUID.randomUUID(), Role.EMPLOYEE)

        // EMPLOYEE has no inventory access at all (principle IV).
        val response = get(protectedPath, "Bearer $withoutPermission")

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", field(response.body, "code"))
    }
}
