package com.granatum.core

import com.granatum.core.api.config.JwtAuthFilter
import com.granatum.core.domain.type.Role
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.CuentaAccesoService
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
class PendienteDeCambioIT : BaseAuthIT() {

    @Autowired lateinit var cuentaAccesoService: CuentaAccesoService
    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var jwtService: JwtService

    private fun accesoNuevo(rol: Role = Role.EMPLEADO): Pair<String, String> {
        val empleadoId = UUID.randomUUID().also { directorio.registrarActivo(it) }
        val (cuenta, temporal) = cuentaAccesoService.darAcceso(empleadoId, correoUnico(), rol)
        return cuenta.email to temporal
    }

    @Test
    fun `the token of a pending account carries the claim`() {
        val (email, temporal) = accesoNuevo()

        val par = autenticacion.iniciarSesion(email, temporal)

        assertTrue(jwtService.requiereCambioPassword(par.accessToken))
        assertTrue(par.requiereCambioPassword, "US4 scenario 2: the response says so too")
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
        val (email, temporal) = accesoNuevo(Role.ADMIN)
        val par = autenticacion.iniciarSesion(email, temporal)

        val autoridades = autoridadesDe(par.accessToken)

        assertEquals(listOf(JwtAuthFilter.AUTORIDAD_SOLO_CAMBIO_PASSWORD), autoridades)
        assertFalse(
            autoridades.contains("ROLE_ADMIN"),
            "an ADMIN pending a change must be as restricted as anybody else: the state " +
                "of the session wins over the role of the person"
        )
    }

    @Test
    fun `a token with no pending change keeps its role authority`() {
        val (email, temporal) = accesoNuevo(Role.ENCARGADO)
        val empleadoId = jwtService.getSubjectFromToken(
            autenticacion.iniciarSesion(email, temporal).accessToken
        )
        val par = cuentaAccesoService.cambiarPasswordDeEmpleado(
            empleadoId, temporal, "Granatum-2027!"
        )

        assertEquals(listOf("ROLE_ENCARGADO"), autoridadesDe(par.accessToken))
    }

    /** Runs the real filter and reports what it put in the security context. */
    private fun autoridadesDe(accessToken: String): List<String> {
        val filtro = JwtAuthFilter(jwtService)
        val request = org.springframework.mock.web.MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $accessToken")
        }
        val response = org.springframework.mock.web.MockHttpServletResponse()
        val chain = org.springframework.mock.web.MockFilterChain()

        org.springframework.security.core.context.SecurityContextHolder.clearContext()
        filtro.doFilter(request, response, chain)
        val auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().authentication
        org.springframework.security.core.context.SecurityContextHolder.clearContext()

        // `GrantedAuthority.getAuthority()` is @Nullable in Spring Security 7.
        return auth?.authorities?.mapNotNull { it.authority } ?: emptyList()
    }
}
