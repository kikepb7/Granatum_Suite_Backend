package com.granatum.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.client.RestClient
import java.util.UUID

/**
 * A permit-all filter chain, for this module's HTTP tests only - the same
 * arrangement, and for the same reason, as inventory's
 * `InventoryPermissiveSecurityTestConfig`. The real chain is `SecurityConfig`,
 * in `app`, which keeps every `/api/sheets` route to `ADMIN`; these tests cover the
 * contract, not authorisation. A real token is still sent, so the controller
 * knows who the ADMIN is.
 */
@TestConfiguration
class SheetsPermissiveSecurityTestConfig {
    @Bean
    fun permissiveChain(httpSecurity: HttpSecurity): SecurityFilterChain =
        httpSecurity
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
}

/** Real JSON over a real socket, as an ADMIN. */
abstract class SheetsHttp : SheetsDatabase() {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwt: JwtService

    protected val admin: UUID = UUID.randomUUID()
    protected val json = ObjectMapper()

    data class Response(val status: Int, val raw: String) {
        val body: JsonNode get() = ObjectMapper().readTree(raw.ifEmpty { "{}" })
    }

    protected fun send(method: HttpMethod, path: String, body: String? = null): Response =
        RestClient.builder().baseUrl("http://localhost:$port").build()
            .method(method).uri(path)
            .header("Authorization", "Bearer ${jwt.generateAccessToken(admin, Role.ADMIN)}")
            .apply { if (body != null) contentType(MediaType.APPLICATION_JSON).body(body) }
            .exchange({ _, r -> Response(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!
}
