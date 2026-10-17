package com.granatum.core

import com.granatum.core.domain.contract.MaterialInexistenteException
import com.granatum.core.domain.contract.MaterialStock
import com.granatum.core.domain.contract.StockInsuficienteException
import com.granatum.core.domain.contract.StockMateriales
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
 * This module's stand-in for `StockMateriales`. In production `inventory` implements it; here the
 * inventory is a map, because this module must not depend on `inventory` even in tests.
 */
class StockMaterialesDoble : StockMateriales {

    data class Movimiento(val materialId: EntityId, val cantidad: Int, val usuarioId: EntityId, val motivo: String)

    private val materiales = mutableMapOf<EntityId, MaterialStock>()
    val retiradas = mutableListOf<Movimiento>()
    val devoluciones = mutableListOf<Movimiento>()

    fun material(cantidad: Int, descripcion: String = "Jarrón cilíndrico", categoria: String = "Jarrones"): EntityId =
        UUID.randomUUID().also { materiales[it] = MaterialStock(it, categoria, descripcion, cantidad, "A", 2, null) }

    fun borrar(id: EntityId) {
        materiales -= id
    }

    fun cantidad(id: EntityId): Int? = materiales[id]?.cantidad

    override fun disponibles(): List<MaterialStock> = materiales.values.toList()

    override fun resumen(materialIds: Collection<EntityId>): Map<EntityId, MaterialStock> =
        materiales.filterKeys { it in materialIds }

    override fun retirar(materialId: EntityId, cantidad: Int, usuarioId: EntityId, motivo: String): MaterialStock {
        val material = materiales[materialId] ?: throw MaterialInexistenteException(materialId)
        if (material.cantidad < cantidad) throw StockInsuficienteException(materialId, material.cantidad)
        retiradas += Movimiento(materialId, cantidad, usuarioId, motivo)
        return material.copy(cantidad = material.cantidad - cantidad).also { materiales[materialId] = it }
    }

    override fun devolver(materialId: EntityId, cantidad: Int, usuarioId: EntityId, motivo: String): Boolean {
        val material = materiales[materialId] ?: return false
        devoluciones += Movimiento(materialId, cantidad, usuarioId, motivo)
        materiales[materialId] = material.copy(cantidad = material.cantidad + cantidad)
        return true
    }
}

@TestConfiguration
class StockMaterialesDobleConfig {
    @Bean
    fun stockMateriales() = StockMaterialesDoble()
}
