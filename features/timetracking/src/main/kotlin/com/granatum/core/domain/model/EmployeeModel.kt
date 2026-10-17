package com.granatum.core.domain.model

import com.granatum.core.domain.type.ContractType
import java.time.LocalDate
import java.util.UUID

data class EmployeeModel(
    val id: UUID,
    val name: String,
    val identityDocument: String,
    val position: String,
    val contractType: ContractType,
    val hireDate: LocalDate,
    val active: Boolean
)
