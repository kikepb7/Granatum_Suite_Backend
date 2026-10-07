package com.granatum.core.api.dto

import com.granatum.core.domain.type.TipoContrato
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ResumenMensualDto(
    val empleadoId: UUID,
    val anio: Int,
    val mes: Int,
    val tipoContrato: TipoContrato,
    val totalMinutosTrabajados: Int,
    val dias: List<DiaResumenDto>
)

data class DiaResumenDto(
    val fecha: LocalDate,
    val entrada: Instant,
    val salida: Instant?,
    val minutosTrabajados: Int?,
    val minutosPausa: Int,
    val reconstruido: Boolean,
    val corregido: Boolean
)
