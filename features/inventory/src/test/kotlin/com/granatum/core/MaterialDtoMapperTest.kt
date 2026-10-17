package com.granatum.core

import com.granatum.core.api.dto.MaterialLocationRequest
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.mappers.toModel
import com.granatum.core.domain.model.CategoryModel
import com.granatum.core.domain.model.MaterialDimensionsModel
import com.granatum.core.domain.model.MaterialLocation
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.type.Warehouse
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MaterialDtoMapperTest {

    @Test
    fun `toDto maps every field and exposes the signed URL, never the storage path`() {
        val now = Instant.now()
        val category = CategoryModel(UUID.randomUUID(), "Cilindros", null, now, now)
        val model = MaterialModel(
            id = UUID.randomUUID(),
            category = category,
            description = "Cilindro alto",
            dimensions = MaterialDimensionsModel(BigDecimal.TEN, BigDecimal.ONE),
            quantity = 3,
            location = MaterialLocation(Warehouse.C, 5),
            imagePath = "materials/a/b.jpg",
            imageUrl = "https://example.test/firmada",
            createdAt = now,
            updatedAt = now
        )

        val dto = model.toDto()

        assertEquals(model.id, dto.id)
        assertEquals(category.id, dto.category.id)
        assertEquals("Cilindro alto", dto.description)
        assertEquals(BigDecimal.TEN, dto.dimensions.height)
        assertEquals(BigDecimal.ONE, dto.dimensions.width)
        assertEquals(3, dto.quantity)
        assertEquals(Warehouse.C, dto.location.warehouse)
        assertEquals(5, dto.location.section)
        assertEquals("https://example.test/firmada", dto.imageUrl)
    }

    @Test
    fun `a location request maps to the domain location`() {
        assertEquals(MaterialLocation(Warehouse.B, 4), MaterialLocationRequest("B", 4).toModel())
    }
}
