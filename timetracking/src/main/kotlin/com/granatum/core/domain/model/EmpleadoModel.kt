package com.granatum.core.domain.model

import com.granatum.core.domain.type.TipoContrato
import java.time.LocalDate
import java.util.UUID

data class EmpleadoModel(
    val id: UUID,
    val nombre: String,
    val documentoIdentidad: String,
    val puesto: String,
    val tipoContrato: TipoContrato,
    val fechaAlta: LocalDate,
    val activo: Boolean
)
