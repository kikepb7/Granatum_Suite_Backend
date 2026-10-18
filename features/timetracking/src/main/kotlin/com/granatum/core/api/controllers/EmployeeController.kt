package com.granatum.core.api.controllers

import com.granatum.core.api.dto.UpdateEmployeeRequest
import com.granatum.core.api.dto.SetEmployeeActiveRequest
import com.granatum.core.api.dto.CreateEmployeeRequest
import com.granatum.core.api.dto.EmployeeDto
import com.granatum.core.api.mappers.toDto
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.service.EmployeeService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Staff management. **ADMIN only**, enforced at the route in `SecurityConfig`
 * (FR-027).
 *
 * **There is deliberately no `DELETE`.** Removing a person would destroy the
 * working-time history that must be kept for four years; the exit is
 * `PATCH /{id}/active`. This is a decision about the contract, not an omission,
 * and it must not be "completed" later.
 */
@RestController
@RequestMapping("/api/employees")
class EmployeeController(
    private val employeeService: EmployeeService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateEmployeeRequest): EmployeeDto =
        employeeService.create(
            name = request.name,
            lastName = request.lastName,
            identityDocument = request.identityDocument,
            position = request.position,
            contractType = request.contractType,
            hireDate = request.hireDate
        ).toDto()

    @GetMapping
    fun findAll(@RequestParam(required = false) active: Boolean?): List<EmployeeDto> =
        employeeService.findAll(active).map { it.toDto() }

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): EmployeeDto =
        employeeService.findById(id).toDto()

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateEmployeeRequest
    ): EmployeeDto {
        // Refused rather than ignored. Ignoring it would return 200 to a client
        // convinced the change took effect, and the divergence would not
        // surface until much later.
        if (request.identityDocument != null) {
            throw InconsistentValuesException(
                "el documento de identidad no se modifica por esta via"
            )
        }

        return employeeService.update(
            id = id,
            name = request.name,
            lastName = request.lastName,
            position = request.position,
            contractType = request.contractType,
            hireDate = request.hireDate
        ).toDto()
    }

    /** Its own endpoint so an exit is an explicit act, not a side effect of an edit. */
    @PatchMapping("/{id}/active")
    fun setActive(
        @PathVariable id: UUID,
        @Valid @RequestBody request: SetEmployeeActiveRequest
    ): EmployeeDto =
        employeeService.setActive(id, request.active).toDto()
}
