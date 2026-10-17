package com.granatum.core.infrastructure.storage

import com.granatum.core.domain.exception.AlmacenImagenesNoDisponibleException
import com.granatum.core.domain.port.AlmacenImagenes

/**
 * No Storage configured (research.md D-010): uploads are rejected, there is
 * nothing to delete and nothing to sign. Removing an image or a material still
 * works; only the object, if one was ever stored, stays behind.
 */
class AlmacenImagenesDeshabilitado : AlmacenImagenes {

    override val disponible: Boolean = false

    override fun guardar(ruta: String, contenido: ByteArray, mediaType: String) =
        throw AlmacenImagenesNoDisponibleException()

    override fun borrar(rutas: Collection<String>) = Unit

    override fun firmar(rutas: Collection<String>): Map<String, String> = emptyMap()
}
