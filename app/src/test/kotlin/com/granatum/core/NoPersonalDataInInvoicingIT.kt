package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.granatum.core.HttpTestClient.Companion.field
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-027, principle VI: no name, tax id, amount or description of an invoice
 * reaches the logs, with the root logger at DEBUG - the state a production
 * diagnostic session leaves the application in (research.md D-018).
 *
 * At DEBUG, Spring MVC logs request and response bodies through toString(),
 * which is how passwords leaked in feature 002; an invoice is nothing but
 * names, tax ids and amounts. The supplier here is self-employed, so its tax id
 * is a DNI.
 *
 * Covers the upload, the recognition, a correction, the confirmation and the
 * original's download, and the report as JSON, CSV and PDF.
 *
 * Validated by mutation (2026-10-08): a temporary
 * `log.debug("confirmando {}", f.issuerNif)` in `InvoiceReview.confirm`
 * turned it red with the line quoted; restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WithFixedInvoiceRecognizer::class)
class NoPersonalDataInInvoicingIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    /**
     * A snapshot of what was captured. Logback appends to the list while
     * holding the appender's lock, and scheduled jobs or server threads may
     * still be logging while the test reads: iterating the live list then
     * throws ConcurrentModificationException (seen once in a full build).
     */
    private fun captured(): List<ILoggingEvent> = synchronized(appender) { appender.list.toList() }
    private var previousLevel: Level? = null

    @AfterEach
    fun release() {
        root.detachAppender(appender)
        appender.stop()
        root.level = previousLevel ?: Level.INFO
    }

    private val client by lazy { RestClient.builder().baseUrl("http://localhost:$port").build() }
    private val token by lazy { jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN) }

    private fun json(method: String, path: String, body: String? = null): Pair<Int, String> =
        client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
            .header("Authorization", "Bearer $token")
            .apply { if (body != null) contentType(MediaType.APPLICATION_JSON).body(body) }
            .exchange({ _, r -> r.statusCode.value() to r.body.readAllBytes().decodeToString() }, false)!!

    private fun png(): ByteArray {
        val img = BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, (0..0xFFFFFF).random()) }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun `uploading, recognizing, correcting, confirming, downloading and reporting log no invoice data`() {
        json("PUT", "/api/invoicing/company", """{"legalName":"Floristeria Granatum S.L.","nif":"B12345674"}""")

        appender.start()
        root.addAppender(appender)
        previousLevel = root.level
        root.level = Level.DEBUG

        val parts = LinkedMultiValueMap<String, Any>().apply {
            add("files", object : ByteArrayResource(png()) { override fun getFilename() = "invoice-${WithFixedInvoiceRecognizer.NAME}.png" })
        }
        val upload = client.post().uri("/api/invoicing/invoices")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
            .exchange({ _, r -> r.statusCode.value() to r.body.readAllBytes().decodeToString() }, false)!!
        assertEquals(202, upload.first, upload.second)
        val id = field(upload.second, "invoiceId")!!

        var record = json("GET", "/api/invoicing/invoices/$id")
        val deadline = System.nanoTime() + 10_000_000_000
        while (!record.second.contains("\"DRAFT\"") && System.nanoTime() < deadline) {
            Thread.sleep(100); record = json("GET", "/api/invoicing/invoices/$id")
        }
        assertTrue(record.second.contains(WithFixedInvoiceRecognizer.NIF), "the data did travel - otherwise a clean log proves nothing")

        val version = field(record.second, "version")!!
        val corrected = record.second
            .replace(WithFixedInvoiceRecognizer.CONCEPT, "${WithFixedInvoiceRecognizer.CONCEPT} corregido")
        assertEquals(200, json("PUT", "/api/invoicing/invoices/$id", corrected).first)
        val newVersion = field(json("GET", "/api/invoicing/invoices/$id").second, "version")!!
        assertEquals(200, json("POST", "/api/invoicing/invoices/$id/confirm", """{"version":$newVersion}""").first)
        json("GET", "/api/invoicing/invoices/$id/original")
        assertEquals(200, json("GET", "/api/invoicing/reports?period=QUARTERLY&year=2026&quarter=4").first)
        val csv = json("GET", "/api/invoicing/reports?period=QUARTERLY&year=2026&quarter=4&format=csv")
        assertEquals(200, csv.first)
        // app tests share a persistent database, so the quarter may hold earlier
        // runs' invoices too: the report counts at least this one.
        val received = Regex("Recibidas;Facturas;(\\d+)").find(csv.second)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue(received >= 1, "the report carries this invoice: ${csv.second.take(400)}")
        assertEquals(200, json("GET", "/api/invoicing/reports?period=QUARTERLY&year=2026&quarter=4&format=pdf").first)
        assertTrue(version.isNotEmpty() && captured().isNotEmpty(), "DEBUG was on and something was logged")

        val fromServer = captured().filterNot { it.loggerName.startsWith("org.springframework.web.client") }
        val data = listOf(
            WithFixedInvoiceRecognizer.NAME, WithFixedInvoiceRecognizer.NIF,
            WithFixedInvoiceRecognizer.CONCEPT, WithFixedInvoiceRecognizer.TOTAL, "3571.79", "4321,87"
        )
        val leaks = data.flatMap { value ->
            fromServer.filter { (it.formattedMessage + (it.throwableProxy?.message ?: "")).contains(value) }
                .map { "[$value] [${it.loggerName}] ${it.formattedMessage.take(200)}" }
        }
        assertEquals(emptyList(), leaks, "invoice data reached the logs")
    }
}
