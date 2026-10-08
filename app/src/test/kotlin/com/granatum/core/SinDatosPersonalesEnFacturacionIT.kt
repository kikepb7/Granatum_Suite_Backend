package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.granatum.core.ClientePruebaHttp.Companion.campo
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
 * `log.debug("confirmando {}", f.emisorNif)` in `RevisionFacturas.confirmar`
 * turned it red with the line quoted; restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ConReconocedorDeFacturaFija::class)
class SinDatosPersonalesEnFacturacionIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private val raiz = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    /**
     * A snapshot of what was captured. Logback appends to the list while
     * holding the appender's lock, and scheduled jobs or server threads may
     * still be logging while the test reads: iterating the live list then
     * throws ConcurrentModificationException (seen once in a full build).
     */
    private fun capturados(): List<ILoggingEvent> = synchronized(appender) { appender.list.toList() }
    private var nivelPrevio: Level? = null

    @AfterEach
    fun soltar() {
        raiz.detachAppender(appender)
        appender.stop()
        raiz.level = nivelPrevio ?: Level.INFO
    }

    private val cliente by lazy { RestClient.builder().baseUrl("http://localhost:$puerto").build() }
    private val token by lazy { jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN) }

    private fun json(metodo: String, ruta: String, cuerpo: String? = null): Pair<Int, String> =
        cliente.method(org.springframework.http.HttpMethod.valueOf(metodo)).uri(ruta)
            .header("Authorization", "Bearer $token")
            .apply { if (cuerpo != null) contentType(MediaType.APPLICATION_JSON).body(cuerpo) }
            .exchange({ _, r -> r.statusCode.value() to r.body.readAllBytes().decodeToString() }, false)!!

    private fun png(): ByteArray {
        val img = BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, (0..0xFFFFFF).random()) }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun `uploading, recognising, correcting, confirming, downloading and reporting log no invoice data`() {
        json("PUT", "/api/facturacion/empresa", """{"razonSocial":"Floristeria Granatum S.L.","nif":"B12345674"}""")

        appender.start()
        raiz.addAppender(appender)
        nivelPrevio = raiz.level
        raiz.level = Level.DEBUG

        val partes = LinkedMultiValueMap<String, Any>().apply {
            add("ficheros", object : ByteArrayResource(png()) { override fun getFilename() = "factura-${ConReconocedorDeFacturaFija.NOMBRE}.png" })
        }
        val subida = cliente.post().uri("/api/facturacion/facturas")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.MULTIPART_FORM_DATA).body(partes)
            .exchange({ _, r -> r.statusCode.value() to r.body.readAllBytes().decodeToString() }, false)!!
        assertEquals(202, subida.first, subida.second)
        val id = campo(subida.second, "facturaId")!!

        var ficha = json("GET", "/api/facturacion/facturas/$id")
        val limite = System.nanoTime() + 10_000_000_000
        while (!ficha.second.contains("\"BORRADOR\"") && System.nanoTime() < limite) {
            Thread.sleep(100); ficha = json("GET", "/api/facturacion/facturas/$id")
        }
        assertTrue(ficha.second.contains(ConReconocedorDeFacturaFija.NIF), "the data did travel - otherwise a clean log proves nothing")

        val version = campo(ficha.second, "version")!!
        val corregir = ficha.second
            .replace(ConReconocedorDeFacturaFija.CONCEPTO, "${ConReconocedorDeFacturaFija.CONCEPTO} corregido")
        assertEquals(200, json("PUT", "/api/facturacion/facturas/$id", corregir).first)
        val nueva = campo(json("GET", "/api/facturacion/facturas/$id").second, "version")!!
        assertEquals(200, json("POST", "/api/facturacion/facturas/$id/confirmar", """{"version":$nueva}""").first)
        json("GET", "/api/facturacion/facturas/$id/original")
        assertEquals(200, json("GET", "/api/facturacion/reportes?periodo=TRIMESTRAL&anio=2026&trimestre=4").first)
        val csv = json("GET", "/api/facturacion/reportes?periodo=TRIMESTRAL&anio=2026&trimestre=4&formato=csv")
        assertEquals(200, csv.first)
        // app tests share a persistent database, so the quarter may hold earlier
        // runs' invoices too: the report counts at least this one.
        val recibidas = Regex("Recibidas;Facturas;(\\d+)").find(csv.second)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue(recibidas >= 1, "the report carries this invoice: ${csv.second.take(400)}")
        assertEquals(200, json("GET", "/api/facturacion/reportes?periodo=TRIMESTRAL&anio=2026&trimestre=4&formato=pdf").first)
        assertTrue(version.isNotEmpty() && capturados().isNotEmpty(), "DEBUG was on and something was logged")

        val delServidor = capturados().filterNot { it.loggerName.startsWith("org.springframework.web.client") }
        val datos = listOf(
            ConReconocedorDeFacturaFija.NOMBRE, ConReconocedorDeFacturaFija.NIF,
            ConReconocedorDeFacturaFija.CONCEPTO, ConReconocedorDeFacturaFija.TOTAL, "3571.79", "4321,87"
        )
        val fugas = datos.flatMap { dato ->
            delServidor.filter { (it.formattedMessage + (it.throwableProxy?.message ?: "")).contains(dato) }
                .map { "[$dato] [${it.loggerName}] ${it.formattedMessage.take(200)}" }
        }
        assertEquals(emptyList(), fugas, "invoice data reached the logs")
    }
}
