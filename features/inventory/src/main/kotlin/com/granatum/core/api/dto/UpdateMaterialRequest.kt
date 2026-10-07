package com.granatum.core.api.dto

import com.granatum.core.domain.type.EstadoMaterial
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

/**
 * Full update of a material's descriptive/catalog attributes.
 * `cantidadDisponible` is intentionally excluded - it can only change through
 * `PATCH /api/materiales/{id}/cantidad`, which enforces a mandatory `motivo`
 * and records the change in the historial.
 */
data class UpdateMaterialRequest(
    @field:NotBlank
    @field:Size(max = 140)
    val nombre: String,

    @field:NotNull
    val categoriaId: UUID,

    @field:Min(0)
    val cantidadTotal: Int,

    @field:Valid
    @field:NotNull
    val tamano: TamanoRequest,

    @field:NotBlank
    val color: String,

    @field:NotBlank
    val materialFisico: String,

    @field:NotNull
    val estado: EstadoMaterial,

    @field:NotBlank
    val ubicacion: String,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = true)
    val precioUnitario: BigDecimal,

    @field:NotBlank
    val proveedor: String,

    val fotos: List<String> = emptyList()
)
