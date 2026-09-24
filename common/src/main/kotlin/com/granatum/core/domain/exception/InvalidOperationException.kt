package com.granatum.core.domain.exception

/**
 * A request is well-formed but violates a business rule (e.g. reducing
 * `cantidadDisponible` below zero). Mapped to HTTP 400 by
 * [com.granatum.core.api.exception_handling.CommonExceptionHandler].
 */
open class InvalidOperationException(
    override val message: String
) : RuntimeException(message)
