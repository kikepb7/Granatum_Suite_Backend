package com.granatum.core

import com.granatum.core.api.dto.LocalizacionRequest
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.mappers.toModel
import com.granatum.core.domain.model.CategoriaModel
import com.granatum.core.domain.model.DimensionesModel
import com.granatum.core.domain.model.Localizacion
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.type.Almacen
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MaterialDtoMapperTest {

    @Test
    fun `toDto maps every field and exposes the signed URL, never the storage path`() {
        val now = Instant.now()
        val categoria = CategoriaModel(UUID.randomUUID(), "Cilindros", null, now, now)
        val model = MaterialModel(
            id = UUID.randomUUID(),
            categoria = categoria,
            descripcion = "Cilindro alto",
            dimensiones = DimensionesModel(BigDecimal.TEN, BigDecimal.ONE),
            cantidad = 3,
            localizacion = Localizacion(Almacen.C, 5),
            imagenRuta = "materiales/a/b.jpg",
            imagenUrl = "https://example.test/firmada",
            fechaAlta = now,
            fechaUltimaModificacion = now
        )

        val dto = model.toDto()

        assertEquals(model.id, dto.id)
        assertEquals(categoria.id, dto.categoria.id)
        assertEquals("Cilindro alto", dto.descripcion)
        assertEquals(BigDecimal.TEN, dto.dimensiones.alto)
        assertEquals(BigDecimal.ONE, dto.dimensiones.ancho)
        assertEquals(3, dto.cantidad)
        assertEquals(Almacen.C, dto.localizacion.almacen)
        assertEquals(5, dto.localizacion.seccion)
        assertEquals("https://example.test/firmada", dto.imagenUrl)
    }

    @Test
    fun `a location request maps to the domain location`() {
        assertEquals(Localizacion(Almacen.B, 4), LocalizacionRequest("B", 4).toModel())
    }
}
