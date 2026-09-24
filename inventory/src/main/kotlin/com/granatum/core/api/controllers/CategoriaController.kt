package com.granatum.core.api.controllers

import com.granatum.core.api.dto.CategoriaDto
import com.granatum.core.api.dto.CreateCategoriaRequest
import com.granatum.core.api.dto.UpdateCategoriaRequest
import com.granatum.core.api.mappers.toDto
import com.granatum.core.service.CategoriaService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/categorias")
class CategoriaController(
    private val categoriaService: CategoriaService
) {

    @GetMapping
    fun findAll(): List<CategoriaDto> =
        categoriaService.findAll().map { it.toDto() }

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): CategoriaDto =
        categoriaService.findById(id).toDto()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateCategoriaRequest): CategoriaDto =
        categoriaService.create(request.nombre, request.descripcion).toDto()

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateCategoriaRequest): CategoriaDto =
        categoriaService.update(id, request.nombre, request.descripcion).toDto()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        categoriaService.delete(id)
    }
}
