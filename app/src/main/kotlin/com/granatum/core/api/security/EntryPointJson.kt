package com.granatum.core.api.security

import com.granatum.core.api.config.JwtAuthFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/**
 * The `401` and `403` bodies, in the project's single error shape.
 *
 * ## Why this exists
 *
 * FR-006 requires an **expired** access token to be rejected distinguishably
 * from an invalid one, so the application knows to renew instead of asking for
 * the password again. Until now `SecurityConfig` answered with
 * `HttpStatusEntryPoint(UNAUTHORIZED)`: a `401` with an **empty body** - no
 * `code`, no message, and nothing to tell the two cases apart. So this closes
 * two things at once, FR-006 and a breach of principle VIII that was already
 * there.
 *
 * ## Why it is not a @RestControllerAdvice
 *
 * The rejection happens in the filter chain, before any controller runs, so no
 * advice ever sees it. Principle VIII forbids building error bodies **in a
 * controller**, which this is not; what it requires is the single shape, and
 * that is what this emits. The JSON is written by hand rather than through an
 * `ObjectMapper` for one deliberate reason: an entry point that could itself
 * fail to serialise would answer a `500` to a request whose only problem was a
 * missing token.
 *
 * ## The 403 as well
 *
 * `AccessDeniedHandler` is here too, so the `403` does not end up the only mute
 * response in the API once the `401` carries a body. The existing tests assert
 * the status rather than the body, so they are unaffected.
 */
@Component
class EntryPointJson : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException
    ) {
        // The mark is left by JwtAuthFilter, the only place that can tell an
        // expired signature from a broken one.
        val caducado = request.getAttribute(JwtAuthFilter.ATRIBUTO_TOKEN_CADUCADO) == true

        if (caducado) {
            escribir(
                response,
                HttpStatus.UNAUTHORIZED,
                "TOKEN_ACCESO_EXPIRADO",
                "La sesion ha expirado, renueve el token de acceso"
            )
        } else {
            escribir(
                response,
                HttpStatus.UNAUTHORIZED,
                "NO_AUTENTICADO",
                "Se requiere un token de acceso valido"
            )
        }
    }

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException
    ) {
        escribir(
            response,
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "No tiene permisos para realizar esta operacion"
        )
    }

    /**
     * No message here may carry an email address, a token or any fragment of
     * one: these bodies reach clients and logs alike (principle VI). They say
     * what kind of authentication failed, never with which value.
     */
    private fun escribir(
        response: HttpServletResponse,
        estado: HttpStatus,
        code: String,
        message: String
    ) {
        response.status = estado.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write("""{"code":"$code","message":"$message"}""")
    }
}
