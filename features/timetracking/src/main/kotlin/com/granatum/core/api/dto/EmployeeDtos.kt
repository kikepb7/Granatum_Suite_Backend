package com.granatum.core.api.dto

import com.granatum.core.api.validation.IdentityDocument
import com.granatum.core.domain.type.ContractType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.util.UUID

data class CreateEmployeeRequest(
    @field:NotBlank @field:Size(min = 1, max = 150) val name: String,
    @field:NotBlank @field:IdentityDocument val identityDocument: String,
    @field:NotBlank @field:Size(min = 1, max = 100) val position: String,
    @field:NotNull val contractType: ContractType,
    /** A civil date: an engagement is an administrative fact of a day, not an instant. */
    @field:NotNull val hireDate: LocalDate
)

/**
 * Carries neither `identityDocument` nor `active`, by design.
 *
 * Correcting someone's identity document is an exceptional case that deserves
 * its own audited operation, not a general PUT - and a request that includes it
 * is rejected rather than ignored, so a client never believes it changed
 * something it did not.
 *
 * `active` has its own endpoint so that deactivating someone is an explicit act
 * and not the side effect of editing their job title.
 */
data class UpdateEmployeeRequest(
    @field:NotBlank @field:Size(min = 1, max = 150) val name: String,
    @field:NotBlank @field:Size(min = 1, max = 100) val position: String,
    @field:NotNull val contractType: ContractType,
    @field:NotNull val hireDate: LocalDate,
    /**
     * Present only so an attempt to change it can be refused explicitly. Any
     * non-null value here produces a validation error.
     */
    val identityDocument: String? = null
)

data class SetEmployeeActiveRequest(
    @field:NotNull val active: Boolean
)

data class EmployeeDto(
    val id: UUID,
    val name: String,
    val identityDocument: String,
    val position: String,
    val contractType: ContractType,
    val hireDate: LocalDate,
    val active: Boolean
)
