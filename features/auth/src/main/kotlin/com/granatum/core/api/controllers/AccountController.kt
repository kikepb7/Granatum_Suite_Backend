package com.granatum.core.api.controllers

import com.granatum.core.api.dto.AccountGrantRequest
import com.granatum.core.api.dto.AccountGrantResponse
import com.granatum.core.service.AccountService
import com.granatum.core.service.OrphanDetectionService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Account administration. `ADMIN` only - the rule is declared in
 * `SecurityConfig`, which constitution principle IV makes the single place
 * where the route map lives.
 */
@RestController
@RequestMapping("/api/auth/accounts")
class AccountController(
    private val accountService: AccountService,
    private val orphanDetection: OrphanDetectionService
) {

    data class OrphansResponse(
        val total: Int,
        val accounts: List<OrphanDetectionService.OrphanAccount>
    )

    /**
     * Accounts whose person no longer exists (FR-029c). `ADMIN` only.
     *
     * No email in the response: the ids are enough to clean up, and the address
     * would be personal data in a diagnostic listing that has no use for it.
     */
    @GetMapping("/orphans")
    fun orphans(): OrphansResponse =
        orphanDetection.findOrphans().let { OrphansResponse(it.size, it) }

    /**
     * Grants access in one operation (FR-029b).
     *
     * **The temporary password is in this response and nowhere else, ever.** It
     * is not stored in clear, not written to any log, and cannot be read back;
     * if the `ADMIN` loses it, the way forward is to reset. That is why the
     * response carries it at all - there is no second chance to fetch it.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun grantAccess(@Valid @RequestBody request: AccountGrantRequest): AccountGrantResponse {
        val (account, temporaryPassword) = accountService.grantAccess(
            employeeId = request.employeeId!!,
            email = request.email,
            role = request.role
        )
        return AccountGrantResponse(
            accountId = account.id,
            employeeId = account.employeeId,
            email = account.email,
            role = account.role,
            temporaryPassword = temporaryPassword
        )
    }

    /**
     * Resets somebody's password (FR-024 to FR-026): new temporary password,
     * every session closed, lockout lifted.
     *
     * Addressed by **empleado id** rather than account id: it is the identifier
     * the `ADMIN` already has on the person's record, and it saves a lookup
     * whose only purpose would be to translate one id into another.
     *
     * `200` and not `201`: nothing is created, an existing credential is
     * replaced. Same response body as granting access, because the `ADMIN`
     * needs exactly the same thing from it - the temporary password, once.
     */
    @PostMapping("/{employeeId}/reset")
    fun resetPassword(@PathVariable employeeId: UUID): AccountGrantResponse {
        val (account, temporaryPassword) = accountService.resetPassword(employeeId)
        return AccountGrantResponse(
            accountId = account.id,
            employeeId = account.employeeId,
            email = account.email,
            role = account.role,
            temporaryPassword = temporaryPassword
        )
    }
}
