package com.granatum.core.domain.type

/**
 * Application-wide roles. ADMIN has full access; ENCARGADO manages
 * inventory and approves fichaje corrections; EMPLEADO can only fichar
 * and see their own history.
 */
enum class Role {
    ADMIN,
    ENCARGADO,
    EMPLEADO
}
