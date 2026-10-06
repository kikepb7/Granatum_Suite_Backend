package com.granatum.core.service

import com.granatum.core.domain.type.EstadoToken
import com.granatum.core.domain.type.Role
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JwtServiceTest {

    private val jwtService = JwtService(
        secretBase64 = randomTestJwtKeyBase64(),
        expirationMinutes = 15,
        refreshExpirationDays = 30
    )

    @Test
    fun `access token round trip keeps subject and role`() {
        val subject = UUID.randomUUID()
        val token = jwtService.generateAccessToken(subject, Role.ENCARGADO)

        assertTrue(jwtService.validateAccessToken(token))
        assertEquals(subject, jwtService.getSubjectFromToken(token))
        assertEquals(Role.ENCARGADO, jwtService.getRoleFromToken(token))
    }

    @Test
    fun `refresh token is not a valid access token`() {
        val subject = UUID.randomUUID()
        val token = jwtService.generateRefreshToken(subject, Role.ADMIN)

        assertTrue(jwtService.validateRefreshToken(token))
        assertEquals(false, jwtService.validateAccessToken(token))
    }

    // --- pwd_change (FR-019, FR-020) ----------------------------------------

    /**
     * The compatibility guarantee. Every token in the system before this
     * feature was minted without the claim, and `inventory`, `timetracking` and
     * `/api/dev/token` still mint them that way.
     */
    @Test
    fun `a token minted without the claim does not require a password change`() {
        val token = jwtService.generateAccessToken(UUID.randomUUID(), Role.EMPLEADO)

        assertFalse(jwtService.requiereCambioPassword(token))
        assertTrue(jwtService.validateAccessToken(token))
    }

    @Test
    fun `a token minted with the claim exposes it`() {
        val token = jwtService.generateAccessToken(
            UUID.randomUUID(), Role.EMPLEADO, requiereCambioPassword = true
        )

        assertTrue(jwtService.requiereCambioPassword(token))
        assertTrue(
            jwtService.validateAccessToken(token),
            "it is still a usable access token - what changes is the authority the " +
                "filter grants for it, not whether it parses"
        )
        assertEquals(Role.EMPLEADO, jwtService.getRoleFromToken(token))
    }

    /**
     * Called from the filter on a request that has already been refused, so an
     * exception here would turn a routine rejection into a 500.
     */
    @Test
    fun `requiereCambioPassword on an unreadable token answers false instead of throwing`() {
        assertFalse(jwtService.requiereCambioPassword("Bearer no-es-un-jwt"))
        assertFalse(jwtService.requiereCambioPassword(""))
    }

    // --- estadoToken (FR-006) -----------------------------------------------

    @Test
    fun `a fresh token reports VALIDO`() {
        val token = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        assertEquals(EstadoToken.VALIDO, jwtService.estadoToken(token))
    }

    /**
     * FR-006 lives here: an expired token means *renew*, a forged one means
     * *sign in again*, and the application cannot tell them apart unless this
     * method does. Minted with a negative lifetime so it is already expired,
     * which is the only way to test it without waiting.
     */
    @Test
    fun `an expired token reports CADUCADO and not INVALIDO`() {
        val yaCaducado = JwtService(
            secretBase64 = randomTestJwtKeyBase64(),
            expirationMinutes = -1,
            refreshExpirationDays = 30
        )
        val token = yaCaducado.generateAccessToken(UUID.randomUUID(), Role.EMPLEADO)

        assertEquals(
            EstadoToken.CADUCADO,
            yaCaducado.estadoToken(token),
            "collapsing this into INVALIDO is what left the mobile app unable to know " +
                "whether to renew or to ask for the password again"
        )
        assertFalse(yaCaducado.validateAccessToken(token), "expired is still not usable")
    }

    @Test
    fun `a token signed with another key reports INVALIDO`() {
        val otro = JwtService(
            secretBase64 = randomTestJwtKeyBase64(),
            expirationMinutes = 15,
            refreshExpirationDays = 30
        )
        val ajeno = otro.generateAccessToken(UUID.randomUUID(), Role.ADMIN)

        assertEquals(EstadoToken.INVALIDO, jwtService.estadoToken(ajeno))
    }

    @Test
    fun `garbage reports INVALIDO`() {
        assertEquals(EstadoToken.INVALIDO, jwtService.estadoToken("Bearer aaa.bbb.ccc"))
        assertEquals(EstadoToken.INVALIDO, jwtService.estadoToken(""))
    }

    /** D-017: the refresh lifetime is configuration now, not a constant. */
    @Test
    fun `the refresh lifetime comes from configuration`() {
        val corto = JwtService(
            secretBase64 = randomTestJwtKeyBase64(),
            expirationMinutes = 15,
            refreshExpirationDays = 7
        )
        assertEquals(7L * 24 * 60 * 60 * 1000, corto.refreshTokenValidityMs)
    }
}
