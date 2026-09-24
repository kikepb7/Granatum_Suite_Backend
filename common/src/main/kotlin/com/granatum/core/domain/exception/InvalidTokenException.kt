package com.granatum.core.domain.exception

class InvalidTokenException(
    override val message: String? = null
) : RuntimeException(message ?: "Invalid token")
