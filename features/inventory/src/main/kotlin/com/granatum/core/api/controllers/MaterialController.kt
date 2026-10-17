package com.granatum.core.api.controllers

import com.granatum.core.api.dto.CreateMaterialRequest
import com.granatum.core.api.dto.HistorialMaterialDto
import com.granatum.core.api.dto.MaterialDto
import com.granatum.core.api.dto.UpdateCantidadRequest
import com.granatum.core.api.dto.UpdateMaterialRequest
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.mappers.toModel
import com.granatum.core.api.util.requestUserId
import com.granatum.core.domain.type.Almacen
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
@RequestMapping("/api/materiales")
class MaterialController(
    private val materialService: MaterialService
) {

    /** FR-010. A section only means something inside a warehouse, so it requires one. */
    @GetMapping
    fun findAll(
        @RequestParam(required = false) @Pattern(regexp = "[ABC]") almacen: String?,
        @RequestParam(required = false) @Min(1) @Max(5) seccion: Int?
    ): List<MaterialDto> {
        if (seccion != null && almacen == null) throw MissingServletRequestParameterException("almacen", "String")
        return materialService.findAll(almacen?.let(Almacen::valueOf), seccion).map { it.toDto() }
    }

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): MaterialDto =
        materialService.findById(id).toDto()

    // The `!!` on request fields are safe: @Valid has checked @NotNull on each.

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateMaterialRequest): MaterialDto =
        materialService.create(
            categoriaId = request.categoriaId!!,
            descripcion = request.descripcion!!,
            dimensiones = request.dimensiones!!.toModel(),
            cantidad = request.cantidad!!,
            localizacion = request.localizacion!!.toModel()
        ).toDto()

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateMaterialRequest): MaterialDto =
        materialService.update(
            id = id,
            categoriaId = request.categoriaId!!,
            descripcion = request.descripcion!!,
            dimensiones = request.dimensiones!!.toModel(),
            localizacion = request.localizacion!!.toModel()
        ).toDto()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        materialService.delete(id)
    }

    @GetMapping("/{id}/historial")
    fun findHistorial(@PathVariable id: UUID): List<HistorialMaterialDto> =
        materialService.findHistorial(id).map { it.toDto() }

    @PatchMapping("/{id}/cantidad")
    fun updateCantidad(@PathVariable id: UUID, @Valid @RequestBody request: UpdateCantidadRequest): MaterialDto =
        materialService.updateCantidad(
            id = id,
            usuarioId = requestUserId,
            cantidad = request.cantidad!!,
            motivo = request.motivo!!
        ).toDto()

    /**
     * FR-013: one file in the `imagen` part. Its type is decided by its bytes
     * in the service, never by its name or the part's Content-Type.
     */
    @PutMapping("/{id}/imagen", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun subirImagen(@PathVariable id: UUID, @RequestPart("imagen") imagen: MultipartFile): MaterialDto =
        materialService.subirImagen(id, imagen.bytes).toDto()

    /** FR-016. Idempotent. */
    @DeleteMapping("/{id}/imagen")
    fun quitarImagen(@PathVariable id: UUID): MaterialDto =
        materialService.quitarImagen(id).toDto()
}
