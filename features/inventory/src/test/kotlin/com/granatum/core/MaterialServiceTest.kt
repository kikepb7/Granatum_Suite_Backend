package com.granatum.core

import com.granatum.core.domain.exception.AlmacenImagenesNoDisponibleException
import com.granatum.core.domain.exception.CantidadInvalidaException
import com.granatum.core.domain.exception.CategoriaNotFoundException
import com.granatum.core.domain.exception.ImagenDemasiadoGrandeException
import com.granatum.core.domain.exception.ImagenNoValidaException
import com.granatum.core.domain.exception.MaterialNotFoundException
import com.granatum.core.domain.model.DimensionesModel
import com.granatum.core.domain.model.Localizacion
import com.granatum.core.domain.port.AlmacenImagenes
import com.granatum.core.domain.type.Almacen
import com.granatum.core.domain.type.TipoCambioHistorial
import com.granatum.core.infrastructure.database.entities.CategoriaEntity
import com.granatum.core.infrastructure.database.entities.DimensionesEmbeddable
import com.granatum.core.infrastructure.database.entities.HistorialMaterialEntity
import com.granatum.core.infrastructure.database.entities.LocalizacionEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.repositories.CategoriaRepository
import com.granatum.core.domain.contract.UsoMateriales
import com.granatum.core.domain.exception.MaterialEnUsoException
import com.granatum.core.infrastructure.database.repositories.HistorialMaterialRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import com.granatum.core.infrastructure.storage.AlmacenImagenesDeshabilitado
import com.granatum.core.infrastructure.storage.ConfiguracionAlmacenImagenes
import com.granatum.core.service.MaterialService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.springframework.transaction.support.TransactionOperations
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialServiceTest {

    private val materialRepository = mockk<MaterialRepository>()
    private val categoriaRepository = mockk<CategoriaRepository>()
    private val historialMaterialRepository = mockk<HistorialMaterialRepository>()
    private val almacen = mockk<AlmacenImagenes>(relaxed = true)
    private val usoMateriales = mockk<UsoMateriales> { every { enUso(any()) } returns false }
    private val config = ConfiguracionAlmacenImagenes(
        supabaseUrl = "https://storage.test",
        serviceKey = "clave",
        bucket = "materiales",
        urlSegundos = 3600,
        maxBytes = 1024
    )

    private fun servicio(almacenImagenes: AlmacenImagenes = almacen) = MaterialService(
        materialRepository, categoriaRepository, historialMaterialRepository,
        almacenImagenes, config, TransactionOperations.withoutTransaction(), usoMateriales
    )

    private val service = servicio()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16)
    private val pdf = "%PDF-1.7".toByteArray() + ByteArray(16)

    private fun material(cantidad: Int = 5, imagenRuta: String? = null) = MaterialEntity(
        categoria = CategoriaEntity(nombre = "Cilindros"),
        descripcion = "Cilindro de cristal",
        dimensiones = DimensionesEmbeddable(BigDecimal.TEN, BigDecimal.TEN),
        cantidad = cantidad,
        localizacion = LocalizacionEmbeddable(Almacen.A, 1),
        imagenRuta = imagenRuta
    )

    private fun existe(entity: MaterialEntity) {
        every { materialRepository.findById(entity.id) } returns Optional.of(entity)
        every { materialRepository.existsById(entity.id) } returns true
        every { materialRepository.saveAndFlush(entity) } returns entity
    }

    // --- US1 / US2: alta, edicion, localizacion ------------------------------

    @Test
    fun `create stores every field of the new model`() {
        val categoria = CategoriaEntity(nombre = "Cables")
        every { categoriaRepository.findById(categoria.id) } returns Optional.of(categoria)
        val guardado = slot<MaterialEntity>()
        every { materialRepository.save(capture(guardado)) } answers { guardado.captured }

        val result = service.create(
            categoria.id, "Bobina", DimensionesModel(BigDecimal("30"), BigDecimal("45.5")), 12, Localizacion(Almacen.B, 4)
        )

        assertEquals("Bobina", result.descripcion)
        assertEquals(BigDecimal("45.5"), result.dimensiones.ancho)
        assertEquals(12, result.cantidad)
        assertEquals(Localizacion(Almacen.B, 4), result.localizacion)
        assertNull(result.imagenRuta)
        assertNull(result.imagenUrl)
    }

    @Test
    fun `create with an unknown category saves nothing`() {
        val categoriaId = UUID.randomUUID()
        every { categoriaRepository.findById(categoriaId) } returns Optional.empty()

        assertFailsWith<CategoriaNotFoundException> {
            service.create(categoriaId, "x", DimensionesModel(BigDecimal.ONE, BigDecimal.ONE), 1, Localizacion(Almacen.A, 1))
        }
        verify(exactly = 0) { materialRepository.save(any()) }
    }

    @Test
    fun `update changes description, dimensions, category and location but never the quantity`() {
        val entity = material(cantidad = 7)
        existe(entity)
        val nueva = CategoriaEntity(nombre = "Otra")
        every { categoriaRepository.findById(nueva.id) } returns Optional.of(nueva)

        val result = service.update(
            entity.id, nueva.id, "Nueva", DimensionesModel(BigDecimal("2"), BigDecimal("3")), Localizacion(Almacen.C, 5)
        )

        assertEquals(nueva.id, result.categoria.id)
        assertEquals("Nueva", result.descripcion)
        assertEquals(BigDecimal("3"), result.dimensiones.ancho)
        assertEquals(Localizacion(Almacen.C, 5), result.localizacion)
        assertEquals(7, result.cantidad)
    }

    @Test
    fun `a location outside sections 1 to 5 cannot even be built`() {
        assertFailsWith<IllegalArgumentException> { Localizacion(Almacen.A, 0) }
        assertFailsWith<IllegalArgumentException> { Localizacion(Almacen.A, 6) }
    }

    // --- US4: cantidad con motivo --------------------------------------------

    @Test
    fun `updateCantidad saves the new value and records a historial entry`() {
        val entity = material(cantidad = 10)
        existe(entity)
        val usuarioId = UUID.randomUUID()
        val historial = slot<HistorialMaterialEntity>()
        every { historialMaterialRepository.save(capture(historial)) } answers { historial.captured }

        val result = service.updateCantidad(entity.id, usuarioId, 7, "Rotura")

        assertEquals(7, result.cantidad)
        assertEquals(TipoCambioHistorial.CANTIDAD, historial.captured.tipoCambio)
        assertEquals("10", historial.captured.valorAnterior)
        assertEquals("7", historial.captured.valorNuevo)
        assertEquals("Rotura", historial.captured.motivo)
        assertEquals(usuarioId, historial.captured.usuarioId)
    }

    @Test
    fun `updateCantidad rejects a negative value and writes no historial`() {
        val entity = material(cantidad = 5)
        existe(entity)

        assertFailsWith<CantidadInvalidaException> { service.updateCantidad(entity.id, UUID.randomUUID(), -1, "Motivo") }
        assertEquals(5, entity.cantidad)
        verify(exactly = 0) { historialMaterialRepository.save(any()) }
    }

    @Test
    fun `updateCantidad throws when the material does not exist`() {
        val id = UUID.randomUUID()
        every { materialRepository.findById(id) } returns Optional.empty()

        assertFailsWith<MaterialNotFoundException> { service.updateCantidad(id, UUID.randomUUID(), 1, "Motivo") }
    }

    // --- US5: filtros --------------------------------------------------------

    @Test
    fun `findAll filters by warehouse, or by warehouse and section`() {
        val a = material()
        every { materialRepository.findAllByLocalizacionAlmacen(Almacen.A) } returns listOf(a)
        every { materialRepository.findAllByLocalizacionAlmacenAndLocalizacionSeccion(Almacen.A, 1) } returns listOf(a)
        every { materialRepository.findAll() } returns listOf(a, material())

        assertEquals(listOf(a.id), service.findAll(Almacen.A).map { it.id })
        assertEquals(listOf(a.id), service.findAll(Almacen.A, 1).map { it.id })
        assertEquals(2, service.findAll().size)
    }

    // --- US3: imagen ---------------------------------------------------------

    @Test
    fun `a file that is not an image by its bytes is rejected without touching storage`() {
        val entity = material()
        existe(entity)

        assertFailsWith<ImagenNoValidaException> { service.subirImagen(entity.id, pdf) }
        assertFailsWith<ImagenNoValidaException> { service.subirImagen(entity.id, ByteArray(0)) }
        verify(exactly = 0) { almacen.guardar(any(), any(), any()) }
    }

    @Test
    fun `a file over the limit is rejected without touching storage`() {
        val entity = material()
        existe(entity)

        assertFailsWith<ImagenDemasiadoGrandeException> { service.subirImagen(entity.id, jpeg + ByteArray(2048)) }
        verify(exactly = 0) { almacen.guardar(any(), any(), any()) }
    }

    @Test
    fun `uploading to a missing material stores nothing`() {
        val id = UUID.randomUUID()
        every { materialRepository.existsById(id) } returns false

        assertFailsWith<MaterialNotFoundException> { service.subirImagen(id, jpeg) }
        verify(exactly = 0) { almacen.guardar(any(), any(), any()) }
    }

    @Test
    fun `an upload goes to a new path under the material, is pointed at, and the old one is deleted`() {
        val entity = material(imagenRuta = "materiales/vieja.jpg")
        existe(entity)
        val ruta = slot<String>()
        every { almacen.guardar(capture(ruta), any(), any()) } just runs
        every { almacen.firmar(any()) } answers { firstArg<Collection<String>>().associateWith { "https://firmada/$it" } }

        val result = service.subirImagen(entity.id, jpeg)

        assertTrue(Regex("materiales/${entity.id}/[0-9a-f-]{36}\\.jpg").matches(ruta.captured), ruta.captured)
        verify { almacen.guardar(ruta.captured, jpeg, "image/jpeg") }
        assertEquals(ruta.captured, entity.imagenRuta)
        assertEquals("https://firmada/${ruta.captured}", result.imagenUrl)
        verify { almacen.borrar(listOf("materiales/vieja.jpg")) }
    }

    @Test
    fun `two uploads never share a path`() {
        val entity = material()
        existe(entity)
        val rutas = mutableListOf<String>()
        every { almacen.guardar(capture(rutas), any(), any()) } just runs

        service.subirImagen(entity.id, jpeg)
        service.subirImagen(entity.id, jpeg)

        assertNotEquals(rutas[0], rutas[1])
    }

    @Test
    fun `if pointing the material at the new image fails, the new object is deleted and the old one kept`() {
        val entity = material(imagenRuta = "materiales/vieja.jpg")
        existe(entity)
        every { materialRepository.saveAndFlush(entity) } throws IllegalStateException("db caida")
        val ruta = slot<String>()
        every { almacen.guardar(capture(ruta), any(), any()) } just runs

        assertFailsWith<IllegalStateException> { service.subirImagen(entity.id, jpeg) }

        verify { almacen.borrar(listOf(ruta.captured)) }
        verify(exactly = 0) { almacen.borrar(listOf("materiales/vieja.jpg")) }
    }

    @Test
    fun `failing to delete the replaced object does not fail the upload`() {
        val entity = material(imagenRuta = "materiales/vieja.jpg")
        existe(entity)
        every { almacen.borrar(listOf("materiales/vieja.jpg")) } throws AlmacenImagenesNoDisponibleException()

        service.subirImagen(entity.id, jpeg)

        assertTrue(entity.imagenRuta!!.startsWith("materiales/${entity.id}/"))
    }

    @Test
    fun `removing the image clears it and deletes the object, and is idempotent`() {
        val entity = material(imagenRuta = "materiales/vieja.jpg")
        existe(entity)

        assertNull(service.quitarImagen(entity.id).imagenRuta)
        verify(exactly = 1) { almacen.borrar(listOf("materiales/vieja.jpg")) }

        assertNull(service.quitarImagen(entity.id).imagenRuta)
        verify(exactly = 1) { almacen.borrar(any()) }
    }

    @Test
    fun `deleting a material deletes its image after the row`() {
        val entity = material(imagenRuta = "materiales/vieja.jpg")
        existe(entity)
        every { materialRepository.delete(entity) } just runs

        service.delete(entity.id)

        verify { materialRepository.delete(entity) }
        verify { almacen.borrar(listOf("materiales/vieja.jpg")) }
    }

    @Test
    fun `a material out at a scheduled event is not deleted`() {
        val entity = material()
        existe(entity)
        every { usoMateriales.enUso(entity.id) } returns true

        assertFailsWith<MaterialEnUsoException> { service.delete(entity.id) }

        verify(exactly = 0) { materialRepository.delete(any()) }
    }

    @Test
    fun `without storage, uploads are refused while removing and deleting still work`() {
        val sinAlmacen = servicio(AlmacenImagenesDeshabilitado())
        val entity = material(imagenRuta = "materiales/vieja.jpg")
        existe(entity)
        every { materialRepository.delete(entity) } just runs

        assertFailsWith<AlmacenImagenesNoDisponibleException> { sinAlmacen.subirImagen(entity.id, jpeg) }
        assertEquals("materiales/vieja.jpg", entity.imagenRuta, "a refused upload changes nothing")
        assertNull(sinAlmacen.findById(entity.id).imagenUrl)
        assertNull(sinAlmacen.quitarImagen(entity.id).imagenRuta)
        sinAlmacen.delete(entity.id)
    }

    @Test
    fun `a listing signs every image in one call`() {
        val conImagen1 = material(imagenRuta = "materiales/1.jpg")
        val conImagen2 = material(imagenRuta = "materiales/2.png")
        val sinImagen = material()
        every { materialRepository.findAll() } returns listOf(conImagen1, sinImagen, conImagen2)
        every { almacen.firmar(any()) } answers { firstArg<Collection<String>>().associateWith { "https://firmada/$it" } }

        val urls = service.findAll().map { it.imagenUrl }

        assertEquals(listOf("https://firmada/materiales/1.jpg", null, "https://firmada/materiales/2.png"), urls)
        verify(exactly = 1) { almacen.firmar(listOf("materiales/1.jpg", "materiales/2.png")) }
    }

    @Test
    fun `a storage failure while signing leaves the URL out instead of failing the read`() {
        val entity = material(imagenRuta = "materiales/1.jpg")
        existe(entity)
        every { almacen.firmar(any()) } throws AlmacenImagenesNoDisponibleException()

        val result = service.findById(entity.id)

        assertEquals("materiales/1.jpg", result.imagenRuta)
        assertNull(result.imagenUrl)
    }
}
