package com.granatum.core.domain.contract

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role

/**
 * Who holds a role (feature 008): `notifications` needs to know who the
 * ENCARGADOs and ADMINs are to tell them about pending requests, and roles live
 * on `auth`'s accounts. `auth` implements it; consumers never see an account,
 * an email or a hash - only the ids of the people, which is what the token
 * subject is.
 */
interface DirectorioRoles {

    /** The staff ids of the people whose account has any of [roles]. One query. */
    fun empleadosConRol(roles: Set<Role>): Set<EntityId>
}
