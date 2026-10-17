package com.granatum.core.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CompanyRequest(
    @field:NotBlank @field:Size(max = 200) val legalName: String,
    @field:NotBlank @field:Size(max = 20) val nif: String
) {
    override fun toString(): String = "CompanyRequest(***)"
}

data class CompanyDto(
    val legalName: String,
    val nif: String,
    val recognitionActive: Boolean
) {
    override fun toString(): String = "CompanyDto(recognitionActive=$recognitionActive)"
}
