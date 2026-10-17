package com.granatum.core.service

import com.granatum.core.domain.exception.CategoryNotFoundException
import com.granatum.core.domain.model.CategoryModel
import com.granatum.core.domain.type.EntityId
import com.granatum.core.infrastructure.database.entities.CategoryEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.CategoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CategoryService(
    private val categoryRepository: CategoryRepository
) {

    fun findAll(): List<CategoryModel> =
        categoryRepository.findAll().map { it.toModel() }

    fun findById(id: EntityId): CategoryModel =
        categoryRepository.findById(id).orElseThrow { CategoryNotFoundException(id) }.toModel()

    @Transactional
    fun create(name: String, description: String?): CategoryModel =
        categoryRepository.save(CategoryEntity(name = name, description = description)).toModel()

    @Transactional
    fun update(id: EntityId, name: String, description: String?): CategoryModel {
        val entity = categoryRepository.findById(id).orElseThrow { CategoryNotFoundException(id) }
        entity.name = name
        entity.description = description
        return categoryRepository.save(entity).toModel()
    }

    @Transactional
    fun delete(id: EntityId) {
        val entity = categoryRepository.findById(id).orElseThrow { CategoryNotFoundException(id) }
        categoryRepository.delete(entity)
    }
}
