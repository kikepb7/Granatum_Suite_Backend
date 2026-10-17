package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-018 and FR-024: only an `ADMIN` creates or resets credentials (US5
 * scenario 4).
 *
 * Lives in `app` because it is authorisation, and `SecurityConfig` exists only
 * here. In `auth` the module's own tests run under a permit-all chain, so the
 * same assertions there would pass without exercising any rule (D-016).
 *
 * Only the authorisation layer is under test, so the ADMIN cases accept any
 * answer that is not 401/403 - what the service then says about a random
 * employee id is `auth`'s business, tested there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountsAuthorizationIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private fun post(path: String, role: Role?, json: String = "{}"): Int =
        RestClient.builder().baseUrl("http://localhost:$port").build()
            .post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .apply {
                if (role != null) {
                    header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), role)}")
                }
            }
            .body(json)
            .exchange({ _, response -> response.statusCode.value() }, false)!!

    private val grant = "/api/auth/accounts"
    private fun reset() = "/api/auth/accounts/${UUID.randomUUID()}/reset"
    private val grantBody = """{"employeeId":"${UUID.randomUUID()}","email":"x@granatum.es"}"""

    @Test
    fun `nobody but ADMIN may grant access`() {
        listOf(Role.MANAGER, Role.EMPLOYEE, Role.REPRESENTATIVE).forEach { role ->
            assertEquals(403, post(grant, role, grantBody), "$role must not grant access")
        }
    }

    /** US5 scenario 4. */
    @Test
    fun `nobody but ADMIN may reset a password`() {
        listOf(Role.MANAGER, Role.EMPLOYEE, Role.REPRESENTATIVE).forEach { role ->
            assertEquals(
                403,
                post(reset(), role),
                "$role resetting someone else's password would be a takeover of their account"
            )
        }
    }

    @Test
    fun `without a token the answer is 401 and not 403`() {
        assertEquals(401, post(grant, null, grantBody))
        assertEquals(401, post(reset(), null))
    }

    @Test
    fun `ADMIN gets past the authorisation layer`() {
        val grantStatus = post(grant, Role.ADMIN, grantBody)
        val resetStatus = post(reset(), Role.ADMIN)

        assertTrue(grantStatus !in setOf(401, 403), "grant: $grantStatus")
        assertTrue(resetStatus !in setOf(401, 403), "reset: $resetStatus")
    }
}
