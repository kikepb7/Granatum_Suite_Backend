package com.granatum.core.domain.port

/**
 * Where materials' images live (research.md D-001): a private Supabase Storage
 * bucket, or nothing at all when the installation has no Storage configured
 * (D-010). Paths only ever contain UUIDs (D-009).
 */
interface MaterialImageStorage {

    /** False when there is nowhere to upload to; uploads are then rejected with `503`. */
    val available: Boolean

    /** Stores [content] at [path]. Never overwrites: every upload has a new path. */
    fun store(path: String, content: ByteArray, mediaType: String)

    fun delete(paths: Collection<String>)

    /**
     * Temporary URLs for [paths], in one round trip. A path that could not be
     * signed is absent from the result rather than failing the others.
     */
    fun sign(paths: Collection<String>): Map<String, String>
}
