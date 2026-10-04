package com.granatum.core.domain.type

/**
 * Application-wide roles.
 *
 * - [ADMIN] has full access.
 * - [ENCARGADO] manages inventory and approves fichaje corrections.
 * - [EMPLEADO] can only fichar and see their own history.
 * - [REPRESENTANTE] is read-only over the whole workforce's working-time
 *   register and nothing else. It exists because article 34.9 of the Spanish
 *   Workers' Statute names workers' legal representatives as recipients of
 *   that register. Its scope is the narrowest that satisfies the obligation:
 *   it never sees fichaje locations (not required by 34.9, and the most
 *   intrusive datum in the record), never approves or requests corrections,
 *   never manages staff, never touches inventory, and writes nothing.
 */
enum class Role {
    ADMIN,
    ENCARGADO,
    EMPLEADO,
    REPRESENTANTE
}
