package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role

/**
 * An access account as the business talks about it: no JPA, no HTTP.
 *
 * Carries neither the password hash nor the email by accident of omission - the
 * services that need them read the entity. What travels in the domain model is
 * what business rules reason about, and no rule in this feature reasons about
 * the hash itself.
 */
data class Account(
    val id: EntityId,
    val employeeId: EntityId,
    val email: String,
    val role: Role,
    val passwordChangeRequired: Boolean,
    val lockoutState: LockoutState
)
