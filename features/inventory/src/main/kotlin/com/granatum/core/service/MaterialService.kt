package com.granatum.core.service

import com.granatum.core.domain.exception.CantidadInvalidaException
import com.granatum.core.domain.exception.CategoriaNotFoundException
import com.granatum.core.domain.exception.MaterialNotFoundException
import com.granatum.core.domain.model.HistorialMaterialModel
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoMaterial
import com.granatum.core.domain.type.TipoCambioHistorial
import com.granatum.core.domain.type.UnidadMedida
import com.granatum.core.infrastructure.database.entities.HistorialMaterialEntity
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.entities.TamanoEmbeddable
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.CategoriaRepository
import com.granatum.core.infrastructure.database.repositories.HistorialMaterialRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class MaterialService(
    private val materialRepository: MaterialRepository,
    private val categoriaRepository: CategoriaRepository,
    private val historialMaterialRepository: HistorialMaterialRepository
) {

    fun findAll(): List<MaterialModel> =
        materialRepository.findAll().map { it.toModel() }

    fun findById(id: EntityId): MaterialModel =
        materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }.toModel()

    @Transactional
    fun create(
        nombre: String,
        categoriaId: EntityId,
        cantidadDisponible: Int,
        cantidadTotal: Int,
        alto: BigDecimal,
        ancho: BigDecimal,
        diametro: BigDecimal?,
        unidadMedida: UnidadMedida,
        color: String,
        materialFisico: String,
        estado: EstadoMaterial,
        ubicacion: String,
        precioUnitario: BigDecimal,
        proveedor: String,
        fotos: List<String>
    ): MaterialModel {
        val categoria = categoriaRepository.findById(categoriaId).orElseThrow { CategoriaNotFoundException(categoriaId) }

        val entity = MaterialEntity(
            nombre = nombre,
            categoria = categoria,
            cantidadDisponible = cantidadDisponible,
            cantidadTotal = cantidadTotal,
            tamano = TamanoEmbeddable(alto = alto, ancho = ancho, diametro = diametro, unidadMedida = unidadMedida),
            color = color,
            materialFisico = materialFisico,
            estado = estado,
            ubicacion = ubicacion,
            precioUnitario = precioUnitario,
            proveedor = proveedor,
            fotos = fotos.toMutableList()
        )

        if (cantidadDisponible < 0 || cantidadDisponible > cantidadTotal) {
            throw CantidadInvalidaException(entity.id, cantidadDisponible, cantidadTotal)
        }

        return materialRepository.save(entity).toModel()
    }

    @Transactional
    fun update(
        id: EntityId,
        nombre: String,
        categoriaId: EntityId,
        cantidadTotal: Int,
        alto: BigDecimal,
        ancho: BigDecimal,
        diametro: BigDecimal?,
        unidadMedida: UnidadMedida,
        color: String,
        materialFisico: String,
        estado: EstadoMaterial,
        ubicacion: String,
        precioUnitario: BigDecimal,
        proveedor: String,
        fotos: List<String>
    ): MaterialModel {
        val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
        val categoria = categoriaRepository.findById(categoriaId).orElseThrow { CategoriaNotFoundException(categoriaId) }

        if (entity.cantidadDisponible > cantidadTotal) {
            throw CantidadInvalidaException(id, entity.cantidadDisponible, cantidadTotal)
        }

        entity.nombre = nombre
        entity.categoria = categoria
        entity.cantidadTotal = cantidadTotal
        entity.tamano = TamanoEmbeddable(alto = alto, ancho = ancho, diametro = diametro, unidadMedida = unidadMedida)
        entity.color = color
        entity.materialFisico = materialFisico
        entity.estado = estado
        entity.ubicacion = ubicacion
        entity.precioUnitario = precioUnitario
        entity.proveedor = proveedor
        entity.fotos = fotos.toMutableList()

        return materialRepository.save(entity).toModel()
    }

    @Transactional
    fun delete(id: EntityId) {
        val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
        materialRepository.delete(entity)
    }

    fun findHistorial(materialId: EntityId): List<HistorialMaterialModel> {
        if (!materialRepository.existsById(materialId)) throw MaterialNotFoundException(materialId)
        return historialMaterialRepository.findAllByMaterialIdOrderByFechaDesc(materialId).map { it.toModel() }
    }

    @Transactional
    fun updateCantidad(id: EntityId, usuarioId: EntityId, cantidadDisponible: Int, motivo: String): MaterialModel {
        val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
        if (cantidadDisponible < 0 || cantidadDisponible > entity.cantidadTotal) {
            throw CantidadInvalidaException(id, cantidadDisponible, entity.cantidadTotal)
        }

        val valorAnterior = entity.cantidadDisponible
        entity.cantidadDisponible = cantidadDisponible
        val saved = materialRepository.save(entity)

        historialMaterialRepository.save(
            HistorialMaterialEntity(
                materialId = id,
                usuarioId = usuarioId,
                tipoCambio = TipoCambioHistorial.CANTIDAD,
                valorAnterior = valorAnterior.toString(),
                valorNuevo = cantidadDisponible.toString(),
                motivo = motivo
            )
        )

        return saved.toModel()
    }
}
