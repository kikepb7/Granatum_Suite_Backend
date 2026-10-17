package com.granatum.core.files

/**
 * The uploadable formats: what an invoice can come in (specs/004-invoices
 * FR-001, research.md D-007), of which a material's image accepts the three
 * image types (specs/010-inventory-materials-refactor).
 */
enum class DocumentType(val mediaType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    PDF("application/pdf", "pdf");

    companion object {
        fun fromMediaType(mediaType: String): DocumentType = entries.single { it.mediaType == mediaType }
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
object FileTypeDetector {

    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PDF = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    fun detect(bytes: ByteArray): DocumentType? = when {
        startsWith(bytes, JPEG) -> DocumentType.JPEG
        startsWith(bytes, PNG) -> DocumentType.PNG
        startsWith(bytes, PDF) -> DocumentType.PDF
        startsWith(bytes, RIFF) && startsWith(bytes, WEBP, offset = 8) -> DocumentType.WEBP
        else -> null
    }

    private fun startsWith(bytes: ByteArray, signature: ByteArray, offset: Int = 0): Boolean =
        bytes.size >= offset + signature.size && signature.indices.all { bytes[offset + it] == signature[it] }
}
