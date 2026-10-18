package com.granatum.core.domain.model

import com.granatum.core.domain.type.ContractType
import java.time.LocalDate
import java.util.UUID

data class EmployeeModel(
    val id: UUID,
    val name: String,
    /** Null while pending: people registered before feature 012 (FR-003). */
    val lastName: String?,
    val identityDocument: String,
    val position: String,
    val contractType: ContractType,
    val hireDate: LocalDate,
    val active: Boolean
)
