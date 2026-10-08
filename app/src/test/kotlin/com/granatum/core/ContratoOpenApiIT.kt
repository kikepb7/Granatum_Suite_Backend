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
 *     OPENAPI_ACTUALIZAR=true ./gradlew :app:test --tests '*ContratoOpenApiIT'
 *
 * and commit the file with the change that caused it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"])
class ContratoOpenApiIT {

    @LocalServerPort
    var puerto: Int = 0

    private val json = ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    /** Gradle runs the tests from the module directory, so the repository root is one level up. */
    private val copia = File("../docs/openapi.json")

    private fun documento(): JsonNode {
        val cuerpo = RestClient.create("http://localhost:$puerto").get().uri("/v3/api-docs").retrieve().body(String::class.java)!!
        return json.readTree(cuerpo)
    }

    private fun operacion(doc: JsonNode, ruta: String, metodo: String): JsonNode =
        doc.path("paths").path(ruta).path(metodo).also { assertTrue(!it.isMissingNode, "$metodo $ruta missing from the document") }

    @Test
    fun `the document covers every feature's API and nothing outside it`() {
        val doc = documento()
        val rutas = doc.path("paths").fieldNames().asSequence().toList()

        assertTrue(rutas.all { it.startsWith("/api/") }, rutas.filterNot { it.startsWith("/api/") }.toString())
        assertTrue(rutas.none { it.startsWith("/api/dev") }, "the dev token emitter is not part of the contract")
        listOf(
            "/api/materiales", "/api/fichajes", "/api/empleados", "/api/auth/login", "/api/auth/registro",
            "/api/facturacion/facturas", "/api/ausencias", "/api/notificaciones"
        ).forEach { assertTrue(it in rutas, "$it missing: $rutas") }
    }

    @Test
    fun `authentication is a bearer JWT everywhere except the public routes`() {
        val doc = documento()

        assertEquals("bearer", doc.at("/components/securitySchemes/bearerAuth/scheme").asText())
        assertEquals("bearerAuth", doc.at("/security/0").fieldNames().next())
        listOf("/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/registro").forEach {
            val seguridad = operacion(doc, it, "post").path("security")
            assertTrue(seguridad.isArray && seguridad.isEmpty, "$it is public: no token in the contract either")
        }
        assertTrue(operacion(doc, "/api/ausencias", "get").path("security").isMissingNode, "inherits the global bearer requirement")
        assertTrue(operacion(doc, "/api/ausencias", "get").path("responses").has("401"))
    }

    @Test
    fun `errors are documented with the single error format`() {
        val doc = documento()

        assertEquals(setOf("code", "message"), doc.at("/components/schemas/Error/properties").fieldNames().asSequence().toSet())
        assertEquals(
            "#/components/schemas/Error",
            operacion(doc, "/api/auth/login", "post").at("/responses/429/content/application~1json/schema/\$ref").asText()
        )
    }

    @Test
    fun `required fields of request bodies follow the validation annotations`() {
        val doc = documento()
        val requeridos = doc.at("/components/schemas/SolicitudAusenciaRequest/required").map { it.asText() }.toSet()

        assertEquals(setOf("tipo", "desde", "hasta"), requeridos)
    }

    @Test
    fun `the Swagger UI loads, under a CSP that lets it run its own scripts only`() {
        val (estado, csp) = RestClient.create("http://localhost:$puerto").get().uri("/swagger-ui/index.html")
            .exchange({ _, r -> r.statusCode.value() to r.headers.getFirst("Content-Security-Policy") }, false)!!

        assertEquals(200, estado)
        assertTrue(csp!!.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'"), csp)
    }

    @Test
    fun `the committed docs-openapi-json matches the API`() {
        val vivo = documento()

        if (System.getenv("OPENAPI_ACTUALIZAR") == "true") {
            copia.parentFile.mkdirs()
            copia.writeText(json.writeValueAsString(json.treeToValue(vivo, Any::class.java)) + "\n")
            return
        }
        if (!copia.exists()) fail("docs/openapi.json does not exist. Generate it: OPENAPI_ACTUALIZAR=true ./gradlew :app:test --tests '*ContratoOpenApiIT'")
        if (json.readTree(copia) != vivo) {
            fail(
                "docs/openapi.json no coincide con la API: un controlador ha cambiado y el contrato que usan las apps no. " +
                    "Regenéralo con OPENAPI_ACTUALIZAR=true ./gradlew :app:test --tests '*ContratoOpenApiIT' y súbelo con el cambio."
            )
        }
    }
}
