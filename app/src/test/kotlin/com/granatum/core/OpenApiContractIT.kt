package com.granatum.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClient
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The OpenAPI document (springdoc) and its committed copy, `docs/openapi.json`,
 * which is what the mobile and web apps generate their clients from.
 *
 * ## Keeping the copy in step
 *
 * The test compares the live document with the committed file and fails if
 * they differ - a controller changed and the contract the apps build against
 * did not. To regenerate it after an intended change:
 *
 *     OPENAPI_UPDATE=true ./gradlew :app:test --tests '*OpenApiContractIT'
 *
 * and commit the file with the change that caused it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"])
class OpenApiContractIT {

    @LocalServerPort
    var port: Int = 0

    private val json = ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    /** Gradle runs the tests from the module directory, so the repository root is one level up. */
    private val committedCopy = File("../docs/openapi.json")

    private fun document(): JsonNode {
        val body = RestClient.create("http://localhost:$port").get().uri("/v3/api-docs").retrieve().body(String::class.java)!!
        return json.readTree(body)
    }

    private fun operation(doc: JsonNode, path: String, method: String): JsonNode =
        doc.path("paths").path(path).path(method).also { assertTrue(!it.isMissingNode, "$method $path missing from the document") }

    @Test
    fun `the document covers every feature's API and nothing outside it`() {
        val doc = document()
        val paths = doc.path("paths").fieldNames().asSequence().toList()

        assertTrue(paths.all { it.startsWith("/api/") }, paths.filterNot { it.startsWith("/api/") }.toString())
        assertTrue(paths.none { it.startsWith("/api/dev") }, "the dev token emitter is not part of the contract")
        listOf(
            "/api/materials", "/api/shifts", "/api/employees", "/api/auth/login", "/api/auth/sign-up",
            "/api/invoicing/invoices", "/api/absences", "/api/notifications"
        ).forEach { assertTrue(it in paths, "$it missing: $paths") }
    }

    @Test
    fun `authentication is a bearer JWT everywhere except the public routes`() {
        val doc = document()

        assertEquals("bearer", doc.at("/components/securitySchemes/bearerAuth/scheme").asText())
        assertEquals("bearerAuth", doc.at("/security/0").fieldNames().next())
        listOf("/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/sign-up").forEach {
            val security = operation(doc, it, "post").path("security")
            assertTrue(security.isArray && security.isEmpty, "$it is public: no token in the contract either")
        }
        assertTrue(operation(doc, "/api/absences", "get").path("security").isMissingNode, "inherits the global bearer requirement")
        assertTrue(operation(doc, "/api/absences", "get").path("responses").has("401"))
    }

    @Test
    fun `errors are documented with the single error format`() {
        val doc = document()

        assertEquals(setOf("code", "message"), doc.at("/components/schemas/Error/properties").fieldNames().asSequence().toSet())
        assertEquals(
            "#/components/schemas/Error",
            operation(doc, "/api/auth/login", "post").at("/responses/429/content/application~1json/schema/\$ref").asText()
        )
    }

    @Test
    fun `required fields of request bodies follow the validation annotations`() {
        val doc = document()
        val required = doc.at("/components/schemas/NewAbsenceRequest/required").map { it.asText() }.toSet()

        assertEquals(setOf("type", "from", "to"), required)
    }

    @Test
    fun `the Swagger UI loads, under a CSP that lets it run its own scripts only`() {
        val (status, csp) = RestClient.create("http://localhost:$port").get().uri("/swagger-ui/index.html")
            .exchange({ _, r -> r.statusCode.value() to r.headers.getFirst("Content-Security-Policy") }, false)!!

        assertEquals(200, status)
        assertTrue(csp!!.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'"), csp)
    }

    @Test
    fun `the committed docs-openapi-json matches the API`() {
        val live = document()

        if (System.getenv("OPENAPI_UPDATE") == "true") {
            committedCopy.parentFile.mkdirs()
            committedCopy.writeText(json.writeValueAsString(json.treeToValue(live, Any::class.java)) + "\n")
            return
        }
        if (!committedCopy.exists()) fail("docs/openapi.json does not exist. Generate it: OPENAPI_UPDATE=true ./gradlew :app:test --tests '*OpenApiContractIT'")
        if (json.readTree(committedCopy) != live) {
            fail(
                "docs/openapi.json no coincide con la API: un controlador ha cambiado y el contrato que usan las apps no. " +
                    "Regenéralo con OPENAPI_UPDATE=true ./gradlew :app:test --tests '*OpenApiContractIT' y súbelo con el cambio."
            )
        }
    }
}
