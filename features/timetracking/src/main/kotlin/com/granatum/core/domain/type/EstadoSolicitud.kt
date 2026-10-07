package com.granatum.core.domain.type

/**
 * State of a correction request. [APROBADA] and [RECHAZADA] are both terminal:
 * resolving twice is rejected.
 */
enum class EstadoSolicitud {
    PENDIENTE,
    APROBADA,
    RECHAZADA
}
