package com.granatum.core.api.controllers

import com.granatum.core.api.dto.CreateMaterialRequest
import com.granatum.core.api.dto.MaterialDto
import com.granatum.core.api.dto.MaterialHistoryDto
import com.granatum.core.api.dto.UpdateMaterialQuantityRequest
import com.granatum.core.api.dto.UpdateMaterialRequest
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.mappers.toModel
import com.granatum.core.api.util.requestUserId
import com.granatum.core.domain.type.Warehouse
import com.granatum.core.service.MaterialService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/materials")
class MaterialController(
    private val materialService: MaterialService
) {

    /** FR-010. A section only means something inside a warehouse, so it requires one. */
    @GetMapping
    fun findAll(
        @RequestParam(required = false) @Pattern(regexp = "[ABC]") warehouse: String?,
        @RequestParam(required = false) @Min(1) @Max(5) section: Int?
    ): List<MaterialDto> {
        if (section != null && warehouse == null) throw MissingServletRequestParameterException("warehouse", "String")
        return materialService.findAll(warehouse?.let(Warehouse::valueOf), section).map { it.toDto() }
    }

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): MaterialDto =
        materialService.findById(id).toDto()

    // The `!!` on request fields are safe: @Valid has checked @NotNull on each.

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateMaterialRequest): MaterialDto =
        materialService.create(
            categoryId = request.categoryId!!,
            description = request.description!!,
            dimensions = request.dimensions!!.toModel(),
            quantity = request.quantity!!,
            location = request.location!!.toModel()
        ).toDto()

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateMaterialRequest): MaterialDto =
        materialService.update(
            id = id,
            categoryId = request.categoryId!!,
            description = request.description!!,
            dimensions = request.dimensions!!.toModel(),
            location = request.location!!.toModel()
        ).toDto()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        materialService.delete(id)
    }

    @GetMapping("/{id}/history")
    fun findHistory(@PathVariable id: UUID): List<MaterialHistoryDto> =
        materialService.findHistory(id).map { it.toDto() }

    @PatchMapping("/{id}/quantity")
    fun updateQuantity(@PathVariable id: UUID, @Valid @RequestBody request: UpdateMaterialQuantityRequest): MaterialDto =
        materialService.updateQuantity(
            id = id,
            userId = requestUserId,
            quantity = request.quantity!!,
            reason = request.reason!!
        ).toDto()

    /**
     * FR-013: one file in the `image` part. Its type is decided by its bytes
     * in the service, never by its name or the part's Content-Type.
     */
    @PutMapping("/{id}/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadImage(@PathVariable id: UUID, @RequestPart("image") image: MultipartFile): MaterialDto =
        materialService.uploadImage(id, image.bytes).toDto()

    /** FR-016. Idempotent. */
    @DeleteMapping("/{id}/image")
    fun removeImage(@PathVariable id: UUID): MaterialDto =
        materialService.removeImage(id).toDto()
}
