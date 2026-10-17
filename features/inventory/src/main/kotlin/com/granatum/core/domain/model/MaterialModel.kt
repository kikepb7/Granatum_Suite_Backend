package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import java.time.Instant

data class MaterialModel(
    val id: EntityId,
    val categoria: CategoriaModel,
    val descripcion: String,
    val dimensiones: DimensionesModel,
    val cantidad: Int,
    val localizacion: Localizacion,
    /** Path of the image in the bucket; never leaves through HTTP. */
    val imagenRuta: String?,
    /**
     * Temporary signed URL for the image, filled in by the service when it
     * reads (research.md D-005). Null without an image, or when it could not
     * be signed.
     */
    val imagenUrl: String? = null,
    val fechaAlta: Instant,
    val fechaUltimaModificacion: Instant
)
