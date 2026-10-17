package com.granatum.core

import com.granatum.core.domain.contract.InsufficientStockException
import com.granatum.core.domain.contract.MaterialInventory
import com.granatum.core.domain.contract.MaterialMissingException
import com.granatum.core.domain.contract.MaterialStock
import com.granatum.core.domain.type.EntityId
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.util.UUID

/** Test-only boot class for this module's Spring contexts (feature 011). */
@SpringBootApplication
@EnableJpaAuditing
class EventsTestApplication

/**
 * This module's stand-in for `MaterialInventory`. In production `inventory` implements it; here
 * the inventory is a map, because this module must not depend on `inventory` even in tests.
 */
class MaterialInventoryFake : MaterialInventory {

    data class Movement(val materialId: EntityId, val quantity: Int, val userId: EntityId, val reason: String)

    private val materials = mutableMapOf<EntityId, MaterialStock>()
    val withdrawals = mutableListOf<Movement>()
    val returns = mutableListOf<Movement>()

    fun material(quantity: Int, description: String = "Jarrón cilíndrico", category: String = "Jarrones"): EntityId =
        UUID.randomUUID().also { materials[it] = MaterialStock(it, category, description, quantity, "A", 2, null) }

    fun delete(id: EntityId) {
        materials -= id
    }

    fun quantity(id: EntityId): Int? = materials[id]?.quantity

    override fun available(): List<MaterialStock> = materials.values.toList()

    override fun summarize(materialIds: Collection<EntityId>): Map<EntityId, MaterialStock> =
        materials.filterKeys { it in materialIds }

    override fun withdraw(materialId: EntityId, quantity: Int, userId: EntityId, reason: String): MaterialStock {
        val material = materials[materialId] ?: throw MaterialMissingException(materialId)
        if (material.quantity < quantity) throw InsufficientStockException(materialId, material.quantity)
        withdrawals += Movement(materialId, quantity, userId, reason)
        return material.copy(quantity = material.quantity - quantity).also { materials[materialId] = it }
    }

    override fun putBack(materialId: EntityId, quantity: Int, userId: EntityId, reason: String): Boolean {
        val material = materials[materialId] ?: return false
        returns += Movement(materialId, quantity, userId, reason)
        materials[materialId] = material.copy(quantity = material.quantity + quantity)
        return true
    }
}

@TestConfiguration
class MaterialInventoryFakeConfig {
    @Bean
    fun materialInventory() = MaterialInventoryFake()
}
