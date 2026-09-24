package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.CategoriaNotFoundException
import com.granatum.core.domain.exception.MaterialNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

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
}
