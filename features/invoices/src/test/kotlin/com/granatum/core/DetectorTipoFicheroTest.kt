package com.granatum.core

import com.granatum.core.infrastructure.documentos.DetectorTipoFichero
import com.granatum.core.infrastructure.documentos.TipoDocumento
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The type of an uploaded file comes from its first bytes, never from its name
 * or the Content-Type the client sends (research.md D-007).
 */
class DetectorTipoFicheroTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() } + ByteArray(32)

    @Test
    fun `JPEG, PNG, WebP and PDF are recognised by their signature`() {
        assertEquals(TipoDocumento.JPEG, DetectorTipoFichero.detectar(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(TipoDocumento.PNG, DetectorTipoFichero.detectar(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(TipoDocumento.WEBP, DetectorTipoFichero.detectar("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(TipoDocumento.PDF, DetectorTipoFichero.detectar("%PDF-1.7\n".toByteArray()))
    }

    @Test
    fun `the media type and extension follow the detected type`() {
        assertEquals("application/pdf", TipoDocumento.PDF.mediaType)
        assertEquals("jpg", TipoDocumento.JPEG.extension)
        assertEquals("image/webp", TipoDocumento.WEBP.mediaType)
    }

    /** A PDF renamed `.jpg` is a PDF; a text file renamed `.pdf` is nothing. */
    @Test
    fun `the name and the claimed type do not matter, only the bytes`() {
        assertEquals(TipoDocumento.PDF, DetectorTipoFichero.detectar("%PDF-1.4 rest".toByteArray()))
        assertNull(DetectorTipoFichero.detectar("esto no es un pdf aunque se llame factura.pdf".toByteArray()))
    }

    /**
     * HEIC (iPhone photos) is not accepted: the recognition service cannot read
     * it (hueco 1 of the plan, research.md D-007). It is an ISO-BMFF box with
     * `ftyp` at offset 4 and a `heic`-family brand.
     */
    @Test
    fun `HEIC is refused`() {
        listOf("heic", "heix", "mif1", "heim").forEach { marca ->
            val heic = byteArrayOf(0, 0, 0, 0x18) + "ftyp$marca".toByteArray() + ByteArray(32)
            assertNull(DetectorTipoFichero.detectar(heic), marca)
        }
    }

    @Test
    fun `empty, tiny and arbitrary files are refused`() {
        assertNull(DetectorTipoFichero.detectar(ByteArray(0)))
        assertNull(DetectorTipoFichero.detectar(byteArrayOf(0xFF.toByte())))
        assertNull(DetectorTipoFichero.detectar("GIF89a".toByteArray() + ByteArray(32)), "GIF is not an invoice format here")
        assertNull(DetectorTipoFichero.detectar(ByteArray(64) { 0x41 }))
    }
}
