package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.AlmacenImagenesNoDisponibleException
import com.granatum.core.domain.exception.CategoriaNotFoundException
import com.granatum.core.domain.exception.ImagenDemasiadoGrandeException
import com.granatum.core.domain.exception.ImagenNoValidaException
import com.granatum.core.domain.exception.MaterialEnUsoException
import com.granatum.core.domain.exception.MaterialNotFoundException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Ahead of [CommonExceptionHandler], like every other module's advice: the two
 * not-found exceptions are also [com.granatum.core.domain.exception.NotFoundException]s,
 * and without an order which advice answered - `MATERIAL_NOT_FOUND` or the
 * generic `NOT_FOUND` - was left to bean registration order.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class InventoryExceptionHandler {

    @ExceptionHandler(CategoriaNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onCategoriaNotFound(e: CategoriaNotFoundException) = mapOf(
        "code" to "CATEGORIA_NOT_FOUND",
        "message" to e.message
    )

    @ExceptionHandler(MaterialNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMaterialNotFound(e: MaterialNotFoundException) = mapOf(
        "code" to "MATERIAL_NOT_FOUND",
        "message" to e.message
    )

    @ExceptionHandler(MaterialEnUsoException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onMaterialEnUso(e: MaterialEnUsoException) = mapOf(
        "code" to "MATERIAL_EN_USO",
        "message" to e.message
    )

    @ExceptionHandler(ImagenNoValidaException::class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    fun onImagenNoValida(e: ImagenNoValidaException) = mapOf(
        "code" to "IMAGEN_NO_VALIDA",
        "message" to e.message
    )

    @ExceptionHandler(ImagenDemasiadoGrandeException::class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    fun onImagenDemasiadoGrande(e: ImagenDemasiadoGrandeException) = mapOf(
        "code" to "IMAGEN_DEMASIADO_GRANDE",
        "message" to e.message
    )

    @ExceptionHandler(AlmacenImagenesNoDisponibleException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun onAlmacenNoDisponible(e: AlmacenImagenesNoDisponibleException) = mapOf(
        "code" to "ALMACEN_IMAGENES_NO_DISPONIBLE",
        "message" to e.message
    )
}
