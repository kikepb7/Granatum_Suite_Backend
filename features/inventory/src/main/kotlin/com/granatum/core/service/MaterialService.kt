package com.granatum.core.service

import com.granatum.core.domain.exception.CantidadInvalidaException
import com.granatum.core.domain.exception.CategoriaNotFoundException
import com.granatum.core.domain.exception.ImagenDemasiadoGrandeException
import com.granatum.core.domain.exception.ImagenNoValidaException
import com.granatum.core.domain.exception.MaterialNotFoundException
import com.granatum.core.domain.model.DimensionesModel
import com.granatum.core.domain.model.HistorialMaterialModel
import com.granatum.core.domain.model.Localizacion
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.port.AlmacenImagenes
import com.granatum.core.domain.type.Almacen
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.TipoCambioHistorial
import com.granatum.core.ficheros.DetectorTipoFichero
import com.granatum.core.ficheros.TipoDocumento
import com.granatum.core.infrastructure.database.entities.DimensionesEmbeddable
import com.granatum.core.infrastructure.database.entities.HistorialMaterialEntity
import com.granatum.core.infrastructure.database.entities.LocalizacionEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.CategoriaRepository
import com.granatum.core.infrastructure.database.repositories.HistorialMaterialRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import com.granatum.core.infrastructure.storage.ConfiguracionAlmacenImagenes
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
 * returns - runs its database part in [transacciones] and talks to Storage
 * outside it: an upload of up to 10 MB never holds a pooled
 * connection, and a failure leaves at most an orphaned object - never a
 * material pointing at an object that does not exist.
 */
