package com.granatum.core

import com.granatum.core.domain.type.Role
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The HTTP boundary of `/api/auth`, exercised with real JSON over a real socket.
 *
 * ## Why this exists
 *
 * This is the lesson of `ContratoHttpIT` in feature 001. Every other
 * integration test calls a service **method** directly, with Kotlin objects, so
 * none of them deserialises a request body - and that gap hid a complete outage:
 * Spring Boot 4's web layer uses Jackson 3 (`tools.jackson`) while the project
 * only carried the Kotlin module for Jackson 2, so **every POST answered 500**
 * with the entire suite green.
 *
 * A real port rather than MockMvc, and not only because Boot 4 removed
 * `@AutoConfigureMockMvc` and `TestRestTemplate`: MockMvc never opens a socket,
 * and what is under test here is precisely the wire - the converters, the
 * filters and the JSON.
 *
 * ## What it does not cover
 *
 * Authorisation. `SecurityConfig` lives in `app`, so this context has no chain
 * of its own and Boot's default security would otherwise answer `401` to
 * everything before a controller ran - which is what it did until
 * [AuthPermissiveSecurityTestConfig] was added. That configuration makes these tests
 * about the contract and leaves authorisation to `app`, the only module that has
 * both the real chain and the other features. It is the split D-016 calls for.
 */
