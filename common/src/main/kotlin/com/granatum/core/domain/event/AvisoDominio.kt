package com.granatum.core.domain.event

import com.granatum.core.domain.type.EntityId

/**
 * Something happened that someone should hear about (feature 008).
 *
 * The second form of "explicit contract" between features that constitution
 * principle I allows, next to the interfaces in `domain/contract`: the feature
 * where it happens publishes it with Spring's `ApplicationEventPublisher`, and
 * `notifications` listens - neither knows the other exists.
 *
 * Carries ids only: never a name, a comment or a reason, because a notice must
 * not carry personal data (feature 008, FR-006).
 *
 * @property referenciaId what the notice is about: a shift, a correction, an
 *   absence, a sign-up request.
 * @property titularId the person it concerns, when it concerns one (the
 *   shift's owner, the absence's person); `null` for a sign-up, whose person
 *   has no staff record yet.
 * @property autorId who caused it, so they are not notified about their own
 *   request; `null` when the system did (a job).
 */
data class AvisoDominio(
    val tipo: TipoAviso,
    val referenciaId: EntityId,
    val titularId: EntityId? = null,
    val autorId: EntityId? = null
)

/** The kinds of notice (specs/008-notifications/contracts/README.md). */
enum class TipoAviso {
    FICHAJE_SIN_SALIDA,
    FICHAJE_INCOMPLETO,
    CORRECCION_PENDIENTE,
    CORRECCION_APROBADA,
    CORRECCION_RECHAZADA,
    AUSENCIA_PENDIENTE,
    AUSENCIA_APROBADA,
    AUSENCIA_RECHAZADA,
    REGISTRO_PENDIENTE
}
