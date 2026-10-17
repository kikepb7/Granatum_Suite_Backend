package com.granatum.core

import com.granatum.core.domain.exception.ImageStorageUnavailableException
import com.granatum.core.infrastructure.storage.MaterialImageStorageProperties
import com.granatum.core.infrastructure.storage.SupabaseMaterialImageStorage
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
class SupabaseMaterialImageStorageTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: SupabaseMaterialImageStorage
    private lateinit var properties: MaterialImageStorageProperties

    @BeforeEach
    fun start() {
        server = MockWebServer().apply { start() }
        properties = MaterialImageStorageProperties(
            supabaseUrl = server.url("/").toString(),
            serviceKey = "clave-de-servicio",
            bucket = "materiales",
            urlSeconds = 600,
            maxBytes = 10_485_760,
            timeoutSeconds = 5
        )
        storage = SupabaseMaterialImageStorage(properties)
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun next() = server.takeRequest(5, TimeUnit.SECONDS)!!

    @Test
    fun `an upload posts the bytes untouched to the object path, never upserting`() {
        server.enqueue(MockResponse().setBody("""{"Key":"materials/m/1.jpg"}"""))
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

        storage.store("materials/m/1.jpg", bytes, "image/jpeg")

        val request = next()
        assertEquals("POST", request.method)
        assertEquals("/storage/v1/object/materiales/materials/m/1.jpg", request.path)
        assertEquals("Bearer clave-de-servicio", request.getHeader("Authorization"))
        assertEquals("clave-de-servicio", request.getHeader("apikey"))
        assertEquals("image/jpeg", request.getHeader("Content-Type"))
        assertEquals("false", request.getHeader("x-upsert"))
        assertContentEquals(bytes, request.body.readByteArray())
    }

    @Test
    fun `a delete sends the paths as prefixes`() {
        server.enqueue(MockResponse().setBody("[]"))

        storage.delete(listOf("materials/m/1.jpg"))

        val request = next()
        assertEquals("DELETE", request.method)
        assertEquals("/storage/v1/object/materiales", request.path)
        assertEquals("""{"prefixes":["materials/m/1.jpg"]}""", request.body.readUtf8())
    }

    @Test
    fun `signing is one call for every path, and the relative URLs come back absolute`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                [
                  {"error": null, "path": "materials/m/1.jpg", "signedURL": "/object/sign/materiales/materials/m/1.jpg?token=t1"},
                  {"error": "Either the object does not exist or you do not have access to it", "path": "materials/m/2.jpg", "signedURL": null}
                ]
                """.trimIndent()
            )
        )

        val urls = storage.sign(listOf("materials/m/1.jpg", "materials/m/2.jpg"))

        val request = next()
        assertEquals("POST", request.method)
        assertEquals("/storage/v1/object/sign/materiales", request.path)
        val body = request.body.readUtf8()
        assertTrue("\"expiresIn\":600" in body, body)
        assertTrue("\"paths\":[\"materials/m/1.jpg\",\"materials/m/2.jpg\"]" in body, body)
        assertEquals(
            mapOf("materials/m/1.jpg" to server.url("/storage/v1/object/sign/materiales/materials/m/1.jpg?token=t1").toString()),
            urls,
            "a path Storage could not sign is left out, not failed"
        )
    }

    @Test
    fun `nothing to delete or sign makes no call`() {
        storage.delete(emptyList())
        assertEquals(emptyMap(), storage.sign(emptyList()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an error answer becomes the service-unavailable exception`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertFailsWith<ImageStorageUnavailableException> {
            storage.store("materials/m/1.jpg", byteArrayOf(1), "image/jpeg")
        }

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Duplicate"}"""))
        assertFailsWith<ImageStorageUnavailableException> {
            storage.store("materials/m/1.jpg", byteArrayOf(1), "image/jpeg")
        }
    }

    @Test
    fun `an unreachable storage becomes the service-unavailable exception`() {
        server.shutdown()
        assertFailsWith<ImageStorageUnavailableException> { storage.sign(listOf("materials/m/1.jpg")) }
    }

    @Test
    fun `the service key never shows in the configuration's toString`() {
        assertFalse("clave-de-servicio" in properties.toString(), properties.toString())
        assertFalse(MaterialImageStorageProperties("", "", "materiales", 600, 1).enabled)
        assertFalse(MaterialImageStorageProperties("https://x.test", "", "materiales", 600, 1).enabled)
        assertTrue(properties.enabled)
    }
}
