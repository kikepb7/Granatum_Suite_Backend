package com.granatum.core.api.dto

import com.granatum.core.domain.type.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.util.UUID

/*
 * The owner's sign-up (feature 005 US1, feature 009) and onboarding by an ADMIN
 * (feature 009). Every request and response overrides toString: they carry a
 * password, a code, a name, an email or an identity document, and a data class
 * would print them in any log line or exception that mentions the object
 * (principle VI).
 */

data class SignUpRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String = "",

    /** Capped at 128 like LoginRequest: what stops a huge body becoming Argon2 work. */
    @field:NotNull
    @field:Size(max = 128)
    val password: String = "",

    @field:NotBlank
    @field:Size(max = 150)
    val name: String = "",

    @field:NotBlank
    @field:Size(max = 20)
    val identityDocument: String = "",

    /**
     * Required: only the business owner signs up, once, with the code set at
     * deployment (feature 009). Everyone else is onboarded by an ADMIN.
     */
    @field:NotBlank
    @field:Size(max = 200)
    val bootstrapCode: String = ""
) {
    override fun toString(): String = "SignUpRequest(bootstrapCode=***)"
}

data class SignUpResponse(
    /** Always `ACTIVA`: the owner's account exists and can sign in. */
    val status: String,
    val message: String
)

/**
 * Onboarding a person in one step (feature 009): their staff record and their
 * account. `position`, `contractType` and `hireDate` are the staff record's; if a
 * record with this document already exists, they are ignored and the account
 * is linked to it.
 */
data class OnboardingRequest(
    @field:NotBlank
    @field:Size(max = 150)
    val name: String = "",

    @field:NotBlank
    @field:Size(max = 20)
    val identityDocument: String = "",

    @field:NotBlank
    @field:Size(max = 100)
    val position: String = "",

    @field:NotBlank
    @field:Pattern(regexp = "FULL_TIME|PART_TIME|HOURLY")
    val contractType: String = "",

    @field:NotNull
    val hireDate: LocalDate? = null,

    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String = "",

    @field:NotNull
    val role: Role? = null
) {
    override fun toString(): String = "OnboardingRequest(contractType=$contractType, role=$role)"
}

data class OnboardingResponse(
    val employeeId: UUID,
    val accountId: UUID,
    val email: String,
    val role: Role,
    /** False when the account was linked to an existing staff record with this document. */
    val staffRecordCreated: Boolean,
    /**
     * The temporary password, **only here and only once**: it is not stored in
     * clear and cannot be read back. The ADMIN hands it to the person, who must
     * change it on first sign-in. If it is lost, reset it.
     */
    val temporaryPassword: String
) {
    override fun toString(): String =
        "OnboardingResponse(employeeId=$employeeId, accountId=$accountId, role=$role, staffRecordCreated=$staffRecordCreated, " +
            "email=***, temporaryPassword=***)"
}
