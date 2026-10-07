package com.granatum.core.domain.port

import java.util.UUID

/** An original file, exactly as uploaded. */
class DocumentoGuardado(val contenido: ByteArray, val mediaType: String) {
    override fun toString(): String = "DocumentoGuardado(mediaType=$mediaType, bytes=${contenido.size})"
}

/**
 * Where originals live (research.md D-008). Postgres today; an interface so
 * that moving them to object storage (Supabase Storage, if the database plan is
 * too small) is a new implementation, not a change to the services.
 */
interface AlmacenDocumentos {
    fun guardar(facturaId: UUID, contenido: ByteArray, mediaType: String, nombreOriginal: String?)
    fun leer(facturaId: UUID): DocumentoGuardado
}
