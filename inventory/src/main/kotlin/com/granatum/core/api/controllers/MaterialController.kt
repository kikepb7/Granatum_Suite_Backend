package com.granatum.core.api.controllers

import com.granatum.core.api.dto.CreateMaterialRequest
import com.granatum.core.api.dto.HistorialMaterialDto
import com.granatum.core.api.dto.MaterialDto
import com.granatum.core.api.dto.UpdateCantidadRequest
import com.granatum.core.api.dto.UpdateMaterialRequest
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.MaterialService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/materiales")
class MaterialController(
    private val materialService: MaterialService
) {

    @GetMapping
    fun findAll(): List<MaterialDto> =
        materialService.findAll().map { it.toDto() }

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): MaterialDto =
        materialService.findById(id).toDto()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateMaterialRequest): MaterialDto =
        materialService.create(
            nombre = request.nombre,
            categoriaId = request.categoriaId,
            cantidadDisponible = request.cantidadDisponible,
            cantidadTotal = request.cantidadTotal,
            alto = request.tamano.alto,
            ancho = request.tamano.ancho,
            diametro = request.tamano.diametro,
            unidadMedida = request.tamano.unidadMedida,
            color = request.color,
            materialFisico = request.materialFisico,
            estado = request.estado,
            ubicacion = request.ubicacion,
            precioUnitario = request.precioUnitario,
            proveedor = request.proveedor,
            fotos = request.fotos
        ).toDto()

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateMaterialRequest): MaterialDto =
        materialService.update(
            id = id,
            nombre = request.nombre,
            categoriaId = request.categoriaId,
            cantidadTotal = request.cantidadTotal,
            alto = request.tamano.alto,
            ancho = request.tamano.ancho,
            diametro = request.tamano.diametro,
            unidadMedida = request.tamano.unidadMedida,
            color = request.color,
            materialFisico = request.materialFisico,
            estado = request.estado,
            ubicacion = request.ubicacion,
            precioUnitario = request.precioUnitario,
            proveedor = request.proveedor,
            fotos = request.fotos
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
            cantidadDisponible = request.cantidadDisponible,
            motivo = request.motivo
        ).toDto()
}
