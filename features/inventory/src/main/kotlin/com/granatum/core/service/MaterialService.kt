package com.granatum.core.service

import com.granatum.core.domain.contract.MaterialUsage
import com.granatum.core.domain.exception.CategoryNotFoundException
import com.granatum.core.domain.exception.InvalidMaterialImageException
import com.granatum.core.domain.exception.InvalidMaterialQuantityException
import com.granatum.core.domain.exception.MaterialImageTooLargeException
import com.granatum.core.domain.exception.MaterialInUseException
import com.granatum.core.domain.exception.MaterialNotFoundException
import com.granatum.core.domain.model.MaterialDimensionsModel
import com.granatum.core.domain.model.MaterialHistoryModel
import com.granatum.core.domain.model.MaterialLocation
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.port.MaterialImageStorage
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.MaterialChangeType
import com.granatum.core.domain.type.Warehouse
import com.granatum.core.files.DocumentType
import com.granatum.core.files.FileTypeDetector
import com.granatum.core.infrastructure.database.entities.MaterialDimensionsEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.entities.MaterialHistoryEntity
import com.granatum.core.infrastructure.database.entities.MaterialLocationEmbeddable
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.CategoryRepository
import com.granatum.core.infrastructure.database.repositories.MaterialHistoryRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import com.granatum.core.infrastructure.storage.MaterialImageStorageProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/**
 * Materials and their images.
 *
 * Storage and Postgres share no transaction (research.md D-003), so every
 * operation that can touch Storage - including signing the image of what it
 * returns - runs its database part in [transactions] and talks to Storage
 * outside it: an upload of up to 10 MB never holds a pooled
 * connection, and a failure leaves at most an orphaned object - never a
 * material pointing at an object that does not exist.
 */
