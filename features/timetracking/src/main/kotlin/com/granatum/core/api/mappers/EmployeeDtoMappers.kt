package com.granatum.core.api.mappers

import com.granatum.core.api.dto.EmployeeDto
import com.granatum.core.domain.model.EmployeeModel

/**
 * Model -> Dto.
 *
 * This is the one DTO that carries the identity document, because the resource
 * is the person's own record and an ADMIN managing staff needs to see it. It
 * must not leak into fichaje responses, where it has no business being.
 */
fun EmployeeModel.toDto(): EmployeeDto = EmployeeDto(
    id = id,
    name = name,
    identityDocument = identityDocument,
    position = position,
    contractType = contractType,
    hireDate = hireDate,
    active = active
)
