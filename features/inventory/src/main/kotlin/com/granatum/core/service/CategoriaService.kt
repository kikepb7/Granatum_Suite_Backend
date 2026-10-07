package com.granatum.core.service

import com.granatum.core.domain.exception.CategoriaNotFoundException
import com.granatum.core.domain.model.CategoriaModel
import com.granatum.core.domain.type.EntityId
import com.granatum.core.infrastructure.database.entities.CategoriaEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.CategoriaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CategoriaService(
    private val categoriaRepository: CategoriaRepository
) {

    fun findAll(): List<CategoriaModel> =
        categoriaRepository.findAll().map { it.toModel() }

    fun findById(id: EntityId): CategoriaModel =
        categoriaRepository.findById(id).orElseThrow { CategoriaNotFoundException(id) }.toModel()

    @Transactional
    fun create(nombre: String, descripcion: String?): CategoriaModel =
        categoriaRepository.save(CategoriaEntity(nombre = nombre, descripcion = descripcion)).toModel()

    @Transactional
    fun update(id: EntityId, nombre: String, descripcion: String?): CategoriaModel {
        val entity = categoriaRepository.findById(id).orElseThrow { CategoriaNotFoundException(id) }
        entity.nombre = nombre
        entity.descripcion = descripcion
        return categoriaRepository.save(entity).toModel()
    }

    @Transactional
    fun delete(id: EntityId) {
        val entity = categoriaRepository.findById(id).orElseThrow { CategoriaNotFoundException(id) }
        categoriaRepository.delete(entity)
    }
}
