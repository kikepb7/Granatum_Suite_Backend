package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.CategoriaEntity
import com.granatum.core.infrastructure.database.repositories.CategoriaRepository
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
 * A real port and not MockMvc for the reason `ContratoHttpAuthIT` gives: what
 * is under test is the wire - converters, multipart, validation, error shapes.
 * Authorisation is out of scope here (see [SeguridadPermisivaTestConfig]);
 * `app` covers it with the real `SecurityConfig`.
 */
@SpringBootTest(
    classes = [InventoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(SeguridadPermisivaTestConfig::class)
class MaterialesHttpIT {

    companion object {
        // Started by hand and never stopped: the singleton pattern explained
        // in auth's BaseAuthIT.
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        /** Every request Storage received, in order. Cleared before each test. */
        val recibidas = CopyOnWriteArrayList<RecordedRequest>()

        @JvmStatic
        val storage = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recibidas += request
                    val ruta = request.path.orEmpty()
                    return when {
                        ruta == "/storage/v1/object/sign/materiales" -> {
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
            registry.add("inventory.imagenes.supabase-url") { storage.url("/").toString() }
            registry.add("inventory.imagenes.service-key") { "clave-de-prueba" }
        }
    }

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var categorias: CategoriaRepository

    @Autowired
    lateinit var jwt: JwtService

    private data class Respuesta(val estado: Int, val cuerpo: String)

    private val cliente: RestClient
        get() = RestClient.builder().baseUrl("http://localhost:$puerto").build()

    private fun enviar(metodo: HttpMethod, ruta: String, json: String? = null, token: String? = null): Respuesta =
        cliente.method(metodo).uri(ruta)
            .apply { if (json != null) contentType(MediaType.APPLICATION_JSON).body(json) }
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .exchange({ _, r -> Respuesta(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    private fun subir(id: String, bytes: ByteArray, nombre: String = "foto.jpg"): Respuesta {
        val partes = LinkedMultiValueMap<String, Any>().apply {
            add("imagen", object : ByteArrayResource(bytes) {
                override fun getFilename() = nombre
            })
        }
        return cliente.put().uri("/api/materiales/$id/imagen")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(partes)
            .exchange({ _, r -> Respuesta(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!
    }

    /** Deliberately a regex and not a JSON parse: it reads the raw wire bytes. */
    private fun campo(cuerpo: String, nombre: String): String? =
        Regex("\"$nombre\"\\s*:\\s*\"?([^\",}]*)\"?").find(cuerpo)?.groupValues?.get(1)

    private lateinit var categoriaId: UUID

    @BeforeEach
    fun preparar() {
        recibidas.clear()
        categoriaId = categorias.save(CategoriaEntity(nombre = "Categoria ${UUID.randomUUID()}")).id
    }

    private fun crear(almacen: String = "B", seccion: Int = 4, cantidad: Int = 12): String {
        val r = enviar(
            HttpMethod.POST, "/api/materiales",
            """
            {"categoriaId":"$categoriaId","descripcion":"Bobina de cable",
             "dimensiones":{"alto":30,"ancho":45.5},"cantidad":$cantidad,
             "localizacion":{"almacen":"$almacen","seccion":$seccion}}
            """.trimIndent()
        )
        assertEquals(201, r.estado, r.cuerpo)
        return campo(r.cuerpo, "id")!!
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)

    // --- US1 / US2 -----------------------------------------------------------

    @Test
    fun `a material is created and read back with exactly the new model`() {
        val id = crear()

        val r = enviar(HttpMethod.GET, "/api/materiales/$id")

        assertEquals(200, r.estado)
        assertTrue(""""localizacion":{"almacen":"B","seccion":4}""" in r.cuerpo, r.cuerpo)
        assertTrue(""""dimensiones":{"alto":30""" in r.cuerpo, r.cuerpo)
        assertEquals("12", campo(r.cuerpo, "cantidad"))
        // Not campo(): the nested category has a "descripcion" of its own, first.
        assertTrue(""""descripcion":"Bobina de cable"""" in r.cuerpo, r.cuerpo)
        assertEquals("null", campo(r.cuerpo, "imagenUrl"))
        assertEquals(campo(r.cuerpo, "fechaAlta"), campo(r.cuerpo, "fechaUltimaModificacion"))
        // Without the nested category, which has a "nombre" of its own.
        val material = r.cuerpo.replace(Regex("\"categoria\":\\{[^}]*}"), "")
        for (retirado in listOf("nombre", "color", "estado", "precioUnitario", "proveedor", "fotos", "cantidadTotal")) {
            assertFalse("\"$retirado\"" in material, "$retirado is gone: $material")
        }
    }

    @Test
    fun `an edit moves the modification date, keeps the creation date and ignores a quantity`() {
        val id = crear(cantidad = 12)
        val antes = enviar(HttpMethod.GET, "/api/materiales/$id").cuerpo

        val r = enviar(
            HttpMethod.PUT, "/api/materiales/$id",
            """
            {"categoriaId":"$categoriaId","descripcion":"Otra","cantidad":99,
             "dimensiones":{"alto":1,"ancho":2},"localizacion":{"almacen":"C","seccion":5}}
            """.trimIndent()
        )

        assertEquals(200, r.estado, r.cuerpo)
        assertTrue(""""descripcion":"Otra"""" in r.cuerpo, r.cuerpo)
        assertEquals("12", campo(r.cuerpo, "cantidad"))
        assertEquals(campo(antes, "fechaAlta"), campo(r.cuerpo, "fechaAlta"))
        assertTrue(campo(r.cuerpo, "fechaUltimaModificacion")!! > campo(antes, "fechaUltimaModificacion")!!)
    }

    @Test
    fun `invalid locations and dimensions are rejected naming the field, never echoing the value`() {
        fun cuerpo(localizacion: String, dimensiones: String = """{"alto":1,"ancho":1}""", descripcion: String = "x") =
            """{"categoriaId":"$categoriaId","descripcion":"$descripcion","dimensiones":$dimensiones,"cantidad":1,"localizacion":$localizacion}"""

        val casos = mapOf(
            cuerpo("""{"almacen":"D","seccion":1}""") to "localizacion.almacen",
            cuerpo("""{"almacen":"A","seccion":6}""") to "localizacion.seccion",
            cuerpo("""{"almacen":"A","seccion":0}""") to "localizacion.seccion",
            cuerpo("""{"almacen":"A"}""") to "localizacion.seccion",
            """{"categoriaId":"$categoriaId","descripcion":"x","dimensiones":{"alto":1,"ancho":1},"cantidad":1}""" to "localizacion",
            cuerpo("""{"almacen":"A","seccion":1}""", """{"alto":0,"ancho":1}""") to "dimensiones.alto",
            cuerpo("""{"almacen":"A","seccion":1}""", descripcion = "   ") to "descripcion"
        )
        for ((json, campoEsperado) in casos) {
            val r = enviar(HttpMethod.POST, "/api/materiales", json)
            assertEquals(400, r.estado, json)
            assertEquals("VALIDACION", campo(r.cuerpo, "code"), r.cuerpo)
            assertTrue(campoEsperado in r.cuerpo, "names $campoEsperado: ${r.cuerpo}")
        }
        assertFalse("\"D\"" in enviar(HttpMethod.POST, "/api/materiales", cuerpo("""{"almacen":"D","seccion":1}""")).cuerpo)
    }

    @Test
    fun `an unknown category is a 404 with its own code`() {
        val r = enviar(
            HttpMethod.POST, "/api/materiales",
            """{"categoriaId":"${UUID.randomUUID()}","descripcion":"x","dimensiones":{"alto":1,"ancho":1},"cantidad":1,"localizacion":{"almacen":"A","seccion":1}}"""
        )
        assertEquals(404, r.estado)
        assertEquals("CATEGORIA_NOT_FOUND", campo(r.cuerpo, "code"))
    }

    // --- US5 -----------------------------------------------------------------

    @Test
    fun `the listing filters by warehouse and section, and a section needs a warehouse`() {
        val a3 = crear("A", 3)
        val c2 = crear("C", 2)

        val enA = enviar(HttpMethod.GET, "/api/materiales?almacen=A").cuerpo
        assertTrue(a3 in enA)
        assertFalse(c2 in enA)

        val enA3 = enviar(HttpMethod.GET, "/api/materiales?almacen=A&seccion=3").cuerpo
        assertTrue(a3 in enA3)
        assertFalse(""""seccion":1}""" in enA3 || """"almacen":"C"""" in enA3, enA3)

        for (ruta in listOf("/api/materiales?seccion=3", "/api/materiales?almacen=D", "/api/materiales?almacen=A&seccion=9")) {
            val r = enviar(HttpMethod.GET, ruta)
            assertEquals(400, r.estado, ruta)
            assertEquals("VALIDACION", campo(r.cuerpo, "code"), r.cuerpo)
        }
    }

    // --- US4 -----------------------------------------------------------------

    @Test
    fun `the quantity changes only with a reason, on record with who did it`() {
        val id = crear(cantidad = 10)
        val usuario = UUID.randomUUID()
        val token = jwt.generateAccessToken(usuario, Role.ENCARGADO)

        val sinMotivo = enviar(HttpMethod.PATCH, "/api/materiales/$id/cantidad", """{"cantidad":7}""", token)
        assertEquals(400, sinMotivo.estado)

        val r = enviar(HttpMethod.PATCH, "/api/materiales/$id/cantidad", """{"cantidad":7,"motivo":"Rotura"}""", token)
        assertEquals(200, r.estado, r.cuerpo)
        assertEquals("7", campo(r.cuerpo, "cantidad"))

        val historial = enviar(HttpMethod.GET, "/api/materiales/$id/historial").cuerpo
        assertEquals("CANTIDAD", campo(historial, "tipoCambio"))
        assertEquals("10", campo(historial, "valorAnterior"))
        assertEquals("7", campo(historial, "valorNuevo"))
        assertEquals(usuario.toString(), campo(historial, "usuarioId"))
    }

    // --- US3 -----------------------------------------------------------------

    @Test
    fun `an image is uploaded to storage, served by a signed URL, replaced, removed and deleted with its material`() {
        val id = crear()

        val subida = subir(id, jpeg)
        assertEquals(200, subida.estado, subida.cuerpo)
        val primera = recibidas.single { it.method == "POST" && !it.path!!.contains("/sign/") }
        val rutaPrimera = primera.path!!.removePrefix("/storage/v1/object/materiales/")
        assertTrue(Regex("materiales/$id/[0-9a-f-]{36}\\.jpg").matches(rutaPrimera), rutaPrimera)
        assertEquals("image/jpeg", primera.getHeader("Content-Type"))
        assertEquals("Bearer clave-de-prueba", primera.getHeader("Authorization"))
        val url = campo(subida.cuerpo, "imagenUrl")!!
        assertTrue(url.startsWith(storage.url("/storage/v1/object/sign/materiales/$rutaPrimera").toString()), url)
        assertFalse(rutaPrimera in subida.cuerpo.replace(url, ""), "the storage path itself never leaves: ${subida.cuerpo}")

        recibidas.clear()
        assertEquals(200, subir(id, jpeg).estado)
        val borrado = recibidas.single { it.method == "DELETE" }
        assertEquals("""{"prefixes":["$rutaPrimera"]}""", borrado.body.readUtf8(), "the replaced image is deleted")

        recibidas.clear()
        val quitada = enviar(HttpMethod.DELETE, "/api/materiales/$id/imagen")
        assertEquals(200, quitada.estado)
        assertEquals("null", campo(quitada.cuerpo, "imagenUrl"))
        assertEquals(1, recibidas.count { it.method == "DELETE" })

        assertEquals(200, subir(id, jpeg).estado)
        recibidas.clear()
        assertEquals(204, enviar(HttpMethod.DELETE, "/api/materiales/$id").estado)
        assertEquals(1, recibidas.count { it.method == "DELETE" }, "deleting the material deletes its image")
        assertEquals(404, enviar(HttpMethod.GET, "/api/materiales/$id").estado)
    }

    @Test
    fun `a file that is not an image by its bytes is refused whatever its name says`() {
        val id = crear()

        val r = subir(id, "%PDF-1.7 no es una foto".toByteArray(), nombre = "foto.jpg")

        assertEquals(415, r.estado)
        assertEquals("IMAGEN_NO_VALIDA", campo(r.cuerpo, "code"))
        assertTrue(recibidas.isEmpty(), "nothing reached storage")
    }

    @Test
    fun `a file over 10 MB is refused with its own code`() {
        val id = crear()

        val r = subir(id, jpeg + ByteArray(10 * 1024 * 1024))

        assertEquals(413, r.estado)
        assertEquals("IMAGEN_DEMASIADO_GRANDE", campo(r.cuerpo, "code"))
    }

    @Test
    fun `uploading to a missing material is a 404 and stores nothing`() {
        val r = subir(UUID.randomUUID().toString(), jpeg)

        assertEquals(404, r.estado)
        assertEquals("MATERIAL_NOT_FOUND", campo(r.cuerpo, "code"))
        assertNotNull(r.cuerpo)
        assertTrue(recibidas.isEmpty())
    }
}
