package com.granatum.core.api.controllers

import com.granatum.core.api.dto.CompanyDto
import com.granatum.core.api.dto.CompanyRequest
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.CompanyData
import com.granatum.core.service.CompanyService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `ADMIN` only, by the `/api/invoicing` rule in `SecurityConfig`. */
@RestController
@RequestMapping("/api/invoicing/company")
class CompanyController(private val company: CompanyService) {

    @GetMapping
    fun get(): CompanyDto = company.get().toDto()

    @PutMapping
    fun save(@Valid @RequestBody request: CompanyRequest): CompanyDto =
        company.save(request.legalName, request.nif, requestUserId).toDto()

    private fun CompanyData.toDto() = CompanyDto(legalName, nif, recognitionActive)
}
