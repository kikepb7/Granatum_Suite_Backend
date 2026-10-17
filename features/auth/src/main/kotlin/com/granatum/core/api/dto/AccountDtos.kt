package com.granatum.core.api.dto

import com.granatum.core.domain.type.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Granting access: **one** operation (FR-029b). For whoever uses it this is
 * "give this person access", not the second half of a sign-up.
 */
data class AccountGrantRequest(
    @field:NotNull
    val employeeId: UUID? = null,

    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String = "",

    /**
     * Optional, defaulting to least privilege.
     *
     * This field was not in the spec: it surfaced while implementing US1,
     * because FR-005 requires the access token to carry a role and nothing
     * stored one. Defaulting to [Role.EMPLOYEE] means an `ADMIN` who forgets
     * the field creates an employee account and not another administrator.
     */
    val role: Role = Role.EMPLOYEE
) {
    /**
     * Never prints the secret fields. At DEBUG, Spring MVC logs every request
     * and response body through `toString()` ("Read application/json to [...]"),
     * and a `data class` prints every field by default - which put passwords in
     * clear into the log until `AuthNoPersonalDataInLogsIT` caught it.
     */
    override fun toString(): String =
        "AccountGrantRequest(employeeId=$employeeId, email=***, role=$role)"
}

/**
 * [temporaryPassword] appears here and **nowhere else, ever**: it is not stored
 * in clear, not logged, and cannot be read back. If it is lost, the way forward
 * is to reset.
 */
data class AccountGrantResponse(
    val accountId: UUID,
    val employeeId: UUID,
    val email: String,
    val role: Role,
    val temporaryPassword: String
) {
    /**
     * Never prints the secret fields. At DEBUG, Spring MVC logs every request
     * and response body through `toString()` ("Read application/json to [...]"),
     * and a `data class` prints every field by default - which put passwords in
     * clear into the log until `AuthNoPersonalDataInLogsIT` caught it.
     */
    override fun toString(): String =
        "AccountGrantResponse(accountId=$accountId, employeeId=$employeeId, email=***, " +
            "role=$role, temporaryPassword=***)"
}

/**
 * The account comes from the **token**, never from the body: constitution
 * principle IV forbids accepting a user identifier in the body or the query.
 */
data class ChangePasswordRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val currentPassword: String = "",

    @field:NotBlank
    @field:Size(max = 128)
    val newPassword: String = ""
) {
    /**
     * Never prints the secret fields. At DEBUG, Spring MVC logs every request
     * and response body through `toString()` ("Read application/json to [...]"),
     * and a `data class` prints every field by default - which put passwords in
     * clear into the log until `AuthNoPersonalDataInLogsIT` caught it.
     */
    override fun toString(): String =
        "ChangePasswordRequest(currentPassword=***, newPassword=***)"
}
