package com.granatum.core.api.controllers

import com.granatum.core.api.dto.AvailableMaterialDto
import com.granatum.core.api.dto.EventDto
import com.granatum.core.api.dto.EventMaterialQuantityRequest
import com.granatum.core.api.dto.EventRequest
import com.granatum.core.api.mappers.toAvailableDto
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.util.requestUserId
import com.granatum.core.domain.model.EventData
import com.granatum.core.domain.type.EventStatus
import com.granatum.core.service.EventService
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
@RequestMapping("/api/events")
class EventController(private val eventService: EventService) {

    @GetMapping
    fun list(@RequestParam(required = false) status: EventStatus?): List<EventDto> =
        eventService.list(status).map { it.toDto() }

    /** Declared before `/{id}` reads better; Spring prefers the literal path either way. */
    @GetMapping("/materials")
    fun availableMaterials(): List<AvailableMaterialDto> =
        eventService.availableMaterials().map { it.toAvailableDto() }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): EventDto = eventService.get(id).toDto()

    // The `!!` on request fields are safe: @Valid has checked @NotNull/@NotBlank on each.

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: EventRequest): EventDto =
        eventService.create(requestUserId, request.toData()).toDto()

    @PutMapping("/{id}")
    fun edit(@PathVariable id: UUID, @Valid @RequestBody request: EventRequest): EventDto =
        eventService.edit(id, request.toData()).toDto()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = eventService.delete(id, requestUserId)

    /** Idempotent: finishing a finished event returns it unchanged (FR-008). */
    @PostMapping("/{id}/finish")
    fun finish(@PathVariable id: UUID): EventDto = eventService.finish(id, requestUserId).toDto()

    @PutMapping("/{id}/materials/{materialId}")
    fun assignMaterial(
        @PathVariable id: UUID,
        @PathVariable materialId: UUID,
        @Valid @RequestBody request: EventMaterialQuantityRequest
    ): EventDto = eventService.assignMaterial(id, materialId, request.quantity!!, requestUserId).toDto()

    /** Idempotent: removing a material the event does not take changes nothing. */
    @DeleteMapping("/{id}/materials/{materialId}")
    fun removeMaterial(@PathVariable id: UUID, @PathVariable materialId: UUID): EventDto =
        eventService.removeMaterial(id, materialId, requestUserId).toDto()

    private fun EventRequest.toData() = EventData(name = name!!, description = description, date = date!!)
}
