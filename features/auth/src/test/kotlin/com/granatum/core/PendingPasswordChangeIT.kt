package com.granatum.core

import com.granatum.core.api.config.JwtAuthFilter
import com.granatum.core.domain.type.Role
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.AccountService
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pending-password-change state (FR-019, FR-020) at the token level.
 *
 * What the token carries and what authority it earns is testable here; whether
 * a fichaje route actually refuses it is not, because `SecurityConfig` lives in
 * `app`. `PendienteDeCambioBloqueaFichajeIT` there is the other half, and the
 * split is what D-016 requires.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class PendingPasswordChangeIT : BaseAuthIT() {

    @Autowired lateinit var accountService: AccountService
    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var jwtService: JwtService

    private fun newAccess(role: Role = Role.EMPLOYEE): Pair<String, String> {
        val employeeId = UUID.randomUUID().also { directory.registerActive(it) }
        val (account, temporaryPassword) = accountService.grantAccess(employeeId, uniqueEmail(), role)
        return account.email to temporaryPassword
    }

    @Test
    fun `the token of a pending account carries the claim`() {
        val (email, temporaryPassword) = newAccess()

        val pair = authentication.signIn(email, temporaryPassword)

        assertTrue(jwtService.requiresPasswordChange(pair.accessToken))
        assertTrue(pair.passwordChangeRequired, "US4 scenario 2: the response says so too")
    }

    /**
     * The mechanism FR-020 rests on: the filter grants the restricted authority
     * **instead of** the role, so every `hasAnyRole(...)` rule in
     * `SecurityConfig` starts refusing the token on its own, with no change to
     * `inventory` or `timetracking`.
     *
     * Asserted through the filter rather than by reading the claim, because what
     * matters is the authority that reaches the authorisation layer.
     */
    @Test
    fun `the filter grants only PWD_CHANGE_ONLY and not the role`() {
        val (email, temporaryPassword) = newAccess(Role.ADMIN)
        val pair = authentication.signIn(email, temporaryPassword)

        val authorityNames = authoritiesOf(pair.accessToken)

        assertEquals(listOf(JwtAuthFilter.PASSWORD_CHANGE_ONLY_AUTHORITY), authorityNames)
        assertFalse(
            authorityNames.contains("ROLE_ADMIN"),
            "an ADMIN pending a change must be as restricted as anybody else: the state " +
                "of the session wins over the role of the person"
        )
    }

    @Test
    fun `a token with no pending change keeps its role authority`() {
        val (email, temporaryPassword) = newAccess(Role.MANAGER)
        val employeeId = jwtService.getSubjectFromToken(
            authentication.signIn(email, temporaryPassword).accessToken
        )
        val pair = accountService.changeEmployeePassword(
            employeeId, temporaryPassword, "Granatum-2027!"
        )

        assertEquals(listOf("ROLE_MANAGER"), authoritiesOf(pair.accessToken))
    }

    /** Runs the real filter and reports what it put in the security context. */
    private fun authoritiesOf(accessToken: String): List<String> {
        val jwtFilter = JwtAuthFilter(jwtService)
        val request = org.springframework.mock.web.MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $accessToken")
        }
        val response = org.springframework.mock.web.MockHttpServletResponse()
        val chain = org.springframework.mock.web.MockFilterChain()

        org.springframework.security.core.context.SecurityContextHolder.clearContext()
        jwtFilter.doFilter(request, response, chain)
        val auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().authentication
        org.springframework.security.core.context.SecurityContextHolder.clearContext()

        // `GrantedAuthority.getAuthority()` is @Nullable in Spring Security 7.
        return auth?.authorities?.mapNotNull { it.authority } ?: emptyList()
    }
}
