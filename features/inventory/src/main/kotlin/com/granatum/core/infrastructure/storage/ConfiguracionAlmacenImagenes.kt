package com.granatum.core.infrastructure.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Where the image storage settings come from - and the only place the
 * Supabase service key comes from (research.md D-002).
 *
 * Both the URL and the key default to empty, and either one empty means there
 * is no Storage: the application starts and only uploads are rejected
 * (research.md D-010). The key gives full access to the Supabase project, so
 * it is never logged and [toString] leaves it out (principle VI).
 */
@Component
class ConfiguracionAlmacenImagenes(
    @param:Value("\${inventory.imagenes.supabase-url:}") val supabaseUrl: String,
    @param:Value("\${inventory.imagenes.service-key:}") private val serviceKey: String,
    @param:Value("\${inventory.imagenes.bucket:materiales}") val bucket: String,
    /** How long a signed URL works (FR-018). */
    @param:Value("\${inventory.imagenes.url-segundos:3600}") val urlSegundos: Long,
    /** Per-file limit (FR-014), enforced by the service, not by Spring's multipart limit. */
    @param:Value("\${inventory.imagenes.max-bytes:10485760}") val maxBytes: Long,
    @param:Value("\${inventory.imagenes.timeout-segundos:30}") val timeoutSegundos: Long = 30
) {
    val activo: Boolean get() = supabaseUrl.isNotBlank() && serviceKey.isNotBlank()

    /** The key, for the one place that builds the client. Never logged. */
    fun clave(): String {
        check(activo) { "sin almacenamiento de imagenes configurado" }
        return serviceKey
    }

    override fun toString(): String = "ConfiguracionAlmacenImagenes(activo=$activo, bucket=$bucket)"
}