@Service
class MaterialService(
    private val materialRepository: MaterialRepository,
    private val categoryRepository: CategoryRepository,
    private val materialHistoryRepository: MaterialHistoryRepository,
    private val imageStorage: MaterialImageStorage,
    private val imageProperties: MaterialImageStorageProperties,
    private val transactions: TransactionOperations,
    /** Feature 011: a material whose units are out at an event cannot be deleted (FR-013). */
    private val materialUsage: MaterialUsage
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** FR-010: by warehouse, or by warehouse and section. A section alone is rejected at the edge. */
    fun findAll(warehouse: Warehouse? = null, section: Int? = null): List<MaterialModel> {
        val entities = when {
            warehouse == null -> materialRepository.findAll()
            section == null -> materialRepository.findAllByLocationWarehouse(warehouse)
            else -> materialRepository.findAllByLocationWarehouseAndLocationSection(warehouse, section)
        }
        return withImages(entities.map { it.toModel() })
    }

    /** Those of [ids] that exist, with their images signed in one call (feature 011). */
    fun findAllById(ids: Collection<EntityId>): List<MaterialModel> =
        if (ids.isEmpty()) emptyList() else withImages(materialRepository.findAllByIdIn(ids).map { it.toModel() })

    fun findById(id: EntityId): MaterialModel =
        withImage(materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }.toModel())

    @Transactional
    fun create(
        categoryId: EntityId,
        description: String,
        dimensions: MaterialDimensionsModel,
        quantity: Int,
        location: MaterialLocation
    ): MaterialModel {
        val category = categoryRepository.findById(categoryId).orElseThrow { CategoryNotFoundException(categoryId) }

        val entity = MaterialEntity(
            category = category,
            description = description,
            dimensions = MaterialDimensionsEmbeddable(height = dimensions.height, width = dimensions.width),
            quantity = quantity,
            location = MaterialLocationEmbeddable(warehouse = location.warehouse, section = location.section)
        )
        if (quantity < 0) throw InvalidMaterialQuantityException(entity.id, quantity)

        return materialRepository.save(entity).toModel()
    }

    /**
     * Everything but the quantity, which only changes through [updateQuantity]
     * with a reason (research.md D-008), and the image, which has its own
     * operations.
     */
    fun update(
        id: EntityId,
        categoryId: EntityId,
        description: String,
        dimensions: MaterialDimensionsModel,
        location: MaterialLocation
    ): MaterialModel = withImage(
        transactions.execute {
            val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
            val category = categoryRepository.findById(categoryId).orElseThrow { CategoryNotFoundException(categoryId) }

            entity.category = category
            entity.description = description
            entity.dimensions = MaterialDimensionsEmbeddable(height = dimensions.height, width = dimensions.width)
            entity.location = MaterialLocationEmbeddable(warehouse = location.warehouse, section = location.section)

            // Flushed so that @LastModifiedDate has run before the model is
            // built: otherwise the response would carry the previous date.
            materialRepository.saveAndFlush(entity).toModel()
        }!!
    )

    /** FR-017: the image goes too, once the row is gone. */
    fun delete(id: EntityId) {
        val path = transactions.execute {
            val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
            if (materialUsage.isInUse(id)) throw MaterialInUseException(id)
            materialRepository.delete(entity)
            entity.imagePath
        }
        path?.let(::deleteQuietly)
    }

    fun findHistory(materialId: EntityId): List<MaterialHistoryModel> {
        if (!materialRepository.existsById(materialId)) throw MaterialNotFoundException(materialId)
        return materialHistoryRepository.findAllByMaterialIdOrderByDateDesc(materialId).map { it.toModel() }
    }

    /** FR-011, FR-012: the only way the quantity changes after creation, always with a reason on record. */
    fun updateQuantity(id: EntityId, userId: EntityId, quantity: Int, reason: String): MaterialModel = withImage(
        transactions.execute {
            val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
            if (quantity < 0) throw InvalidMaterialQuantityException(id, quantity)

            val previousValue = entity.quantity
            entity.quantity = quantity
            val saved = materialRepository.saveAndFlush(entity)

            materialHistoryRepository.save(
                MaterialHistoryEntity(
                    materialId = id,
                    userId = userId,
                    changeType = MaterialChangeType.QUANTITY,
                    previousValue = previousValue.toString(),
                    newValue = quantity.toString(),
                    reason = reason
                )
            )
            saved.toModel()
        }!!
    )

    /**
     * FR-013 to FR-015, in the order of research.md D-003: check the file,
     * upload it to a new path, point the material at it, then drop the old one.
     */
    fun uploadImage(id: EntityId, content: ByteArray): MaterialModel {
        if (content.size > imageProperties.maxBytes) throw MaterialImageTooLargeException(imageProperties.maxBytes)
        val type = FileTypeDetector.detect(content)?.takeIf { it in IMAGE_TYPES } ?: throw InvalidMaterialImageException()
        if (!materialRepository.existsById(id)) throw MaterialNotFoundException(id)

        val newPath = "$PATH_PREFIX/$id/${UUID.randomUUID()}.${type.extension}"
        imageStorage.store(newPath, content, type.mediaType)

        val (material, previous) = try {
            transactions.execute {
                val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
                val previous = entity.imagePath
                entity.imagePath = newPath
                materialRepository.saveAndFlush(entity).toModel() to previous
            }!!
        } catch (e: RuntimeException) {
            deleteQuietly(newPath)
            throw e
        }

        previous?.let(::deleteQuietly)
        return withImage(material)
    }

    /** FR-016. Idempotent: a material without an image stays without one. */
    fun removeImage(id: EntityId): MaterialModel {
        val (material, previous) = transactions.execute {
            val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
            val previous = entity.imagePath
            if (previous == null) {
                entity.toModel() to null
            } else {
                entity.imagePath = null
                materialRepository.saveAndFlush(entity).toModel() to previous
            }
        }!!

        previous?.let(::deleteQuietly)
        return material
    }

    private fun withImage(material: MaterialModel): MaterialModel = withImages(listOf(material)).single()

    /**
     * Signs every image in one call (research.md D-005). Any failure leaves
     * the URLs out instead of failing the read (FR-020): what is being read is
     * the inventory, and the image is the one part of it that can be missing.
     */
    private fun withImages(materials: List<MaterialModel>): List<MaterialModel> {
        val paths = materials.mapNotNull { it.imagePath }
        if (paths.isEmpty()) return materials

        val urls = try {
            imageStorage.sign(paths)
        } catch (e: RuntimeException) {
            log.warn("No se pudieron firmar {} imagenes de materiales: {}", paths.size, e.javaClass.simpleName)
            emptyMap()
        }
        return materials.map { m -> m.imagePath?.let { m.copy(imageUrl = urls[it]) } ?: m }
    }

    /**
     * Best effort (research.md D-003): the material is already consistent, and
     * an orphaned object holds no personal data. Logs neither the path nor the
     * cause's message, which can carry the bucket's address.
     */
    private fun deleteQuietly(path: String) {
        try {
            imageStorage.delete(listOf(path))
        } catch (e: RuntimeException) {
            log.warn("No se pudo borrar una imagen de material del almacenamiento: {}", e.javaClass.simpleName)
        }
    }

    private companion object {
        /** FR-014: of the formats `common` recognises, the three images. */
        val IMAGE_TYPES = setOf(DocumentType.JPEG, DocumentType.PNG, DocumentType.WEBP)

        /**
         * Folder of new uploads inside the bucket. Images stored before V28 keep their
         * `materiales/...` paths, which are still valid: a path is opaque once saved.
         */
        const val PATH_PREFIX = "materials"
    }
}
