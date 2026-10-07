package com.granatum.core

import com.granatum.core.domain.exception.CantidadInvalidaException
import com.granatum.core.domain.exception.MaterialNotFoundException
import com.granatum.core.domain.type.EstadoMaterial
import com.granatum.core.domain.type.TipoCambioHistorial
import com.granatum.core.domain.type.UnidadMedida
import com.granatum.core.infrastructure.database.entities.CategoriaEntity
import com.granatum.core.infrastructure.database.entities.HistorialMaterialEntity
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.entities.TamanoEmbeddable
import com.granatum.core.infrastructure.database.repositories.CategoriaRepository
import com.granatum.core.infrastructure.database.repositories.HistorialMaterialRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import com.granatum.core.service.MaterialService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MaterialServiceTest {

    private val materialRepository = mockk<MaterialRepository>()
    private val categoriaRepository = mockk<CategoriaRepository>()
    private val historialMaterialRepository = mockk<HistorialMaterialRepository>()

    private val service = MaterialService(materialRepository, categoriaRepository, historialMaterialRepository)

    private fun material(cantidadDisponible: Int = 5, cantidadTotal: Int = 10) = MaterialEntity(
        nombre = "Cilindro",
        categoria = CategoriaEntity(nombre = "Cilindros"),
        cantidadDisponible = cantidadDisponible,
        cantidadTotal = cantidadTotal,
        tamano = TamanoEmbeddable(BigDecimal.TEN, BigDecimal.TEN, null, UnidadMedida.CM),
        color = "Transparente",
        materialFisico = "Cristal",
        estado = EstadoMaterial.NUEVO,
        ubicacion = "A1",
        precioUnitario = BigDecimal("9.99"),
        proveedor = "Proveedor SL"
    )

    @Test
    fun `updateCantidad saves the new value and records a historial entry`() {
        val entity = material(cantidadDisponible = 5, cantidadTotal = 10)
        val usuarioId = UUID.randomUUID()

        every { materialRepository.findById(entity.id) } returns Optional.of(entity)
        every { materialRepository.save(entity) } returns entity
        val historialSlot = slot<HistorialMaterialEntity>()
        every { historialMaterialRepository.save(capture(historialSlot)) } answers { historialSlot.captured }

        val result = service.updateCantidad(entity.id, usuarioId, 3, "Uso en evento")

        assertEquals(3, result.cantidadDisponible)
        assertEquals(TipoCambioHistorial.CANTIDAD, historialSlot.captured.tipoCambio)
        assertEquals("5", historialSlot.captured.valorAnterior)
        assertEquals("3", historialSlot.captured.valorNuevo)
        assertEquals("Uso en evento", historialSlot.captured.motivo)
        assertEquals(usuarioId, historialSlot.captured.usuarioId)
        verify(exactly = 1) { historialMaterialRepository.save(any()) }
    }

    @Test
    fun `updateCantidad rejects a value above cantidadTotal`() {
        val entity = material(cantidadDisponible = 5, cantidadTotal = 10)
        every { materialRepository.findById(entity.id) } returns Optional.of(entity)

        assertFailsWith<CantidadInvalidaException> {
            service.updateCantidad(entity.id, UUID.randomUUID(), 11, "Motivo")
        }
    }

    @Test
    fun `updateCantidad throws when the material does not exist`() {
        val id = UUID.randomUUID()
        every { materialRepository.findById(id) } returns Optional.empty()

        assertFailsWith<MaterialNotFoundException> {
            service.updateCantidad(id, UUID.randomUUID(), 1, "Motivo")
        }
    }
}
