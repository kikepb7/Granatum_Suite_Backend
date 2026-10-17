package com.granatum.core.files

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The type of an uploaded file comes from its first bytes, never from its name
 * or the Content-Type the client sends (specs/004-invoices research.md D-007).
 */
class FileTypeDetectorTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() } + ByteArray(32)

    @Test
    fun `JPEG, PNG, WebP and PDF are recognised by their signature`() {
        assertEquals(DocumentType.JPEG, FileTypeDetector.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(DocumentType.PNG, FileTypeDetector.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(DocumentType.WEBP, FileTypeDetector.detect("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(DocumentType.PDF, FileTypeDetector.detect("%PDF-1.7\n".toByteArray()))
    }

    @Test
    fun `the media type and extension follow the detected type`() {
        assertEquals("application/pdf", DocumentType.PDF.mediaType)
        assertEquals("jpg", DocumentType.JPEG.extension)
        assertEquals("image/webp", DocumentType.WEBP.mediaType)
    }

    /** A PDF renamed `.jpg` is a PDF; a text file renamed `.pdf` is nothing. */
    @Test
    fun `the name and the claimed type do not matter, only the bytes`() {
        assertEquals(DocumentType.PDF, FileTypeDetector.detect("%PDF-1.4 rest".toByteArray()))
        assertNull(FileTypeDetector.detect("esto no es un pdf aunque se llame factura.pdf".toByteArray()))
    }

    /**
     * HEIC (iPhone photos) is not accepted: the recognition service cannot read
     * it (hueco 1 of the plan, research.md D-007). It is an ISO-BMFF box with
     * `ftyp` at offset 4 and a `heic`-family brand.
     */
    @Test
    fun `HEIC is refused`() {
        listOf("heic", "heix", "mif1", "heim").forEach { brand ->
            val heic = byteArrayOf(0, 0, 0, 0x18) + "ftyp$brand".toByteArray() + ByteArray(32)
            assertNull(FileTypeDetector.detect(heic), brand)
        }
    }

    @Test
    fun `empty, tiny and arbitrary files are refused`() {
        assertNull(FileTypeDetector.detect(ByteArray(0)))
        assertNull(FileTypeDetector.detect(byteArrayOf(0xFF.toByte())))
        assertNull(FileTypeDetector.detect("GIF89a".toByteArray() + ByteArray(32)), "GIF is not an invoice format here")
        assertNull(FileTypeDetector.detect(ByteArray(64) { 0x41 }))
    }
}
