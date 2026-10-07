package com.granatum.core

import com.granatum.core.infrastructure.documentos.DocumentoNoAdmitido
import com.granatum.core.infrastructure.documentos.PreparadorDocumento
import com.granatum.core.infrastructure.documentos.TipoDocumento
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What can be sent to Claude, and in what shape (research.md D-007). The
 * original is never touched: only the copy that is sent may be reduced.
 */
class PreparadorDocumentoTest {

    private val preparador = PreparadorDocumento(pdfMaxPaginas = 20)

    private fun pdf(paginas: Int, cifrado: Boolean = false): ByteArray {
        PDDocument().use { doc ->
            repeat(paginas) { doc.addPage(PDPage()) }
            if (cifrado) {
                doc.protect(StandardProtectionPolicy("propietario", "usuario", AccessPermission()).apply { encryptionKeyLength = 128 })
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    @Test
    fun `a readable PDF of up to 20 pages is sent as it is`() {
        val original = pdf(3)
        val enviado = preparador.paraEnviar(original, TipoDocumento.PDF)
        assertContentEquals(original, enviado.contenido)
        assertEquals("application/pdf", enviado.mediaType)
    }

    @Test
    fun `an encrypted PDF or one of 21 pages is refused as unreadable`() {
        assertFailsWith<DocumentoNoAdmitido.PdfNoLegible> { preparador.examinar(pdf(1, cifrado = true), TipoDocumento.PDF) }
        assertFailsWith<DocumentoNoAdmitido.PdfNoLegible> { preparador.examinar(pdf(21), TipoDocumento.PDF) }
        assertFailsWith<DocumentoNoAdmitido.PdfNoLegible> { preparador.examinar("%PDF-1.7 roto".toByteArray(), TipoDocumento.PDF) }
        preparador.examinar(pdf(20), TipoDocumento.PDF)
    }

    /**
     * A large phone photo (generated here: noise compresses badly, so a 6000x8000
     * JPEG is several megabytes, like a real one). Above 2576 px on the long edge
     * the API would downscale it anyway, so it is reduced before sending: fewer
     * bytes on the wire, and well under the 10 MB base64 limit. The original
     * keeps its exact hash.
     */
    @Test
    fun `a large photo is reduced only for sending, and the original keeps its hash`() {
        val original = fotoGrande()
        assertTrue(original.size > 5_000_000, "the test photo must be a large one: ${original.size} bytes")
        val huella = sha(original)

        val enviado = preparador.paraEnviar(original, TipoDocumento.JPEG)

        assertEquals(huella, sha(original), "the original is untouched")
        assertEquals("image/jpeg", enviado.mediaType)
        val reducida = ImageIO.read(ByteArrayInputStream(enviado.contenido))
        assertEquals(2576, maxOf(reducida.width, reducida.height), "long edge at the model's native limit")
        assertEquals(6000.0 / 8000.0, reducida.width.toDouble() / reducida.height, 0.01, "aspect ratio kept")
        assertTrue(enviado.contenido.size * 4 / 3 < 10_000_000, "under the API's 10 MB base64 limit")
    }

    @Test
    fun `a small image is sent as it is`() {
        val pequena = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val enviado = preparador.paraEnviar(pequena, TipoDocumento.PNG)
        assertContentEquals(pequena, enviado.contenido)
        assertEquals("image/png", enviado.mediaType)
    }

    /**
     * The JVM cannot decode WebP, so a WebP cannot be reduced. One whose base64
     * would exceed the API's 10 MB is refused at upload instead of failing later.
     */
    @Test
    fun `a WebP too large to send is refused at upload`() {
        val enorme = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(7_600_000)
        assertFailsWith<DocumentoNoAdmitido.DemasiadoGrande> { preparador.examinar(enorme, TipoDocumento.WEBP) }
        preparador.examinar("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(1000), TipoDocumento.WEBP)
    }

    private fun fotoGrande(): ByteArray {
        val img = BufferedImage(6000, 8000, BufferedImage.TYPE_INT_RGB)
        val r = Random(42)
        for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) {
            val c = r.nextInt(0xFFFFFF)
            img.setRGB(x, y, c); img.setRGB(x + 1, y, c); img.setRGB(x, y + 1, c); img.setRGB(x + 1, y + 1, c)
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.6f }
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
