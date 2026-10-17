package com.granatum.core.api.controllers

import com.granatum.core.api.dto.CantidadMaterialEventoRequest
import com.granatum.core.api.dto.EventoDto
import com.granatum.core.api.dto.EventoRequest
import com.granatum.core.api.dto.MaterialDisponibleDto
import com.granatum.core.api.mappers.toDisponibleDto
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.util.requestUserId
import com.granatum.core.domain.model.DatosEvento
import com.granatum.core.domain.type.EstadoEvento
import com.granatum.core.service.EventoService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Events and their materials (feature 011, specs/011-events/contracts/README.md). */
@RestController
@RequestMapping("/api/eventos")
class EventoController(private val eventoService: EventoService) {

    @GetMapping
    fun listar(@RequestParam(required = false) estado: EstadoEvento?): List<EventoDto> =
        eventoService.listar(estado).map { it.toDto() }

    /** Declared before `/{id}` reads better; Spring prefers the literal path either way. */
    @GetMapping("/materiales")
    fun materialesDisponibles(): List<MaterialDisponibleDto> =
        eventoService.materialesDisponibles().map { it.toDisponibleDto() }

    @GetMapping("/{id}")
    fun obtener(@PathVariable id: UUID): EventoDto = eventoService.obtener(id).toDto()

    // The `!!` on request fields are safe: @Valid has checked @NotNull/@NotBlank on each.

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun crear(@Valid @RequestBody request: EventoRequest): EventoDto =
        eventoService.crear(requestUserId, request.toDatos()).toDto()

    @PutMapping("/{id}")
    fun editar(@PathVariable id: UUID, @Valid @RequestBody request: EventoRequest): EventoDto =
        eventoService.editar(id, request.toDatos()).toDto()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun borrar(@PathVariable id: UUID) = eventoService.borrar(id, requestUserId)

    /** Idempotent: finishing a finished event returns it unchanged (FR-008). */
    @PostMapping("/{id}/finalizar")
    fun finalizar(@PathVariable id: UUID): EventoDto = eventoService.finalizar(id, requestUserId).toDto()

    @PutMapping("/{id}/materiales/{materialId}")
    fun asignarMaterial(
        @PathVariable id: UUID,
        @PathVariable materialId: UUID,
        @Valid @RequestBody request: CantidadMaterialEventoRequest
    ): EventoDto = eventoService.asignarMaterial(id, materialId, request.cantidad!!, requestUserId).toDto()

    /** Idempotent: removing a material the event does not take changes nothing. */
    @DeleteMapping("/{id}/materiales/{materialId}")
    fun quitarMaterial(@PathVariable id: UUID, @PathVariable materialId: UUID): EventoDto =
        eventoService.quitarMaterial(id, materialId, requestUserId).toDto()

    private fun EventoRequest.toDatos() = DatosEvento(nombre = nombre!!, descripcion = descripcion, fecha = fecha!!)
}
