package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/api/employees` over a real socket (feature 012, US1): the last name is
 * required on every engagement and edit, and the people registered before it
 * existed keep their name and come back marked as pending.
 *
 * Over HTTP and not through the service because what is under test is the
 * request contract - `@Valid`, the error shape, the JSON fields - which a
 * service call never exercises. Authorisation is `app`'s; see
 * [TimetrackingPermissiveSecurityTestConfig].
 */
@Testcontainers
@SpringBootTest(
    classes = [TimetrackingTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(TimetrackingPermissiveSecurityTestConfig::class)
class EmployeeHttpIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("timetracking.incomplete.cron") { "0 0 4 1 1 *" }
            registry.add("timetracking.retention.cron") { "0 0 4 1 1 *" }
        }
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jdbc: JdbcTemplate

    private data class Reply(val status: Int, val body: String)

    private fun send(method: HttpMethod, path: String, json: String? = null): Reply =
        RestClient.builder().baseUrl("http://localhost:$port").build()
            .method(method).uri(path)
            .apply { if (json != null) contentType(MediaType.APPLICATION_JSON).body(json) }
            .exchange({ _, response ->
                Reply(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

    /** Deliberately a regex and not a JSON parse: it reads the raw wire bytes. */
    private fun field(body: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*(\"[^\"]*\"|[^,}]*)").find(body)?.groupValues?.get(1)?.trim()

    private fun validDni(): String {
        val number = (10_000_000..99_999_999).random()
        return "$number${"TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]}"
    }

    private fun createJson(lastName: String?, dni: String = validDni()): String {
        val last = lastName?.let { ""","lastName":"$it"""" }.orEmpty()
        return """{"name":"Ana"$last,"identityDocument":"$dni","position":"Florista",""" +
            """"contractType":"FULL_TIME","hireDate":"2026-01-15"}"""
    }

    private fun updateJson(lastName: String?): String {
        val last = lastName?.let { ""","lastName":"$it"""" }.orEmpty()
        return """{"name":"Ana"$last,"position":"Florista","contractType":"FULL_TIME","hireDate":"2026-01-15"}"""
    }

    private fun createdId(lastName: String = "García López"): String {
        val reply = send(HttpMethod.POST, "/api/employees", createJson(lastName))
        assertEquals(201, reply.status, "body: ${reply.body}")
        return field(reply.body, "id")!!.trim('"')
    }

    @Test
    fun `an engagement with a last name returns it and is not pending`() {
        val reply = send(HttpMethod.POST, "/api/employees", createJson("García López"))

        assertEquals(201, reply.status, "body: ${reply.body}")
        assertEquals("\"Ana\"", field(reply.body, "name"))
        assertEquals("\"García López\"", field(reply.body, "lastName"))
        assertEquals("false", field(reply.body, "lastNamePending"))
    }

    @Test
    fun `an engagement without a last name is rejected naming the field`() {
        val reply = send(HttpMethod.POST, "/api/employees", createJson(lastName = null))

        assertEquals(400, reply.status, "body: ${reply.body}")
        assertEquals("\"VALIDATION\"", field(reply.body, "code"))
        assertTrue(reply.body.contains("lastName"), "the message names the field: ${reply.body}")
    }

    @Test
    fun `a blank last name is rejected`() {
        val reply = send(HttpMethod.POST, "/api/employees", createJson("   "))

        assertEquals(400, reply.status, "body: ${reply.body}")
        assertEquals("\"VALIDATION\"", field(reply.body, "code"))
    }

    /** FR-002: the same limit as the name, 150. */
    @Test
    fun `a last name of 151 characters is rejected, one of 150 is accepted`() {
        assertEquals(400, send(HttpMethod.POST, "/api/employees", createJson("a".repeat(151))).status)
        assertEquals(201, send(HttpMethod.POST, "/api/employees", createJson("a".repeat(150))).status)
    }

    @Test
    fun `an edit without a last name is rejected`() {
        val id = createdId()

        val reply = send(HttpMethod.PUT, "/api/employees/$id", updateJson(lastName = null))

        assertEquals(400, reply.status, "body: ${reply.body}")
        assertEquals("\"VALIDATION\"", field(reply.body, "code"))
        assertTrue(reply.body.contains("lastName"), "the message names the field: ${reply.body}")
    }

    @Test
    fun `an edit with a last name of 151 characters is rejected`() {
        val id = createdId()

        assertEquals(400, send(HttpMethod.PUT, "/api/employees/$id", updateJson("a".repeat(151))).status)
    }

    @Test
    fun `an edit changes the last name`() {
        val id = createdId()

        val reply = send(HttpMethod.PUT, "/api/employees/$id", updateJson("Pérez Martín"))

        assertEquals(200, reply.status, "body: ${reply.body}")
        assertEquals("\"Pérez Martín\"", field(reply.body, "lastName"))
    }

    /**
     * FR-003: a person registered before the feature keeps their name as it was
     * - nobody tries to split it - and comes back with no last name, marked as
     * pending until an ADMIN completes it.
     */
    @Test
    fun `a person registered before the feature is pending until an edit completes it`() {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO employees (id, name, identity_document, position, contract_type, hire_date, active, created_at, updated_at)
            VALUES (?, 'María García López', ?, 'Florista', 'FULL_TIME', DATE '2025-03-01', TRUE, now(), now())
            """.trimIndent(),
            id, validDni()
        )

        val before = send(HttpMethod.GET, "/api/employees/$id")
        assertEquals(200, before.status, "body: ${before.body}")
        assertEquals("\"María García López\"", field(before.body, "name"), "the name is not split")
        assertEquals("null", field(before.body, "lastName"))
        assertEquals("true", field(before.body, "lastNamePending"))

        val after = send(HttpMethod.PUT, "/api/employees/$id", updateJson("García López"))
        assertEquals(200, after.status, "body: ${after.body}")
        assertEquals("\"García López\"", field(after.body, "lastName"))
        assertEquals("false", field(after.body, "lastNamePending"))
    }
}
