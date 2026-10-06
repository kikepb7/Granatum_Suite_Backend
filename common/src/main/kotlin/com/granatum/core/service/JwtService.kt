package com.granatum.core.service

import com.granatum.core.domain.exception.InvalidTokenException
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoToken
import com.granatum.core.domain.type.Role
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
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
    @param:Value("\${jwt.expiration-minutes}") private val expirationMinutes: Long,
    // Was a hard-coded 30-day constant. It becomes configuration because the
    // `auth` feature makes the refresh token opaque and database-backed: the
    // lifetime that counts is the one on the `sesiones_renovacion` row, and the
    // two must be able to move together without a recompile (D-017).
    @param:Value("\${auth.refresh-expiration-days:30}") private val refreshExpirationDays: Long
) {

    private val secretKey = Keys.hmacShaKeyFor(Base64.decode(source = secretBase64))

    private val accessTokenValidityMs = expirationMinutes * 60 * 1000
    val refreshTokenValidityMs: Long = refreshExpirationDays * 24 * 60 * 60 * 1000

    private fun generateToken(
        subject: EntityId,
        role: Role,
        type: String,
        expiry: Long,
        requiereCambioPassword: Boolean = false
    ): String {
        val now = Date()
        val expiryDate = Date(now.time + expiry)
        return Jwts.builder()
            .subject(subject.toString())
            .claim("type", type)
            .claim("role", role.name)
            .apply {
                // Added only when true, so a token minted the old way is
                // byte-for-byte what it was. That is what keeps `inventory`,
                // `timetracking` and `/api/dev/token` from changing behaviour.
                if (requiereCambioPassword) claim(CLAIM_CAMBIO_PASSWORD, true)
            }
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    private fun sinPrefijo(token: String): String =
        if (token.startsWith("Bearer ")) token.removePrefix("Bearer ") else token

    private fun parseAllClaims(token: String): Claims? {
        return try {
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(sinPrefijo(token))
                .payload
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Why a token is not usable, not merely whether it is.
     *
     * [parseAllClaims] above returns `null` for every kind of failure, which is
     * the right shape for "give me the claims or nothing" but destroys the one
     * distinction FR-006 needs: an expired token means *renew*, an invalid one
     * means *sign in again*. `ExpiredJwtException` is the only case jjwt reports
     * separately, so it is caught separately here.
     */
    fun estadoToken(token: String): EstadoToken =
        try {
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(sinPrefijo(token))
            EstadoToken.VALIDO
        } catch (e: ExpiredJwtException) {
            EstadoToken.CADUCADO
        } catch (e: Exception) {
            EstadoToken.INVALIDO
        }

    fun generateAccessToken(
        subject: EntityId,
        role: Role,
        requiereCambioPassword: Boolean = false
    ): String =
        generateToken(
            subject = subject,
            role = role,
            type = "access",
            expiry = accessTokenValidityMs,
            requiereCambioPassword = requiereCambioPassword
        )

    /**
     * Whether this token belongs to an account that still owes a password
     * change (FR-019, FR-020). Absent claim means `false`, which is why every
     * token minted before this feature keeps working unchanged.
     *
     * Returns `false` for an unreadable token rather than throwing: the caller
     * is [com.granatum.core.api.config.JwtAuthFilter], which has already decided
     * not to authenticate it, and an exception there would turn a routine
     * rejection into a 500.
     */
    fun requiereCambioPassword(token: String): Boolean =
        parseAllClaims(token)?.get(CLAIM_CAMBIO_PASSWORD) == true

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

    companion object {
        const val CLAIM_CAMBIO_PASSWORD = "pwd_change"
    }
}
