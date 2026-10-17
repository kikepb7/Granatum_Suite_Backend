package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Guards one line of `SecurityConfig`: the catch-all requires a real role, not
 * merely `authenticated()`.
 *
 * ## Why it needs its own test
 *
 * A token pending a password change **is** authenticated. With the old
 * catch-all, every route added in the future without an explicit role rule
 * would have been open to it - the opposite of deny-by-default, and opened by
 * adding a route rather than by touching security, so nobody would look.
 *
 * Reverting that line to `authenticated()` breaks nothing visible: every
 * existing route has its own rule. This test is the only thing that would
 * notice.
 *
 * ## How it proves the cause
 *
 * The same undeclared path is requested with a normal token and with a pending
 * one. The normal token gets past authorisation and finds no handler (404); the
 * pending one is stopped by the catch-all (403). The contrast is what shows the
 * rule, not the route, is responsible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigCatchAllIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { HttpTestClient(port) }

    /** No controller handles this, and no rule in SecurityConfig names it. */
    private val undeclaredPath = "/api/route-that-does-not-exist-${UUID.randomUUID()}"

    @Test
    fun `a pending token is refused on a route nobody declared`() {
        val pending = jwtService.generateAccessToken(
            UUID.randomUUID(), Role.EMPLOYEE, requiresPasswordChange = true
        )

        assertEquals(
            403,
            http.get(undeclaredPath, pending).status,
            "with `anyRequest().authenticated()` this would get through - a pending " +
                "session is authenticated - and every future route without a rule would " +
                "be open to it"
        )
    }

    @Test
    fun `a normal token gets past the catch-all and simply finds nothing`() {
        val normal = jwtService.generateAccessToken(UUID.randomUUID(), Role.EMPLOYEE)

        assertEquals(
            404,
            http.get(undeclaredPath, normal).status,
            "the control: a real role passes authorisation, so the 403 above is the " +
                "rule's doing and not the route's"
        )
    }

    @Test
    fun `no token at all is still a 401`() {
        assertEquals(401, http.get(undeclaredPath).status)
    }
}
