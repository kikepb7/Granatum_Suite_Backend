package com.granatum.core.api.controllers

import com.granatum.core.api.dto.OnboardingRequest
import com.granatum.core.api.dto.OnboardingResponse
import com.granatum.core.service.AccountService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Onboarding a person (feature 009): `ADMIN` only - the rule is in
 * `SecurityConfig`. Staff record and account in one call; the response carries
 * the temporary password the ADMIN hands to the person, and nothing else will
 * ever show it again.
 */
@RestController
@RequestMapping("/api/auth/onboarding")
class OnboardingController(private val accountService: AccountService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun onboard(@Valid @RequestBody r: OnboardingRequest): OnboardingResponse {
        val onboarding = accountService.onboard(
            AccountService.OnboardingData(
                name = r.name,
                lastName = r.lastName,
                identityDocument = r.identityDocument,
                position = r.position,
                contractType = r.contractType,
                hireDate = r.hireDate!!,
                email = r.email,
                role = r.role!!
            )
        )
        return OnboardingResponse(
            employeeId = onboarding.account.employeeId,
            accountId = onboarding.account.id,
            email = onboarding.account.email,
            role = onboarding.account.role,
            staffRecordCreated = onboarding.staffRecordCreated,
            temporaryPassword = onboarding.temporaryPassword
        )
    }
}
