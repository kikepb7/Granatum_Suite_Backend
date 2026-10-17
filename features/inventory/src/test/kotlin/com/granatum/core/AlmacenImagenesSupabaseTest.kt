package com.granatum.core

import com.granatum.core.domain.exception.AlmacenImagenesNoDisponibleException
import com.granatum.core.infrastructure.storage.AlmacenImagenesSupabase
import com.granatum.core.infrastructure.storage.ConfiguracionAlmacenImagenes
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Supabase Storage client against a local HTTP server: the exact requests
 * it sends (research.md D-001) and how it fails. No real Storage is called.
 */
class AlmacenImagenesSupabaseTest {

    private lateinit var servidor: MockWebServer
    private lateinit var almacen: AlmacenImagenesSupabase
    private lateinit var config: ConfiguracionAlmacenImagenes

    @BeforeEach
    fun arrancar() {
        servidor = MockWebServer().apply { start() }
        config = ConfiguracionAlmacenImagenes(
            supabaseUrl = servidor.url("/").toString(),
            serviceKey = "clave-de-servicio",
            bucket = "materiales",
            urlSegundos = 600,
            maxBytes = 10_485_760,
            timeoutSegundos = 5
        )
        almacen = AlmacenImagenesSupabase(config)
    }

    @AfterEach
    fun parar() = servidor.shutdown()

    private fun siguiente() = servidor.takeRequest(5, TimeUnit.SECONDS)!!

    @Test
    fun `an upload posts the bytes untouched to the object path, never upserting`() {
        servidor.enqueue(MockResponse().setBody("""{"Key":"materiales/m/1.jpg"}"""))
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

        almacen.guardar("materiales/m/1.jpg", bytes, "image/jpeg")

        val peticion = siguiente()
        assertEquals("POST", peticion.method)
        assertEquals("/storage/v1/object/materiales/materiales/m/1.jpg", peticion.path)
        assertEquals("Bearer clave-de-servicio", peticion.getHeader("Authorization"))
        assertEquals("clave-de-servicio", peticion.getHeader("apikey"))
        assertEquals("image/jpeg", peticion.getHeader("Content-Type"))
        assertEquals("false", peticion.getHeader("x-upsert"))
        assertContentEquals(bytes, peticion.body.readByteArray())
    }

    @Test
    fun `a delete sends the paths as prefixes`() {
        servidor.enqueue(MockResponse().setBody("[]"))

        almacen.borrar(listOf("materiales/m/1.jpg"))

        val peticion = siguiente()
        assertEquals("DELETE", peticion.method)
        assertEquals("/storage/v1/object/materiales", peticion.path)
        assertEquals("""{"prefixes":["materiales/m/1.jpg"]}""", peticion.body.readUtf8())
    }

    @Test
    fun `signing is one call for every path, and the relative URLs come back absolute`() {
        servidor.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                [
                  {"error": null, "path": "materiales/m/1.jpg", "signedURL": "/object/sign/materiales/materiales/m/1.jpg?token=t1"},
                  {"error": "Either the object does not exist or you do not have access to it", "path": "materiales/m/2.jpg", "signedURL": null}
                ]
                """.trimIndent()
            )
        )

        val urls = almacen.firmar(listOf("materiales/m/1.jpg", "materiales/m/2.jpg"))

        val peticion = siguiente()
        assertEquals("POST", peticion.method)
        assertEquals("/storage/v1/object/sign/materiales", peticion.path)
        val cuerpo = peticion.body.readUtf8()
        assertTrue("\"expiresIn\":600" in cuerpo, cuerpo)
        assertTrue("\"paths\":[\"materiales/m/1.jpg\",\"materiales/m/2.jpg\"]" in cuerpo, cuerpo)
        assertEquals(
            mapOf("materiales/m/1.jpg" to servidor.url("/storage/v1/object/sign/materiales/materiales/m/1.jpg?token=t1").toString()),
            urls,
            "a path Storage could not sign is left out, not failed"
        )
    }

    @Test
    fun `nothing to delete or sign makes no call`() {
        almacen.borrar(emptyList())
        assertEquals(emptyMap(), almacen.firmar(emptyList()))
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `an error answer becomes the service-unavailable exception`() {
        servidor.enqueue(MockResponse().setResponseCode(500))
        assertFailsWith<AlmacenImagenesNoDisponibleException> {
            almacen.guardar("materiales/m/1.jpg", byteArrayOf(1), "image/jpeg")
        }

        servidor.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Duplicate"}"""))
        assertFailsWith<AlmacenImagenesNoDisponibleException> {
            almacen.guardar("materiales/m/1.jpg", byteArrayOf(1), "image/jpeg")
        }
    }

    @Test
    fun `an unreachable storage becomes the service-unavailable exception`() {
        servidor.shutdown()
        assertFailsWith<AlmacenImagenesNoDisponibleException> { almacen.firmar(listOf("materiales/m/1.jpg")) }
    }

    @Test
    fun `the service key never shows in the configuration's toString`() {
        assertFalse("clave-de-servicio" in config.toString(), config.toString())
        assertFalse(ConfiguracionAlmacenImagenes("", "", "materiales", 600, 1).activo)
        assertFalse(ConfiguracionAlmacenImagenes("https://x.test", "", "materiales", 600, 1).activo)
        assertTrue(config.activo)
    }
}
