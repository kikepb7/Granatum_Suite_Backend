package com.granatum.core.ficheros

/**
 * The uploadable formats: what an invoice can come in (specs/004-invoices
 * FR-001, research.md D-007), of which a material's image accepts the three
 * image types (specs/010-inventory-materials-refactor).
 */
enum class TipoDocumento(val mediaType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    PDF("application/pdf", "pdf");

    companion object {
        fun deMediaType(mediaType: String): TipoDocumento = entries.single { it.mediaType == mediaType }
    }
}

/**
 * The type of a file from its first bytes. Never from its name or the
 * Content-Type the client sends: both are whatever the client says.
 *
 * HEIC (iPhone photos) is deliberately not recognised: the recognition service
 * does not read it, and converting it needs a native library (004, D-007).
 *
 * In `common` because invoices and inventory both need it, and two features
 * share code through `common`, never by copying it (constitution principle I;
 * specs/010-inventory-materials-refactor research.md D-004).
 */
object DetectorTipoFichero {

    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PDF = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    fun detectar(bytes: ByteArray): TipoDocumento? = when {
        empiezaPor(bytes, JPEG) -> TipoDocumento.JPEG
        empiezaPor(bytes, PNG) -> TipoDocumento.PNG
        empiezaPor(bytes, PDF) -> TipoDocumento.PDF
        empiezaPor(bytes, RIFF) && empiezaPor(bytes, WEBP, desde = 8) -> TipoDocumento.WEBP
        else -> null
    }

    private fun empiezaPor(bytes: ByteArray, firma: ByteArray, desde: Int = 0): Boolean =
        bytes.size >= desde + firma.size && firma.indices.all { bytes[desde + it] == firma[it] }
}
