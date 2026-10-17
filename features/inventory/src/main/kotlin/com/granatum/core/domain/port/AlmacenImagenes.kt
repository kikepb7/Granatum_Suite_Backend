package com.granatum.core.domain.port

/**
 * Where materials' images live (research.md D-001): a private Supabase Storage
 * bucket, or nothing at all when the installation has no Storage configured
 * (D-010). Paths only ever contain UUIDs (D-009).
 */
interface AlmacenImagenes {

    /** False when there is nowhere to upload to; uploads are then rejected with `503`. */
    val disponible: Boolean

    /** Stores [contenido] at [ruta]. Never overwrites: every upload has a new path. */
    fun guardar(ruta: String, contenido: ByteArray, mediaType: String)

    fun borrar(rutas: Collection<String>)

    /**
     * Temporary URLs for [rutas], in one round trip. A path that could not be
     * signed is absent from the result rather than failing the others.
     */
    fun firmar(rutas: Collection<String>): Map<String, String>
}
