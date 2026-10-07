package com.granatum.core.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class EmpresaRequest(
    @field:NotBlank @field:Size(max = 200) val razonSocial: String,
    @field:NotBlank @field:Size(max = 20) val nif: String
) {
    override fun toString(): String = "EmpresaRequest(***)"
}

data class EmpresaDto(
    val razonSocial: String,
    val nif: String,
    val reconocimientoActivo: Boolean
) {
    override fun toString(): String = "EmpresaDto(reconocimientoActivo=$reconocimientoActivo)"
}
