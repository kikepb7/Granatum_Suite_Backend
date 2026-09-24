package com.granatum.core.domain.exception

open class NotFoundException(
    override val message: String = "Resource not found"
) : RuntimeException(message)
