package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.EmployeeModel
import com.granatum.core.infrastructure.database.entities.EmployeeEntity

fun EmployeeEntity.toModel(): EmployeeModel = EmployeeModel(
    id = id,
    name = name,
    identityDocument = identityDocument,
    position = position,
    contractType = contractType,
    hireDate = hireDate,
    active = active
)
