package com.granatum.core.service

import com.granatum.core.domain.exception.InvalidTokenException
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import java.util.UUID
import kotlin.io.encoding.Base64

/**
 * Stateless JWT issuing/validation shared by every module. `jwt.secret` must be
 * a base64-encoded 256-bit (or larger) key, e.g. `openssl rand -base64 32`.
 */
@Service
class JwtService(
    @param:Value("\${jwt.secret}") private val secretBase64: String,
    @param:Value("\${jwt.expiration-minutes}") private val expirationMinutes: Long
) {

    private val secretKey = Keys.hmacShaKeyFor(Base64.decode(source = secretBase64))

    private val accessTokenValidityMs = expirationMinutes * 60 * 1000
    val refreshTokenValidityMs: Long = 30L * 24 * 60 * 60 * 1000

    private fun generateToken(subject: EntityId, role: Role, type: String, expiry: Long): String {
        val now = Date()
        val expiryDate = Date(now.time + expiry)
        return Jwts.builder()
            .subject(subject.toString())
            .claim("type", type)
            .claim("role", role.name)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    private fun parseAllClaims(token: String): Claims? {
        val rawToken = if (token.startsWith("Bearer ")) token.removePrefix("Bearer ") else token

        return try {
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(rawToken)
                .payload
        } catch (e: Exception) {
            null
        }
    }

    fun generateAccessToken(subject: EntityId, role: Role): String =
        generateToken(subject = subject, role = role, type = "access", expiry = accessTokenValidityMs)

    fun generateRefreshToken(subject: EntityId, role: Role): String =
        generateToken(subject = subject, role = role, type = "refresh", expiry = refreshTokenValidityMs)

    fun validateAccessToken(token: String): Boolean {
        val claims = parseAllClaims(token) ?: return false
        return (claims["type"] as? String) == "access"
    }

    fun validateRefreshToken(token: String): Boolean {
        val claims = parseAllClaims(token) ?: return false
        return (claims["type"] as? String) == "refresh"
    }

    fun getSubjectFromToken(token: String): EntityId {
        val claims = parseAllClaims(token)
            ?: throw InvalidTokenException("The attached JWT token is not valid")
        return UUID.fromString(claims.subject)
    }

    fun getRoleFromToken(token: String): Role {
        val claims = parseAllClaims(token)
            ?: throw InvalidTokenException("The attached JWT token is not valid")
        val role = claims["role"] as? String
            ?: throw InvalidTokenException("The attached JWT token has no role claim")
        return Role.valueOf(role)
    }
}
