package com.granatum.core

import com.granatum.core.api.mappers.toDto
import com.granatum.core.domain.model.CategoriaModel
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.model.TamanoModel
import com.granatum.core.domain.type.EstadoMaterial
import com.granatum.core.domain.type.UnidadMedida
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MaterialDtoMapperTest {

    @Test
    fun `toDto maps every field including nested categoria and tamano`() {
        val now = Instant.now()
        val categoria = CategoriaModel(UUID.randomUUID(), "Cilindros", null, now, now)
        val model = MaterialModel(
            id = UUID.randomUUID(),
            nombre = "Cilindro alto",
            categoria = categoria,
            cantidadDisponible = 3,
            cantidadTotal = 5,
            tamano = TamanoModel(BigDecimal.TEN, BigDecimal.ONE, null, UnidadMedida.CM),
            color = "Transparente",
            materialFisico = "Cristal",
            estado = EstadoMaterial.NUEVO,
            ubicacion = "A1",
            precioUnitario = BigDecimal("9.99"),
            proveedor = "Proveedor SL",
            fotos = listOf("https://example.com/a.jpg"),
            fechaAlta = now,
            fechaUltimaModificacion = now
        )

        val dto = model.toDto()

        assertEquals(model.id, dto.id)
        assertEquals(model.categoria.id, dto.categoria.id)
        assertEquals(model.tamano.alto, dto.tamano.alto)
        assertEquals(model.fotos, dto.fotos)
        assertEquals(model.estado, dto.estado)
    }
}
