package com.granatum.core

import com.granatum.core.domain.contract.MaterialUsage
import com.granatum.core.domain.exception.CategoryNotFoundException
import com.granatum.core.domain.exception.ImageStorageUnavailableException
import com.granatum.core.domain.exception.InvalidMaterialImageException
import com.granatum.core.domain.exception.InvalidMaterialQuantityException
import com.granatum.core.domain.exception.MaterialImageTooLargeException
import com.granatum.core.domain.exception.MaterialInUseException
import com.granatum.core.domain.exception.MaterialNotFoundException
import com.granatum.core.domain.model.MaterialDimensionsModel
import com.granatum.core.domain.model.MaterialLocation
import com.granatum.core.domain.port.MaterialImageStorage
import com.granatum.core.domain.type.MaterialChangeType
import com.granatum.core.domain.type.Warehouse
import com.granatum.core.infrastructure.database.entities.CategoryEntity
import com.granatum.core.infrastructure.database.entities.MaterialDimensionsEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.entities.MaterialHistoryEntity
import com.granatum.core.infrastructure.database.entities.MaterialLocationEmbeddable
import com.granatum.core.infrastructure.database.repositories.CategoryRepository
import com.granatum.core.infrastructure.database.repositories.MaterialHistoryRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import com.granatum.core.infrastructure.storage.DisabledMaterialImageStorage
import com.granatum.core.infrastructure.storage.MaterialImageStorageProperties
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
    private val categoryRepository = mockk<CategoryRepository>()
    private val materialHistoryRepository = mockk<MaterialHistoryRepository>()
    private val storage = mockk<MaterialImageStorage>(relaxed = true)
    private val materialUsage = mockk<MaterialUsage> { every { isInUse(any()) } returns false }
    private val properties = MaterialImageStorageProperties(
        supabaseUrl = "https://storage.test",
        serviceKey = "clave",
        bucket = "materiales",
        urlSeconds = 3600,
        maxBytes = 1024
    )

    private fun service(imageStorage: MaterialImageStorage = storage) = MaterialService(
        materialRepository, categoryRepository, materialHistoryRepository,
        imageStorage, properties, TransactionOperations.withoutTransaction(), materialUsage
    )

    private val service = service()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16)
    private val pdf = "%PDF-1.7".toByteArray() + ByteArray(16)

    private fun material(quantity: Int = 5, imagePath: String? = null) = MaterialEntity(
        category = CategoryEntity(name = "Cilindros"),
        description = "Cilindro de cristal",
        dimensions = MaterialDimensionsEmbeddable(BigDecimal.TEN, BigDecimal.TEN),
        quantity = quantity,
        location = MaterialLocationEmbeddable(Warehouse.A, 1),
        imagePath = imagePath
    )

    private fun exists(entity: MaterialEntity) {
        every { materialRepository.findById(entity.id) } returns Optional.of(entity)
        every { materialRepository.existsById(entity.id) } returns true
        every { materialRepository.saveAndFlush(entity) } returns entity
    }

    // --- US1 / US2: creation, editing, location -------------------------------

    @Test
    fun `create stores every field of the new model`() {
        val category = CategoryEntity(name = "Cables")
        every { categoryRepository.findById(category.id) } returns Optional.of(category)
        val saved = slot<MaterialEntity>()
        every { materialRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.create(
            category.id, "Bobina", MaterialDimensionsModel(BigDecimal("30"), BigDecimal("45.5")), 12, MaterialLocation(Warehouse.B, 4)
        )

        assertEquals("Bobina", result.description)
        assertEquals(BigDecimal("45.5"), result.dimensions.width)
        assertEquals(12, result.quantity)
        assertEquals(MaterialLocation(Warehouse.B, 4), result.location)
        assertNull(result.imagePath)
        assertNull(result.imageUrl)
    }

    @Test
    fun `create with an unknown category saves nothing`() {
        val categoryId = UUID.randomUUID()
        every { categoryRepository.findById(categoryId) } returns Optional.empty()

        assertFailsWith<CategoryNotFoundException> {
            service.create(categoryId, "x", MaterialDimensionsModel(BigDecimal.ONE, BigDecimal.ONE), 1, MaterialLocation(Warehouse.A, 1))
        }
        verify(exactly = 0) { materialRepository.save(any()) }
    }

    @Test
    fun `update changes description, dimensions, category and location but never the quantity`() {
        val entity = material(quantity = 7)
        exists(entity)
        val other = CategoryEntity(name = "Otra")
        every { categoryRepository.findById(other.id) } returns Optional.of(other)

        val result = service.update(
            entity.id, other.id, "Nueva", MaterialDimensionsModel(BigDecimal("2"), BigDecimal("3")), MaterialLocation(Warehouse.C, 5)
        )

        assertEquals(other.id, result.category.id)
        assertEquals("Nueva", result.description)
        assertEquals(BigDecimal("3"), result.dimensions.width)
        assertEquals(MaterialLocation(Warehouse.C, 5), result.location)
        assertEquals(7, result.quantity)
    }

    @Test
    fun `a location outside sections 1 to 5 cannot even be built`() {
        assertFailsWith<IllegalArgumentException> { MaterialLocation(Warehouse.A, 0) }
        assertFailsWith<IllegalArgumentException> { MaterialLocation(Warehouse.A, 6) }
    }

    // --- US4: quantity with a reason ------------------------------------------

    @Test
    fun `updateQuantity saves the new value and records a history entry`() {
        val entity = material(quantity = 10)
        exists(entity)
        val userId = UUID.randomUUID()
        val history = slot<MaterialHistoryEntity>()
        every { materialHistoryRepository.save(capture(history)) } answers { history.captured }

        val result = service.updateQuantity(entity.id, userId, 7, "Rotura")

        assertEquals(7, result.quantity)
        assertEquals(MaterialChangeType.QUANTITY, history.captured.changeType)
        assertEquals("10", history.captured.previousValue)
        assertEquals("7", history.captured.newValue)
        assertEquals("Rotura", history.captured.reason)
        assertEquals(userId, history.captured.userId)
    }

    @Test
    fun `updateQuantity rejects a negative value and writes no history`() {
        val entity = material(quantity = 5)
        exists(entity)

        assertFailsWith<InvalidMaterialQuantityException> { service.updateQuantity(entity.id, UUID.randomUUID(), -1, "Motivo") }
        assertEquals(5, entity.quantity)
        verify(exactly = 0) { materialHistoryRepository.save(any()) }
    }

    @Test
    fun `updateQuantity throws when the material does not exist`() {
        val id = UUID.randomUUID()
        every { materialRepository.findById(id) } returns Optional.empty()

        assertFailsWith<MaterialNotFoundException> { service.updateQuantity(id, UUID.randomUUID(), 1, "Motivo") }
    }

    // --- US5: filters ---------------------------------------------------------

    @Test
    fun `findAll filters by warehouse, or by warehouse and section`() {
        val a = material()
        every { materialRepository.findAllByLocationWarehouse(Warehouse.A) } returns listOf(a)
        every { materialRepository.findAllByLocationWarehouseAndLocationSection(Warehouse.A, 1) } returns listOf(a)
        every { materialRepository.findAll() } returns listOf(a, material())

        assertEquals(listOf(a.id), service.findAll(Warehouse.A).map { it.id })
        assertEquals(listOf(a.id), service.findAll(Warehouse.A, 1).map { it.id })
        assertEquals(2, service.findAll().size)
    }

    // --- US3: image -----------------------------------------------------------

    @Test
    fun `a file that is not an image by its bytes is rejected without touching storage`() {
        val entity = material()
        exists(entity)

        assertFailsWith<InvalidMaterialImageException> { service.uploadImage(entity.id, pdf) }
        assertFailsWith<InvalidMaterialImageException> { service.uploadImage(entity.id, ByteArray(0)) }
        verify(exactly = 0) { storage.store(any(), any(), any()) }
    }

    @Test
    fun `a file over the limit is rejected without touching storage`() {
        val entity = material()
        exists(entity)

        assertFailsWith<MaterialImageTooLargeException> { service.uploadImage(entity.id, jpeg + ByteArray(2048)) }
        verify(exactly = 0) { storage.store(any(), any(), any()) }
    }

    @Test
    fun `uploading to a missing material stores nothing`() {
        val id = UUID.randomUUID()
        every { materialRepository.existsById(id) } returns false

        assertFailsWith<MaterialNotFoundException> { service.uploadImage(id, jpeg) }
        verify(exactly = 0) { storage.store(any(), any(), any()) }
    }

    @Test
    fun `an upload goes to a new path under the material, is pointed at, and the old one is deleted`() {
        val entity = material(imagePath = "materiales/vieja.jpg")
        exists(entity)
        val path = slot<String>()
        every { storage.store(capture(path), any(), any()) } just runs
        every { storage.sign(any()) } answers { firstArg<Collection<String>>().associateWith { "https://firmada/$it" } }

        val result = service.uploadImage(entity.id, jpeg)

        assertTrue(Regex("materials/${entity.id}/[0-9a-f-]{36}\\.jpg").matches(path.captured), path.captured)
        verify { storage.store(path.captured, jpeg, "image/jpeg") }
        assertEquals(path.captured, entity.imagePath)
        assertEquals("https://firmada/${path.captured}", result.imageUrl)
        verify { storage.delete(listOf("materiales/vieja.jpg")) }
    }

    @Test
    fun `two uploads never share a path`() {
        val entity = material()
        exists(entity)
        val paths = mutableListOf<String>()
        every { storage.store(capture(paths), any(), any()) } just runs

        service.uploadImage(entity.id, jpeg)
        service.uploadImage(entity.id, jpeg)

        assertNotEquals(paths[0], paths[1])
    }

    @Test
    fun `if pointing the material at the new image fails, the new object is deleted and the old one kept`() {
        val entity = material(imagePath = "materials/old.jpg")
        exists(entity)
        every { materialRepository.saveAndFlush(entity) } throws IllegalStateException("db caida")
        val path = slot<String>()
        every { storage.store(capture(path), any(), any()) } just runs

        assertFailsWith<IllegalStateException> { service.uploadImage(entity.id, jpeg) }

        verify { storage.delete(listOf(path.captured)) }
        verify(exactly = 0) { storage.delete(listOf("materials/old.jpg")) }
    }

    @Test
    fun `failing to delete the replaced object does not fail the upload`() {
        val entity = material(imagePath = "materials/old.jpg")
        exists(entity)
        every { storage.delete(listOf("materials/old.jpg")) } throws ImageStorageUnavailableException()

        service.uploadImage(entity.id, jpeg)

        assertTrue(entity.imagePath!!.startsWith("materials/${entity.id}/"))
    }

    @Test
    fun `removing the image clears it and deletes the object, and is idempotent`() {
        val entity = material(imagePath = "materials/old.jpg")
        exists(entity)

        assertNull(service.removeImage(entity.id).imagePath)
        verify(exactly = 1) { storage.delete(listOf("materials/old.jpg")) }

        assertNull(service.removeImage(entity.id).imagePath)
        verify(exactly = 1) { storage.delete(any()) }
    }

    @Test
    fun `deleting a material deletes its image after the row`() {
        val entity = material(imagePath = "materials/old.jpg")
        exists(entity)
        every { materialRepository.delete(entity) } just runs

        service.delete(entity.id)

        verify { materialRepository.delete(entity) }
        verify { storage.delete(listOf("materials/old.jpg")) }
    }

    @Test
    fun `a material out at a scheduled event is not deleted`() {
        val entity = material()
        exists(entity)
        every { materialUsage.isInUse(entity.id) } returns true

        assertFailsWith<MaterialInUseException> { service.delete(entity.id) }

        verify(exactly = 0) { materialRepository.delete(any()) }
    }

    @Test
    fun `without storage, uploads are refused while removing and deleting still work`() {
        val withoutStorage = service(DisabledMaterialImageStorage())
        val entity = material(imagePath = "materials/old.jpg")
        exists(entity)
        every { materialRepository.delete(entity) } just runs

        assertFailsWith<ImageStorageUnavailableException> { withoutStorage.uploadImage(entity.id, jpeg) }
        assertEquals("materials/old.jpg", entity.imagePath, "a refused upload changes nothing")
        assertNull(withoutStorage.findById(entity.id).imageUrl)
        assertNull(withoutStorage.removeImage(entity.id).imagePath)
        withoutStorage.delete(entity.id)
    }

    @Test
    fun `a listing signs every image in one call`() {
        val withImage1 = material(imagePath = "materials/1.jpg")
        val withImage2 = material(imagePath = "materials/2.png")
        val withoutImage = material()
        every { materialRepository.findAll() } returns listOf(withImage1, withoutImage, withImage2)
        every { storage.sign(any()) } answers { firstArg<Collection<String>>().associateWith { "https://firmada/$it" } }

        val urls = service.findAll().map { it.imageUrl }

        assertEquals(listOf("https://firmada/materials/1.jpg", null, "https://firmada/materials/2.png"), urls)
        verify(exactly = 1) { storage.sign(listOf("materials/1.jpg", "materials/2.png")) }
    }

    @Test
    fun `a storage failure while signing leaves the URL out instead of failing the read`() {
        val entity = material(imagePath = "materials/1.jpg")
        exists(entity)
        every { storage.sign(any()) } throws ImageStorageUnavailableException()

        val result = service.findById(entity.id)

        assertEquals("materials/1.jpg", result.imagePath)
        assertNull(result.imageUrl)
    }
}
