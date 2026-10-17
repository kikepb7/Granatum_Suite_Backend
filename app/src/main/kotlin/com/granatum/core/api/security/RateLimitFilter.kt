package com.granatum.core.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Applies the per-origin quotas before anything else in the security chain
 * (feature 006): a refused request costs a map lookup, never a JWT check, an
 * Argon2 hash or a database write (FR-003).
 *
 * ## The address
 *
 * `remoteAddr`, never a header read by hand. Behind a proxy,
 * `server.forward-headers-strategy=native` makes Tomcat replace it with the
 * client from `X-Forwarded-For` - but only for requests that come from a trusted
 * proxy. Reading the header here would let anyone pick a new address per
 * request and walk past the limit (research.md D-002).
 *
 * ## Why a filter writes the 429 (declared deviation from principle VIII)
 *
 * It runs before the `DispatcherServlet`, where no `@RestControllerAdvice`
 * exists - the same reason `JsonEntryPoint` writes the 401/403. The body keeps
 * the single `{code, message}` format.
 *
 * It logs nothing: the address is personal data (FR-006).
 *
 * Not a Spring bean on purpose: a `Filter` bean is also registered by Boot in
 * the servlet container, outside the security chain. `SecurityConfig` creates
 * it and places it.
 */
class RateLimitFilter(private val limiter: OriginRateLimiter) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (!path.startsWith("/api/") && path != "/api") {
            chain.doFilter(request, response)
            return
        }

        val address = request.remoteAddr
        val wait = specificQuota(request.method, path)?.let { limiter.consume(it, address) }
            ?: limiter.consume(Quota.GENERAL, address)

        if (wait != null) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader(HttpHeaders.RETRY_AFTER, wait.toString())
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write(BODY)
            return
        }
        chain.doFilter(request, response)
    }

    private fun specificQuota(method: String, path: String): Quota? {
        if (method != "POST") return null
        return when (path.trimEnd('/')) {
            "/api/auth/login" -> Quota.LOGIN
            "/api/auth/refresh", "/api/auth/logout" -> Quota.SESSION
            "/api/auth/sign-up" -> Quota.SIGN_UP
            else -> null
        }
    }

    companion object {
        const val CODE = "TOO_MANY_REQUESTS"
        private const val BODY =
            """{"code":"$CODE","message":"Demasiadas peticiones. Espera unos segundos y vuelve a intentarlo."}"""
    }
}
