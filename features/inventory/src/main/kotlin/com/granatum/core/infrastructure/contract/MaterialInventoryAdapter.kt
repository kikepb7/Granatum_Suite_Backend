package com.granatum.core.infrastructure.contract

import com.granatum.core.domain.contract.InsufficientStockException
import com.granatum.core.domain.contract.MaterialInventory
import com.granatum.core.domain.contract.MaterialMissingException
import com.granatum.core.domain.contract.MaterialStock
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.MaterialChangeType
import com.granatum.core.infrastructure.database.entities.MaterialHistoryEntity
import com.granatum.core.infrastructure.database.repositories.MaterialHistoryRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import com.granatum.core.service.MaterialService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * The inventory side of [MaterialInventory] (feature 011). Every movement is a change of
 * `QUANTITY` in the material's history, like an adjustment by hand, so the history keeps saying
 * why each unit left and came back.
 *
 * The methods that write join the caller's transaction (default propagation): the event line and
 * the units it took are saved together, or neither (specs/011-events research.md D-003).
 */
@Component
class MaterialInventoryAdapter(
    private val materialService: MaterialService,
    private val materialRepository: MaterialRepository,
    private val historyRepository: MaterialHistoryRepository,
    private val clock: Clock = Clock.systemUTC()
) : MaterialInventory {

    override fun available(): List<MaterialStock> = materialService.findAll().map { it.toStock() }

    override fun summarize(materialIds: Collection<EntityId>): Map<EntityId, MaterialStock> =
        materialService.findAllById(materialIds.toSet()).associate { it.id to it.toStock() }

    @Transactional
    override fun withdraw(materialId: EntityId, quantity: Int, userId: EntityId, reason: String): MaterialStock {
        require(quantity > 0) { "La cantidad a retirar debe ser positiva" }
        if (materialRepository.withdraw(materialId, quantity, Instant.now(clock)) == 0) {
            val available = materialRepository.quantityOf(materialId) ?: throw MaterialMissingException(materialId)
            throw InsufficientStockException(materialId, available)
        }
        val remaining = materialRepository.quantityOf(materialId)!!
        record(materialId, userId, before = remaining + quantity, after = remaining, reason)
        val material = materialRepository.findById(materialId).get()
        return MaterialStock(
            id = materialId,
            category = material.category.name,
            description = material.description,
            quantity = remaining,
            warehouse = material.location.warehouse.name,
            section = material.location.section,
            imageUrl = null
        )
    }

    @Transactional
    override fun putBack(materialId: EntityId, quantity: Int, userId: EntityId, reason: String): Boolean {
        require(quantity > 0) { "La cantidad a devolver debe ser positiva" }
        if (materialRepository.putBack(materialId, quantity, Instant.now(clock)) == 0) return false
        val now = materialRepository.quantityOf(materialId)!!
        record(materialId, userId, before = now - quantity, after = now, reason)
        return true
    }

    private fun record(materialId: EntityId, userId: EntityId, before: Int, after: Int, reason: String) {
        historyRepository.save(
            MaterialHistoryEntity(
                materialId = materialId,
                userId = userId,
                changeType = MaterialChangeType.QUANTITY,
                previousValue = before.toString(),
                newValue = after.toString(),
                reason = reason.take(REASON_MAX)
            )
        )
    }

    private fun MaterialModel.toStock() = MaterialStock(
        id = id,
        category = category.name,
        description = description,
        quantity = quantity,
        warehouse = location.warehouse.name,
        section = location.section,
        imageUrl = imageUrl
    )

    private companion object {
        /** `material_history.reason` is VARCHAR(500). */
        const val REASON_MAX = 500
    }
}
