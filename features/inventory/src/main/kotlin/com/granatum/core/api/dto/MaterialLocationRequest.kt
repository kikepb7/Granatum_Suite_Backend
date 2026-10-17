package com.granatum.core.api.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

/**
 * The warehouse arrives as text and not as the `Warehouse` enum on purpose
 * (research.md D-006): an unknown enum value fails JSON parsing, and that
 * error cannot name the field. Validated here, the `400 VALIDATION` says
 * `location.warehouse`.
 */
data class MaterialLocationRequest(
    @field:NotNull
    @field:Pattern(regexp = "[ABC]")
    val warehouse: String?,

    @field:NotNull
    @field:Min(1)
    @field:Max(5)
    val section: Int?
)
