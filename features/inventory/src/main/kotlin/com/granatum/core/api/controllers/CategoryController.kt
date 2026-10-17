package com.granatum.core.api.controllers

import com.granatum.core.api.dto.CategoryDto
import com.granatum.core.api.dto.CreateCategoryRequest
import com.granatum.core.api.dto.UpdateCategoryRequest
import com.granatum.core.api.mappers.toDto
import com.granatum.core.service.CategoryService
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
@RequestMapping("/api/categories")
class CategoryController(
    private val categoryService: CategoryService
) {

    @GetMapping
    fun findAll(): List<CategoryDto> =
        categoryService.findAll().map { it.toDto() }

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): CategoryDto =
        categoryService.findById(id).toDto()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateCategoryRequest): CategoryDto =
        categoryService.create(request.name, request.description).toDto()

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateCategoryRequest): CategoryDto =
        categoryService.update(id, request.name, request.description).toDto()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        categoryService.delete(id)
    }
}
