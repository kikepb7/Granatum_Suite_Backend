package com.granatum.core.domain.type

/**
 * The life of a sign-up request (feature 005). Every state but [PENDIENTE] is
 * final, and only [PENDIENTE] holds personal data - the V20 CHECKs enforce both.
 */
enum class EstadoSolicitudRegistro {
    PENDIENTE,
    APROBADA,
    RECHAZADA,

    /** Left unresolved past `auth.registro.caducidad-dias`. */
    CADUCADA,

    /**
     * Resolved by the system rather than by an ADMIN's decision: five wrong
     * codes, another request of the same person approved, the address already
     * taken, or the first ADMIN created with that address.
     */
    ANULADA
}
