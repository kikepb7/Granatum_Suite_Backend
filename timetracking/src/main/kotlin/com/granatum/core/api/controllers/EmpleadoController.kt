package com.granatum.core.api.controllers

import com.granatum.core.api.dto.ActualizarEmpleadoRequest
import com.granatum.core.api.dto.CambiarActivoRequest
import com.granatum.core.api.dto.CrearEmpleadoRequest
import com.granatum.core.api.dto.EmpleadoDto
import com.granatum.core.api.mappers.toDto
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.service.EmpleadoService
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
 * `PATCH /{id}/activo`. This is a decision about the contract, not an omission,
 * and it must not be "completed" later.
 */
@RestController
@RequestMapping("/api/empleados")
class EmpleadoController(
    private val empleadoService: EmpleadoService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(@Valid @RequestBody request: CrearEmpleadoRequest): EmpleadoDto =
        empleadoService.crear(
            nombre = request.nombre,
            documentoIdentidad = request.documentoIdentidad,
            puesto = request.puesto,
            tipoContrato = request.tipoContrato,
            fechaAlta = request.fechaAlta
        ).toDto()

    @GetMapping
    fun findAll(@RequestParam(required = false) activo: Boolean?): List<EmpleadoDto> =
        empleadoService.findAll(activo).map { it.toDto() }

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): EmpleadoDto =
        empleadoService.findById(id).toDto()

    @PutMapping("/{id}")
    fun actualizar(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ActualizarEmpleadoRequest
    ): EmpleadoDto {
        // Refused rather than ignored. Ignoring it would return 200 to a client
        // convinced the change took effect, and the divergence would not
        // surface until much later.
        if (request.documentoIdentidad != null) {
            throw ValoresIncoherentesException(
                "el documento de identidad no se modifica por esta via"
            )
        }

        return empleadoService.actualizar(
            id = id,
            nombre = request.nombre,
            puesto = request.puesto,
            tipoContrato = request.tipoContrato,
            fechaAlta = request.fechaAlta
        ).toDto()
    }

    /** Its own endpoint so an exit is an explicit act, not a side effect of an edit. */
    @PatchMapping("/{id}/activo")
    fun cambiarActivo(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CambiarActivoRequest
    ): EmpleadoDto =
        empleadoService.cambiarActivo(id, request.activo).toDto()
}
