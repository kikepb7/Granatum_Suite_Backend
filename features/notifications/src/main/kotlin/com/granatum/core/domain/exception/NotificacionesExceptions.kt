package com.granatum.core.domain.exception

/** Someone else's notice is "not found", never "forbidden": its id must not be confirmed (FR-010). */
class NotificacionNoEncontradaException(id: Any) :
    NotFoundException("No existe ninguna notificación con el id $id")
