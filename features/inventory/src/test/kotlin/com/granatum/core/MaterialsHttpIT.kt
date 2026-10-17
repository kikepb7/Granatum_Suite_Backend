package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.CategoryEntity
import com.granatum.core.infrastructure.database.repositories.CategoryRepository
import com.granatum.core.service.JwtService
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The inventory's HTTP contract end to end: real JSON over a real socket, the
 * real migrations on a real Postgres, and a local HTTP server standing in for
 * Supabase Storage.
 *
 * A real port and not MockMvc for the reason auth's HTTP contract test gives: what
 * is under test is the wire - converters, multipart, validation, error shapes.
 * Authorisation is out of scope here (see [InventoryPermissiveSecurityTestConfig]);
 * `app` covers it with the real `SecurityConfig`.
 */
@SpringBootTest(
    classes = [InventoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(InventoryPermissiveSecurityTestConfig::class)
class MaterialsHttpIT {

    companion object {
        // Started by hand and never stopped: the singleton pattern explained
        // in auth's base integration test.
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        /** Every request Storage received, in order. Cleared before each test. */
        val received = CopyOnWriteArrayList<RecordedRequest>()

        @JvmStatic
        val storage = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    received += request
                    val path = request.path.orEmpty()
                    return when {
                        path == "/storage/v1/object/sign/materiales" -> {
                            val paths = Regex("\"paths\":\\[(.*?)]").find(request.body.clone().readUtf8())!!
                                .groupValues[1].split(",").map { it.trim('"') }
                            MockResponse().setHeader("Content-Type", "application/json").setBody(
                                paths.joinToString(",", "[", "]") {
                                    """{"error":null,"path":"$it","signedURL":"/object/sign/materiales/$it?token=firma"}"""
                                }
                            )
                        }
                        request.method == "POST" -> MockResponse().setBody("""{"Key":"x"}""")
                        request.method == "DELETE" -> MockResponse().setBody("[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("inventory.images.supabase-url") { storage.url("/").toString() }
            registry.add("inventory.images.service-key") { "clave-de-prueba" }
        }
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var categories: CategoryRepository

    @Autowired
    lateinit var jwt: JwtService

    private data class Response(val status: Int, val body: String)

    private val client: RestClient
        get() = RestClient.builder().baseUrl("http://localhost:$port").build()

    private fun send(method: HttpMethod, path: String, json: String? = null, token: String? = null): Response =
        client.method(method).uri(path)
            .apply { if (json != null) contentType(MediaType.APPLICATION_JSON).body(json) }
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .exchange({ _, r -> Response(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    private fun upload(id: String, bytes: ByteArray, fileName: String = "foto.jpg"): Response {
        val parts = LinkedMultiValueMap<String, Any>().apply {
            add("image", object : ByteArrayResource(bytes) {
                override fun getFilename() = fileName
            })
        }
        return client.put().uri("/api/materials/$id/image")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(parts)
            .exchange({ _, r -> Response(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!
    }

    /** Deliberately a regex and not a JSON parse: it reads the raw wire bytes. */
    private fun field(body: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"?([^\",}]*)\"?").find(body)?.groupValues?.get(1)

    /** The material without its nested category, which has a `description`, `createdAt` and `updatedAt` of its own. */
    private fun withoutCategory(body: String): String = body.replace(Regex("\"category\":\\{[^}]*}"), "")

    /** A field of the material itself, never of its nested category. */
    private fun materialField(body: String, name: String): String? = field(withoutCategory(body), name)

    private lateinit var categoryId: UUID

    @BeforeEach
    fun prepare() {
        received.clear()
        categoryId = categories.save(CategoryEntity(name = "Categoria ${UUID.randomUUID()}")).id
    }

    private fun create(warehouse: String = "B", section: Int = 4, quantity: Int = 12): String {
        val r = send(
            HttpMethod.POST, "/api/materials",
            """
            {"categoryId":"$categoryId","description":"Bobina de cable",
             "dimensions":{"height":30,"width":45.5},"quantity":$quantity,
             "location":{"warehouse":"$warehouse","section":$section}}
            """.trimIndent()
        )
        assertEquals(201, r.status, r.body)
        return field(r.body, "id")!!
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)

    // --- US1 / US2 -----------------------------------------------------------

    @Test
    fun `a material is created and read back with exactly the new model`() {
        val id = create()

        val r = send(HttpMethod.GET, "/api/materials/$id")

        assertEquals(200, r.status)
        assertTrue(""""location":{"warehouse":"B","section":4}""" in r.body, r.body)
        assertTrue(""""dimensions":{"height":30""" in r.body, r.body)
        assertEquals("12", field(r.body, "quantity"))
        // Not field(): the nested category has a "description" of its own, first.
        assertTrue(""""description":"Bobina de cable"""" in r.body, r.body)
        assertEquals("null", field(r.body, "imageUrl"))
        assertNotNull(materialField(r.body, "createdAt"))
        assertEquals(materialField(r.body, "createdAt"), materialField(r.body, "updatedAt"))
        val material = withoutCategory(r.body)
        val retired = listOf("name", "color", "status", "unitPrice", "supplier", "photos", "totalQuantity")
        val spanish = listOf(
            "categoria", "descripcion", "dimensiones", "alto", "ancho", "cantidad", "localizacion", "almacen",
            "seccion", "imagenUrl", "fechaAlta", "fechaUltimaModificacion"
        )
        for (gone in retired + spanish) {
            assertFalse("\"$gone\"" in material, "$gone is gone: $material")
        }
    }

    @Test
    fun `an edit moves the modification date, keeps the creation date and ignores a quantity`() {
        val id = create(quantity = 12)
        val before = send(HttpMethod.GET, "/api/materials/$id").body

        val r = send(
            HttpMethod.PUT, "/api/materials/$id",
            """
            {"categoryId":"$categoryId","description":"Otra","quantity":99,
             "dimensions":{"height":1,"width":2},"location":{"warehouse":"C","section":5}}
            """.trimIndent()
        )

        assertEquals(200, r.status, r.body)
        assertTrue(""""description":"Otra"""" in r.body, r.body)
        assertEquals("12", field(r.body, "quantity"))
        assertEquals(materialField(before, "createdAt"), materialField(r.body, "createdAt"))
        assertTrue(materialField(r.body, "updatedAt")!! > materialField(before, "updatedAt")!!)
    }

    @Test
    fun `invalid locations and dimensions are rejected naming the field, never echoing the value`() {
        fun body(location: String, dimensions: String = """{"height":1,"width":1}""", description: String = "x") =
            """{"categoryId":"$categoryId","description":"$description","dimensions":$dimensions,"quantity":1,"location":$location}"""

        val cases = mapOf(
            body("""{"warehouse":"D","section":1}""") to "location.warehouse",
            body("""{"warehouse":"A","section":6}""") to "location.section",
            body("""{"warehouse":"A","section":0}""") to "location.section",
            body("""{"warehouse":"A"}""") to "location.section",
            """{"categoryId":"$categoryId","description":"x","dimensions":{"height":1,"width":1},"quantity":1}""" to "location",
            body("""{"warehouse":"A","section":1}""", """{"height":0,"width":1}""") to "dimensions.height",
            body("""{"warehouse":"A","section":1}""", description = "   ") to "description"
        )
        for ((json, expectedField) in cases) {
            val r = send(HttpMethod.POST, "/api/materials", json)
            assertEquals(400, r.status, json)
            assertEquals("VALIDATION", field(r.body, "code"), r.body)
            assertTrue(expectedField in r.body, "names $expectedField: ${r.body}")
        }
        assertFalse("\"D\"" in send(HttpMethod.POST, "/api/materials", body("""{"warehouse":"D","section":1}""")).body)
    }

    @Test
    fun `an unknown category is a 404 with its own code`() {
        val r = send(
            HttpMethod.POST, "/api/materials",
            """{"categoryId":"${UUID.randomUUID()}","description":"x","dimensions":{"height":1,"width":1},"quantity":1,"location":{"warehouse":"A","section":1}}"""
        )
        assertEquals(404, r.status)
        assertEquals("CATEGORY_NOT_FOUND", field(r.body, "code"))
    }

    @Test
    fun `categories are served under their English route and fields`() {
        val r = send(HttpMethod.POST, "/api/categories", """{"name":"Velas ${UUID.randomUUID()}","description":"Cera"}""")
        assertEquals(201, r.status, r.body)
        assertEquals("Cera", field(r.body, "description"))
        val id = field(r.body, "id")!!

        assertEquals(200, send(HttpMethod.GET, "/api/categories/$id").status)
        assertEquals(404, send(HttpMethod.GET, "/api/categorias/$id").status, "the Spanish route is gone")
    }

    // --- US5 -----------------------------------------------------------------

    @Test
    fun `the listing filters by warehouse and section, and a section needs a warehouse`() {
        val a3 = create("A", 3)
        val c2 = create("C", 2)

        val inA = send(HttpMethod.GET, "/api/materials?warehouse=A").body
        assertTrue(a3 in inA)
        assertFalse(c2 in inA)

        val inA3 = send(HttpMethod.GET, "/api/materials?warehouse=A&section=3").body
        assertTrue(a3 in inA3)
        assertFalse(""""section":1}""" in inA3 || """"warehouse":"C"""" in inA3, inA3)

        for (path in listOf("/api/materials?section=3", "/api/materials?warehouse=D", "/api/materials?warehouse=A&section=9")) {
            val r = send(HttpMethod.GET, path)
            assertEquals(400, r.status, path)
            assertEquals("VALIDATION", field(r.body, "code"), r.body)
        }
    }

    // --- US4 -----------------------------------------------------------------

    @Test
    fun `the quantity changes only with a reason, on record with who did it`() {
        val id = create(quantity = 10)
        val user = UUID.randomUUID()
        val token = jwt.generateAccessToken(user, Role.MANAGER)

        val withoutReason = send(HttpMethod.PATCH, "/api/materials/$id/quantity", """{"quantity":7}""", token)
        assertEquals(400, withoutReason.status)

        val r = send(HttpMethod.PATCH, "/api/materials/$id/quantity", """{"quantity":7,"reason":"Rotura"}""", token)
        assertEquals(200, r.status, r.body)
        assertEquals("7", field(r.body, "quantity"))

        val history = send(HttpMethod.GET, "/api/materials/$id/history").body
        assertEquals("QUANTITY", field(history, "changeType"))
        assertEquals("10", field(history, "previousValue"))
        assertEquals("7", field(history, "newValue"))
        assertEquals("Rotura", field(history, "reason"))
        assertEquals(user.toString(), field(history, "userId"))
        assertNotNull(field(history, "date"))
    }

    // --- US3 -----------------------------------------------------------------

    @Test
    fun `an image is uploaded to storage, served by a signed URL, replaced, removed and deleted with its material`() {
        val id = create()

        val uploaded = upload(id, jpeg)
        assertEquals(200, uploaded.status, uploaded.body)
        val first = received.single { it.method == "POST" && !it.path!!.contains("/sign/") }
        val firstPath = first.path!!.removePrefix("/storage/v1/object/materiales/")
        assertTrue(Regex("materials/$id/[0-9a-f-]{36}\\.jpg").matches(firstPath), firstPath)
        assertEquals("image/jpeg", first.getHeader("Content-Type"))
        assertEquals("Bearer clave-de-prueba", first.getHeader("Authorization"))
        val url = field(uploaded.body, "imageUrl")!!
        assertTrue(url.startsWith(storage.url("/storage/v1/object/sign/materiales/$firstPath").toString()), url)
        assertFalse(firstPath in uploaded.body.replace(url, ""), "the storage path itself never leaves: ${uploaded.body}")

        received.clear()
        assertEquals(200, upload(id, jpeg).status)
        val deletion = received.single { it.method == "DELETE" }
        assertEquals("""{"prefixes":["$firstPath"]}""", deletion.body.readUtf8(), "the replaced image is deleted")

        received.clear()
        val removed = send(HttpMethod.DELETE, "/api/materials/$id/image")
        assertEquals(200, removed.status)
        assertEquals("null", field(removed.body, "imageUrl"))
        assertEquals(1, received.count { it.method == "DELETE" })

        assertEquals(200, upload(id, jpeg).status)
        received.clear()
        assertEquals(204, send(HttpMethod.DELETE, "/api/materials/$id").status)
        assertEquals(1, received.count { it.method == "DELETE" }, "deleting the material deletes its image")
        assertEquals(404, send(HttpMethod.GET, "/api/materials/$id").status)
    }

    @Test
    fun `a file that is not an image by its bytes is refused whatever its name says`() {
        val id = create()

        val r = upload(id, "%PDF-1.7 no es una foto".toByteArray(), fileName = "foto.jpg")

        assertEquals(415, r.status)
        assertEquals("INVALID_IMAGE", field(r.body, "code"))
        assertTrue(received.isEmpty(), "nothing reached storage")
    }

    @Test
    fun `a file over 10 MB is refused with its own code`() {
        val id = create()

        val r = upload(id, jpeg + ByteArray(10 * 1024 * 1024))

        assertEquals(413, r.status)
        assertEquals("IMAGE_TOO_LARGE", field(r.body, "code"))
    }

    @Test
    fun `uploading to a missing material is a 404 and stores nothing`() {
        val r = upload(UUID.randomUUID().toString(), jpeg)

        assertEquals(404, r.status)
        assertEquals("MATERIAL_NOT_FOUND", field(r.body, "code"))
        assertNotNull(r.body)
        assertTrue(received.isEmpty())
    }
}
