package com.granatum.core.service

import com.granatum.core.domain.type.Role
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JwtServiceTest {

    private val jwtService = JwtService(
        secretBase64 = "VGhpc0lzQURldk9ubHlEZWZhdWx0U2VjcmV0S2V5MTIzNA==",
        expirationMinutes = 15
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
}
