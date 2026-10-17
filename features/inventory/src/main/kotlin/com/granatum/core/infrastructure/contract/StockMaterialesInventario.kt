package com.granatum.core.infrastructure.contract

import com.granatum.core.domain.contract.MaterialInexistenteException
import com.granatum.core.domain.contract.MaterialStock
import com.granatum.core.domain.contract.StockInsuficienteException
import com.granatum.core.domain.contract.StockMateriales
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.TipoCambioHistorial
import com.granatum.core.infrastructure.database.entities.HistorialMaterialEntity
import com.granatum.core.infrastructure.database.repositories.HistorialMaterialRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import com.granatum.core.service.MaterialService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * The inventory side of [StockMateriales] (feature 011). Every movement is a change of
 * `CANTIDAD` in the material's history, like an adjustment by hand, so the history keeps saying
 * why each unit left and came back.
 *
 * The methods that write join the caller's transaction (default propagation): the event line and
 * the units it took are saved together, or neither (specs/011-events research.md D-003).
 */
@Component
class StockMaterialesInventario(
    private val materialService: MaterialService,
    private val materialRepository: MaterialRepository,
    private val historialRepository: HistorialMaterialRepository,
    private val clock: Clock = Clock.systemUTC()
) : StockMateriales {

    override fun disponibles(): List<MaterialStock> = materialService.findAll().map { it.toStock() }

    override fun resumen(materialIds: Collection<EntityId>): Map<EntityId, MaterialStock> =
        materialService.findAllById(materialIds.toSet()).associate { it.id to it.toStock() }

    @Transactional
    override fun retirar(materialId: EntityId, cantidad: Int, usuarioId: EntityId, motivo: String): MaterialStock {
        require(cantidad > 0) { "La cantidad a retirar debe ser positiva" }
        if (materialRepository.retirar(materialId, cantidad, Instant.now(clock)) == 0) {
            val disponible = materialRepository.cantidadDe(materialId) ?: throw MaterialInexistenteException(materialId)
            throw StockInsuficienteException(materialId, disponible)
        }
        val queda = materialRepository.cantidadDe(materialId)!!
        anotar(materialId, usuarioId, antes = queda + cantidad, despues = queda, motivo)
        val material = materialRepository.findById(materialId).get()
        return MaterialStock(
            id = materialId,
            categoria = material.categoria.nombre,
            descripcion = material.descripcion,
            cantidad = queda,
            almacen = material.localizacion.almacen.name,
            seccion = material.localizacion.seccion,
            imagenUrl = null
        )
    }

    @Transactional
    override fun devolver(materialId: EntityId, cantidad: Int, usuarioId: EntityId, motivo: String): Boolean {
        require(cantidad > 0) { "La cantidad a devolver debe ser positiva" }
        if (materialRepository.devolver(materialId, cantidad, Instant.now(clock)) == 0) return false
        val ahora = materialRepository.cantidadDe(materialId)!!
        anotar(materialId, usuarioId, antes = ahora - cantidad, despues = ahora, motivo)
        return true
    }

    private fun anotar(materialId: EntityId, usuarioId: EntityId, antes: Int, despues: Int, motivo: String) {
        historialRepository.save(
            HistorialMaterialEntity(
                materialId = materialId,
                usuarioId = usuarioId,
                tipoCambio = TipoCambioHistorial.CANTIDAD,
                valorAnterior = antes.toString(),
                valorNuevo = despues.toString(),
                motivo = motivo.take(MOTIVO_MAX)
            )
        )
    }

    private fun MaterialModel.toStock() = MaterialStock(
        id = id,
        categoria = categoria.nombre,
        descripcion = descripcion,
        cantidad = cantidad,
        almacen = localizacion.almacen.name,
        seccion = localizacion.seccion,
        imagenUrl = imagenUrl
    )

    private companion object {
        /** `historial_material.motivo` is VARCHAR(500). */
        const val MOTIVO_MAX = 500
    }
}
