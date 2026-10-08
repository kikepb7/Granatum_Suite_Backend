package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import java.time.Instant

/**
 * A pending sign-up as the ADMIN sees it in the list (feature 005).
 *
 * Neither the password hash nor the code hash: nothing in the list, and no rule
 * outside the services that hold the entity, has any use for them.
 * `empleadoExistenteId` tells the ADMIN whether approving will link to an
 * existing staff record or needs the data to create one.
 *
 * `toString` is overridden: name, email and document are personal data and a
 * data class would print them wherever the object is mentioned (principle VI).
 */
data class SolicitudRegistroPendiente(
    val id: EntityId,
    val email: String,
    val nombre: String,
    val documentoIdentidad: String,
    val creadaEn: Instant,
    val empleadoExistenteId: EntityId?
) {
    override fun toString(): String = "SolicitudRegistroPendiente(id=$id, creadaEn=$creadaEn)"
}

/** What approving produced. */
data class RegistroAprobado(
    val solicitudId: EntityId,
    val cuentaId: EntityId,
    val empleadoId: EntityId,
    val rol: com.granatum.core.domain.type.Role,
    val fichaCreada: Boolean
)