@Testcontainers
@SpringBootTest(
    classes = [AuthTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(AuthPermissiveSecurityTestConfig::class)
class AuthHttpContractIT : BaseAuthIT() {

    @LocalServerPort
    var port: Int = 0

    private val password = "Granatum-2026!"

    private data class Reply(val status: Int, val body: String)

    private val client: RestClient
        get() = RestClient.builder().baseUrl("http://localhost:$port").build()

    private fun post(path: String, json: String, auth: String? = null): Reply =
        client.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (auth != null) header("Authorization", auth) }
            .body(json)
            .exchange({ _, response ->
                Reply(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

    /** Deliberately a regex and not a JSON parse: it reads the raw wire bytes. */
    private fun field(body: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"?([^\",}]*)\"?").find(body)?.groupValues?.get(1)

    @Test
    fun `login over HTTP returns the token pair as JSON`() {
        val account = activeAccount(password = password, role = Role.EMPLOYEE)

        val reply = post(
            "/api/auth/login",
            """{"email":"${account.email}","password":"$password"}"""
        )

        assertEquals(200, reply.status, "body: ${reply.body}")
        assertNotNull(field(reply.body, "accessToken"))
        assertNotNull(field(reply.body, "refreshToken"))
        assertEquals("900", field(reply.body, "expiresIn"))
        assertEquals("false", field(reply.body, "passwordChangeRequired"))
    }

    /**
     * The body has to deserialise through the real converter. This is the
     * assertion that would have caught the Jackson 3 outage: a data class whose
     * primary constructor Jackson cannot use answers 500 here while every
     * service-level test stays green.
     */
    @Test
    fun `a wrong password answers 401 with the stable error shape`() {
        val account = activeAccount(password = password)

        val reply = post(
            "/api/auth/login",
            """{"email":"${account.email}","password":"Otra-Clave-2026!"}"""
        )

        assertEquals(401, reply.status)
        assertEquals("INVALID_CREDENTIALS", field(reply.body, "code"))
        assertNotNull(field(reply.body, "message"))
    }

    /**
     * FR-003 at the wire level, which is where it actually matters: the two
     * responses must be byte-identical, because that is what an attacker sees.
     */
    @Test
    fun `an unknown email answers byte for byte the same as a wrong password`() {
        val account = activeAccount(password = password)

        val wrongPassword = post(
            "/api/auth/login",
            """{"email":"${account.email}","password":"incorrecta"}"""
        )
        val withoutAccount = post(
            "/api/auth/login",
            """{"email":"${uniqueEmail()}","password":"incorrecta"}"""
        )

        assertEquals(wrongPassword.status, withoutAccount.status)
        assertEquals(
            wrongPassword.body,
            withoutAccount.body,
            "identical down to the bytes: anything that differs is an oracle for which " +
                "addresses are registered"
        )
    }

    @Test
    fun `a malformed email is rejected at the edge`() {
        val reply = post("/api/auth/login", """{"email":"no-es-un-correo","password":"x"}""")

        assertEquals(400, reply.status, "@Valid rejects it before the service runs")
    }

    @Test
    fun `a missing field is rejected at the edge`() {
        val reply = post("/api/auth/login", """{"email":"ana@granatum.es"}""")

        assertEquals(400, reply.status)
    }

    // --- refresh y logout ---------------------------------------------------

    @Test
    fun `refresh over HTTP returns a new pair as JSON`() {
        val account = activeAccount(password = password)
        val login = post("/api/auth/login", """{"email":"${account.email}","password":"$password"}""")
        val refresh = field(login.body, "refreshToken")!!

        val reply = post("/api/auth/refresh", """{"refreshToken":"$refresh"}""")

        assertEquals(200, reply.status, "body: ${reply.body}")
        assertNotNull(field(reply.body, "accessToken"))
        assertTrue(
            field(reply.body, "refreshToken") != refresh,
            "the token must rotate on the wire too, not only in the service"
        )
    }

    @Test
    fun `a reused refresh token answers 401 with its own code`() {
        val account = activeAccount(password = password)
        val login = post("/api/auth/login", """{"email":"${account.email}","password":"$password"}""")
        val refresh = field(login.body, "refreshToken")!!
        post("/api/auth/refresh", """{"refreshToken":"$refresh"}""")

        val reply = post("/api/auth/refresh", """{"refreshToken":"$refresh"}""")

        assertEquals(401, reply.status)
        assertEquals("INVALID_REFRESH_TOKEN", field(reply.body, "code"))
    }

    @Test
    fun `logout answers 204 with no body`() {
        val account = activeAccount(password = password)
        val login = post("/api/auth/login", """{"email":"${account.email}","password":"$password"}""")
        val refresh = field(login.body, "refreshToken")!!

        val reply = post("/api/auth/logout", """{"refreshToken":"$refresh"}""")

        assertEquals(204, reply.status)
        assertEquals("", reply.body, "204 means no content, so there must be none")
    }

    /** Idempotent over HTTP too: the second call is still a 204, not a 404. */
    @Test
    fun `logging out twice answers 204 both times`() {
        val account = activeAccount(password = password)
        val login = post("/api/auth/login", """{"email":"${account.email}","password":"$password"}""")
        val refresh = field(login.body, "refreshToken")!!

        assertEquals(204, post("/api/auth/logout", """{"refreshToken":"$refresh"}""").status)
        assertEquals(204, post("/api/auth/logout", """{"refreshToken":"$refresh"}""").status)
    }

    @Test
    fun `an empty refresh token is rejected at the edge`() {
        assertEquals(400, post("/api/auth/refresh", """{"refreshToken":""}""").status)
    }

    // --- apellidos (feature 012) --------------------------------------------

    private fun validDni(): String {
        val number = (10_000_000..99_999_999).random()
        return "$number${"TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]}"
    }

    private fun onboardingJson(lastName: String?): String {
        val last = lastName?.let { ""","lastName":"$it"""" }.orEmpty()
        return """{"name":"Ana"$last,"identityDocument":"${validDni()}","position":"Florista",""" +
            """"contractType":"PART_TIME","hireDate":"2026-10-01","email":"${uniqueEmail()}","role":"EMPLOYEE"}"""
    }

    private fun signUpJson(lastName: String?): String {
        val last = lastName?.let { ""","lastName":"$it"""" }.orEmpty()
        return """{"email":"${uniqueEmail()}","password":"$password","name":"Ana"$last,""" +
            """"identityDocument":"${validDni()}","bootstrapCode":"cualquiera"}"""
    }

    @Test
    fun `onboarding without a last name is rejected naming the field`() {
        val reply = post("/api/auth/onboarding", onboardingJson(lastName = null))

        assertEquals(400, reply.status, "body: ${reply.body}")
        assertEquals("VALIDATION", field(reply.body, "code"))
        assertTrue(reply.body.contains("lastName"), "the message names the field: ${reply.body}")
    }

    @Test
    fun `onboarding with a blank or over-long last name is rejected`() {
        assertEquals(400, post("/api/auth/onboarding", onboardingJson("  ")).status)
        assertEquals(400, post("/api/auth/onboarding", onboardingJson("a".repeat(151))).status)
    }

    @Test
    fun `onboarding with a last name creates the staff record with it`() {
        val reply = post("/api/auth/onboarding", onboardingJson("García López"))

        assertEquals(201, reply.status, "body: ${reply.body}")
        assertEquals("García López", directory.createdRecords.last().lastName)
    }

    @Test
    fun `the owner's sign-up without a last name is rejected naming the field`() {
        val reply = post("/api/auth/sign-up", signUpJson(lastName = null))

        assertEquals(400, reply.status, "body: ${reply.body}")
        assertEquals("VALIDATION", field(reply.body, "code"))
        assertTrue(reply.body.contains("lastName"), "the message names the field: ${reply.body}")
    }

    @Test
    fun `the owner's sign-up with an over-long last name is rejected`() {
        val reply = post("/api/auth/sign-up", signUpJson("a".repeat(151)))

        assertEquals(400, reply.status, "body: ${reply.body}")
        assertEquals("VALIDATION", field(reply.body, "code"))
    }

    // --- alta y cambio de contrasena ----------------------------------------

    @Test
    fun `granting access over HTTP returns the temporary password once`() {
        val employeeId = java.util.UUID.randomUUID().also { directory.registerActive(it) }
        val email = uniqueEmail()

        val reply = post(
            "/api/auth/accounts",
            """{"employeeId":"$employeeId","email":"$email","role":"EMPLOYEE"}"""
        )

        assertEquals(201, reply.status, "body: ${reply.body}")
        assertEquals(email, field(reply.body, "email"))
        assertEquals("EMPLOYEE", field(reply.body, "role"))
        assertNotNull(
            field(reply.body, "temporaryPassword"),
            "this response is the only place it ever exists: there is no second chance " +
                "to fetch it"
        )
    }

    @Test
    fun `granting access to somebody unknown answers 404 with its code`() {
        val reply = post(
            "/api/auth/accounts",
            """{"employeeId":"${java.util.UUID.randomUUID()}","email":"${uniqueEmail()}"}"""
        )

        assertEquals(404, reply.status)
        assertEquals("EMPLOYEE_NOT_FOUND", field(reply.body, "code"))
    }

    /**
     * 409 and not 400: it is the state of the system that blocks the operation,
     * not the shape of the request.
     */
    @Test
    fun `a duplicate account answers 409`() {
        val employeeId = java.util.UUID.randomUUID().also { directory.registerActive(it) }
        val body = """{"employeeId":"$employeeId","email":"${uniqueEmail()}"}"""
        post("/api/auth/accounts", body)

        val reply = post(
            "/api/auth/accounts",
            """{"employeeId":"$employeeId","email":"${uniqueEmail()}"}"""
        )

        assertEquals(409, reply.status)
        assertEquals("ACCOUNT_ALREADY_EXISTS", field(reply.body, "code"))
    }

    /**
     * The only error in the feature with a third field, and therefore a declared
     * deviation from principle VIII. FR-023 requires saying *which* requirement
     * failed, and a client that marks form fields needs identifiers rather than
     * a sentence - so the field has to survive the trip over the wire.
     */
    @Test
    fun `a weak password answers 422 with the list of requirements`() {
        val (email, temporaryPassword) = newAccess()
        val login = post("/api/auth/login", """{"email":"$email","password":"$temporaryPassword"}""")
        val access = field(login.body, "accessToken")!!

        val reply = post(
            "/api/auth/change-password",
            """{"currentPassword":"$temporaryPassword","newPassword":"abc"}""",
            auth = "Bearer $access"
        )

        assertEquals(422, reply.status, "body: ${reply.body}")
        assertEquals("WEAK_PASSWORD", field(reply.body, "code"))
        assertTrue(
            reply.body.contains("MIN_LENGTH") &&
                reply.body.contains("MISSING_SYMBOL"),
            "the `requirements` field has to reach the client: ${reply.body}"
        )
        assertTrue(!reply.body.contains("abc"), "and must never quote the password")
    }

    @Test
    fun `changing the password over HTTP returns a pair with the flag cleared`() {
        val (email, temporaryPassword) = newAccess()
        val login = post("/api/auth/login", """{"email":"$email","password":"$temporaryPassword"}""")
        assertEquals("true", field(login.body, "passwordChangeRequired"))
        val access = field(login.body, "accessToken")!!

        val reply = post(
            "/api/auth/change-password",
            """{"currentPassword":"$temporaryPassword","newPassword":"Granatum-2027!"}""",
            auth = "Bearer $access"
        )

        assertEquals(200, reply.status, "body: ${reply.body}")
        assertEquals("false", field(reply.body, "passwordChangeRequired"))
        assertNotNull(field(reply.body, "accessToken"))
    }

    @Test
    fun `a wrong current password answers 401`() {
        val (email, temporaryPassword) = newAccess()
        val login = post("/api/auth/login", """{"email":"$email","password":"$temporaryPassword"}""")
        val access = field(login.body, "accessToken")!!

        val reply = post(
            "/api/auth/change-password",
            """{"currentPassword":"no-es-la-actual","newPassword":"Granatum-2027!"}""",
            auth = "Bearer $access"
        )

        assertEquals(401, reply.status)
        assertEquals("INVALID_CREDENTIALS", field(reply.body, "code"))
    }

    // --- restablecimiento ---------------------------------------------------

    @Test
    fun `resetting over HTTP returns a new temporary password with 200`() {
        val (email, temporaryPassword) = newAccess()
        val employeeId = accounts.findByEmail(email)!!.employeeId

        val reply = post("/api/auth/accounts/$employeeId/reset", "")

        assertEquals(200, reply.status, "body: ${reply.body}")
        val newPassword = field(reply.body, "temporaryPassword")
        assertNotNull(newPassword)
        assertTrue(newPassword != temporaryPassword)
    }

    @Test
    fun `resetting somebody without an account answers 404 with its code`() {
        val reply = post("/api/auth/accounts/${java.util.UUID.randomUUID()}/reset", "")

        assertEquals(404, reply.status)
        assertEquals("ACCOUNT_NOT_FOUND", field(reply.body, "code"))
    }

    /** Grants access through the API and returns the email and its temporary password. */
    private fun newAccess(): Pair<String, String> {
        val employeeId = java.util.UUID.randomUUID().also { directory.registerActive(it) }
        val email = uniqueEmail()
        val onboarding = post(
            "/api/auth/accounts",
            """{"employeeId":"$employeeId","email":"$email"}"""
        )
        return email to field(onboarding.body, "temporaryPassword")!!
    }

    /**
     * The response is the only place the refresh token ever exists in clear, so
     * it must not also be leaking the stored hash - a client has no use for it
     * and it is the lookup key for a live session.
     */
    @Test
    fun `the response carries no hash and no password`() {
        val account = activeAccount(password = password)

        val reply = post(
            "/api/auth/login",
            """{"email":"${account.email}","password":"$password"}"""
        )

        assertTrue(!reply.body.contains(password), "the password must not echo back")
        assertTrue(!reply.body.contains("argon2"), "no password hash in the body")
        assertTrue(!reply.body.contains("tokenHash"), "no session hash in the body")
    }
}
