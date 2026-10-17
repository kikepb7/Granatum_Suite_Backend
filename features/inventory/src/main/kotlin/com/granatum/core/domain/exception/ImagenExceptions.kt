package com.granatum.core.domain.exception

/**
 * Not a JPEG, PNG or WebP by its first bytes, or empty (FR-014). Deliberately
 * says nothing about the file's name or declared type: both are the client's
 * word, not a fact.
 */
class ImagenNoValidaException : RuntimeException("La imagen debe ser JPEG, PNG o WebP")

class ImagenDemasiadoGrandeException(maxBytes: Long) :
    RuntimeException("La imagen supera el tamano maximo de ${maxBytes / (1024 * 1024)} MB")

/**
 * No Storage configured, or Storage failed while uploading (research.md D-010,
 * D-011). The cause, if any, is kept for the stack trace but never reaches the
 * response: it can contain the bucket's address.
 */
class AlmacenImagenesNoDisponibleException(cause: Throwable? = null) :
    RuntimeException("La subida de imagenes no esta disponible en este momento", cause)