@Service
class MaterialService(
    private val materialRepository: MaterialRepository,
    private val categoriaRepository: CategoriaRepository,
    private val historialMaterialRepository: HistorialMaterialRepository,
    private val almacenImagenes: AlmacenImagenes,
    private val configImagenes: ConfiguracionAlmacenImagenes,
    private val transacciones: TransactionOperations
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** FR-010: by warehouse, or by warehouse and section. A section alone is rejected at the edge. */
    fun findAll(almacen: Almacen? = null, seccion: Int? = null): List<MaterialModel> {
        val entidades = when {
            almacen == null -> materialRepository.findAll()
            seccion == null -> materialRepository.findAllByLocalizacionAlmacen(almacen)
            else -> materialRepository.findAllByLocalizacionAlmacenAndLocalizacionSeccion(almacen, seccion)
        }
        return conImagenes(entidades.map { it.toModel() })
    }

    fun findById(id: EntityId): MaterialModel =
        conImagen(materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }.toModel())

    @Transactional
    fun create(
        categoriaId: EntityId,
        descripcion: String,
        dimensiones: DimensionesModel,
        cantidad: Int,
        localizacion: Localizacion
    ): MaterialModel {
        val categoria = categoriaRepository.findById(categoriaId).orElseThrow { CategoriaNotFoundException(categoriaId) }

        val entity = MaterialEntity(
            categoria = categoria,
            descripcion = descripcion,
            dimensiones = DimensionesEmbeddable(alto = dimensiones.alto, ancho = dimensiones.ancho),
            cantidad = cantidad,
            localizacion = LocalizacionEmbeddable(almacen = localizacion.almacen, seccion = localizacion.seccion)
        )
        if (cantidad < 0) throw CantidadInvalidaException(entity.id, cantidad)

        return materialRepository.save(entity).toModel()
    }

    /**
     * Everything but the quantity, which only changes through [updateCantidad]
     * with a reason (research.md D-008), and the image, which has its own
     * operations.
     */
    fun update(
        id: EntityId,
        categoriaId: EntityId,
        descripcion: String,
        dimensiones: DimensionesModel,
        localizacion: Localizacion
    ): MaterialModel = conImagen(
        transacciones.execute {
            val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
            val categoria = categoriaRepository.findById(categoriaId).orElseThrow { CategoriaNotFoundException(categoriaId) }

            entity.categoria = categoria
            entity.descripcion = descripcion
            entity.dimensiones = DimensionesEmbeddable(alto = dimensiones.alto, ancho = dimensiones.ancho)
            entity.localizacion = LocalizacionEmbeddable(almacen = localizacion.almacen, seccion = localizacion.seccion)

            // Flushed so that @LastModifiedDate has run before the model is
            // built: otherwise the response would carry the previous date.
            materialRepository.saveAndFlush(entity).toModel()
        }!!
    )

    /** FR-017: the image goes too, once the row is gone. */
    fun delete(id: EntityId) {
        val ruta = transacciones.execute {
            val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
            materialRepository.delete(entity)
            entity.imagenRuta
        }
        ruta?.let(::borrarSinFallar)
    }

    fun findHistorial(materialId: EntityId): List<HistorialMaterialModel> {
        if (!materialRepository.existsById(materialId)) throw MaterialNotFoundException(materialId)
        return historialMaterialRepository.findAllByMaterialIdOrderByFechaDesc(materialId).map { it.toModel() }
    }

    /** FR-011, FR-012: the only way the quantity changes after creation, always with a reason on record. */
    fun updateCantidad(id: EntityId, usuarioId: EntityId, cantidad: Int, motivo: String): MaterialModel = conImagen(
        transacciones.execute {
            val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
            if (cantidad < 0) throw CantidadInvalidaException(id, cantidad)

            val valorAnterior = entity.cantidad
            entity.cantidad = cantidad
            val saved = materialRepository.saveAndFlush(entity)

            historialMaterialRepository.save(
                HistorialMaterialEntity(
                    materialId = id,
                    usuarioId = usuarioId,
                    tipoCambio = TipoCambioHistorial.CANTIDAD,
                    valorAnterior = valorAnterior.toString(),
                    valorNuevo = cantidad.toString(),
                    motivo = motivo
                )
            )
            saved.toModel()
        }!!
    )

    /**
     * FR-013 to FR-015, in the order of research.md D-003: check the file,
     * upload it to a new path, point the material at it, then drop the old one.
     */
    fun subirImagen(id: EntityId, contenido: ByteArray): MaterialModel {
        if (contenido.size > configImagenes.maxBytes) throw ImagenDemasiadoGrandeException(configImagenes.maxBytes)
        val tipo = DetectorTipoFichero.detectar(contenido)?.takeIf { it in IMAGENES } ?: throw ImagenNoValidaException()
        if (!materialRepository.existsById(id)) throw MaterialNotFoundException(id)

        val nueva = "materiales/$id/${UUID.randomUUID()}.${tipo.extension}"
        almacenImagenes.guardar(nueva, contenido, tipo.mediaType)

        val (material, anterior) = try {
            transacciones.execute {
                val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
                val anterior = entity.imagenRuta
                entity.imagenRuta = nueva
                materialRepository.saveAndFlush(entity).toModel() to anterior
            }!!
        } catch (e: RuntimeException) {
            borrarSinFallar(nueva)
            throw e
        }

        anterior?.let(::borrarSinFallar)
        return conImagen(material)
    }

    /** FR-016. Idempotent: a material without an image stays without one. */
    fun quitarImagen(id: EntityId): MaterialModel {
        val (material, anterior) = transacciones.execute {
            val entity = materialRepository.findById(id).orElseThrow { MaterialNotFoundException(id) }
            val anterior = entity.imagenRuta
            if (anterior == null) {
                entity.toModel() to null
            } else {
                entity.imagenRuta = null
                materialRepository.saveAndFlush(entity).toModel() to anterior
            }
        }!!

        anterior?.let(::borrarSinFallar)
        return material
    }

    private fun conImagen(material: MaterialModel): MaterialModel = conImagenes(listOf(material)).single()

    /**
     * Signs every image in one call (research.md D-005). Any failure leaves
     * the URLs out instead of failing the read (FR-020): what is being read is
     * the inventory, and the image is the one part of it that can be missing.
     */
    private fun conImagenes(materiales: List<MaterialModel>): List<MaterialModel> {
        val rutas = materiales.mapNotNull { it.imagenRuta }
        if (rutas.isEmpty()) return materiales

        val urls = try {
            almacenImagenes.firmar(rutas)
        } catch (e: RuntimeException) {
            log.warn("No se pudieron firmar {} imagenes de materiales: {}", rutas.size, e.javaClass.simpleName)
            emptyMap()
        }
        return materiales.map { m -> m.imagenRuta?.let { m.copy(imagenUrl = urls[it]) } ?: m }
    }

    /**
     * Best effort (research.md D-003): the material is already consistent, and
     * an orphaned object holds no personal data. Logs neither the path nor the
     * cause's message, which can carry the bucket's address.
     */
    private fun borrarSinFallar(ruta: String) {
        try {
            almacenImagenes.borrar(listOf(ruta))
        } catch (e: RuntimeException) {
            log.warn("No se pudo borrar una imagen de material del almacenamiento: {}", e.javaClass.simpleName)
        }
    }

    private companion object {
        /** FR-014: of the formats `common` recognises, the three images. */
        val IMAGENES = setOf(TipoDocumento.JPEG, TipoDocumento.PNG, TipoDocumento.WEBP)
    }
}
