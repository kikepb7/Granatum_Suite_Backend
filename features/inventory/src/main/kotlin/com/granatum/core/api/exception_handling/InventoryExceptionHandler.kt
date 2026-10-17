package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.CategoryNotFoundException
import com.granatum.core.domain.exception.ImageStorageUnavailableException
import com.granatum.core.domain.exception.InvalidMaterialImageException
import com.granatum.core.domain.exception.MaterialImageTooLargeException
import com.granatum.core.domain.exception.MaterialInUseException
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

    @ExceptionHandler(CategoryNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onCategoryNotFound(e: CategoryNotFoundException) = mapOf(
        "code" to "CATEGORY_NOT_FOUND",
        "message" to e.message
    )

    @ExceptionHandler(MaterialNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMaterialNotFound(e: MaterialNotFoundException) = mapOf(
        "code" to "MATERIAL_NOT_FOUND",
        "message" to e.message
    )

    @ExceptionHandler(MaterialInUseException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onMaterialInUse(e: MaterialInUseException) = mapOf(
        "code" to "MATERIAL_IN_USE",
        "message" to e.message
    )

    @ExceptionHandler(InvalidMaterialImageException::class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    fun onInvalidImage(e: InvalidMaterialImageException) = mapOf(
        "code" to "INVALID_IMAGE",
        "message" to e.message
    )

    @ExceptionHandler(MaterialImageTooLargeException::class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    fun onImageTooLarge(e: MaterialImageTooLargeException) = mapOf(
        "code" to "IMAGE_TOO_LARGE",
        "message" to e.message
    )

    @ExceptionHandler(ImageStorageUnavailableException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun onImageStorageUnavailable(e: ImageStorageUnavailableException) = mapOf(
        "code" to "IMAGE_STORAGE_UNAVAILABLE",
        "message" to e.message
    )
}
