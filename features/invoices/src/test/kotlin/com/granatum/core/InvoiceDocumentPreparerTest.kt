package com.granatum.core

import com.granatum.core.infrastructure.documents.RejectedInvoiceDocument
import com.granatum.core.infrastructure.documents.InvoiceDocumentPreparer
import com.granatum.core.files.DocumentType
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
class InvoiceDocumentPreparerTest {

    private val preparer = InvoiceDocumentPreparer(pdfMaxPages = 20)

    private fun pdf(pages: Int, encrypted: Boolean = false): ByteArray {
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            if (encrypted) {
                doc.protect(StandardProtectionPolicy("propietario", "usuario", AccessPermission()).apply { encryptionKeyLength = 128 })
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    @Test
    fun `a readable PDF of up to 20 pages is sent as it is`() {
        val original = pdf(3)
        val sent = preparer.forSending(original, DocumentType.PDF)
        assertContentEquals(original, sent.content)
        assertEquals("application/pdf", sent.mediaType)
    }

    @Test
    fun `an encrypted PDF or one of 21 pages is refused as unreadable`() {
        assertFailsWith<RejectedInvoiceDocument.UnreadablePdf> { preparer.inspect(pdf(1, encrypted = true), DocumentType.PDF) }
        assertFailsWith<RejectedInvoiceDocument.UnreadablePdf> { preparer.inspect(pdf(21), DocumentType.PDF) }
        assertFailsWith<RejectedInvoiceDocument.UnreadablePdf> { preparer.inspect("%PDF-1.7 roto".toByteArray(), DocumentType.PDF) }
        preparer.inspect(pdf(20), DocumentType.PDF)
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
        val original = largePhoto()
        assertTrue(original.size > 5_000_000, "the test photo must be a large one: ${original.size} bytes")
        val fingerprint = sha(original)

        val sent = preparer.forSending(original, DocumentType.JPEG)

        assertEquals(fingerprint, sha(original), "the original is untouched")
        assertEquals("image/jpeg", sent.mediaType)
        val reduced = ImageIO.read(ByteArrayInputStream(sent.content))
        assertEquals(2576, maxOf(reduced.width, reduced.height), "long edge at the model's native limit")
        assertEquals(6000.0 / 8000.0, reduced.width.toDouble() / reduced.height, 0.01, "aspect ratio kept")
        assertTrue(sent.content.size * 4 / 3 < 10_000_000, "under the API's 10 MB base64 limit")
    }

    @Test
    fun `a small image is sent as it is`() {
        val small = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val sent = preparer.forSending(small, DocumentType.PNG)
        assertContentEquals(small, sent.content)
        assertEquals("image/png", sent.mediaType)
    }

    /**
     * The JVM cannot decode WebP, so a WebP cannot be reduced. One whose base64
     * would exceed the API's 10 MB is refused at upload instead of failing later.
     */
    @Test
    fun `a WebP too large to send is refused at upload`() {
        val huge = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(7_600_000)
        assertFailsWith<RejectedInvoiceDocument.TooLarge> { preparer.inspect(huge, DocumentType.WEBP) }
        preparer.inspect("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(1000), DocumentType.WEBP)
    }

    private fun largePhoto(): ByteArray {
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
