package com.granatum.core.domain.contract

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoEmpleado

/**
 * What one feature needs to know about the staff records owned by another.
 *
 * ## Why this exists
 *
 * `auth` needs four things about a person: that they exist when an account is
 * created (FR-029c), that they are active when they sign in (FR-002) and when
 * they renew (FR-010), and which accounts point at nobody (FR-029c). All four
 * facts live in `timetracking`'s `empleados` table, and constitution principle
 * I forbids one feature from depending on another.
 *
 * The same principle gives the way out: *"if two features need to share
 * something, that something MUST move up to `common` or be exposed as an
 * explicit contract"*. This is that explicit contract. `auth` compiles against
 * this interface and never mentions `timetracking`, so the boundary is held by
 * the compiler rather than by discipline.
 *
 * ## Why it is a required dependency
 *
 * Consumers take it by constructor with no default. If nobody implements it,
 * `app` does not start. That is the correct failure: an `auth` that cannot check
 * whether a person exists would silently accept orphan accounts, which is
 * precisely what FR-029c forbids.
 *
 * ## What it deliberately does not offer
 *
 * No names, no identity documents, no contract type, no locations. A contract
 * that exposed the whole staff record would let any future feature read personal
 * data it has no reason to see, and principle VI treats that as a data
 * protection failure, not just an authorisation one.
 */
interface DirectorioEmpleados {

    /**
     * The state of the person with this id, or `null` if no such person is
     * registered. The `null` case is the one FR-029c is about, so it is a
     * distinct answer and not an exception.
     */
    fun estado(empleadoId: EntityId): EstadoEmpleado?

    /**
     * Of the ids given, those that correspond to a registered person.
     *
     * Exists as a batch operation and not as a loop over [estado] because the
     * orphan sweep walks every account: one query per account would be a
     * guaranteed N+1. Implementations MUST answer in a single query.
     */
    fun existentes(empleadoIds: Collection<EntityId>): Set<EntityId>
}
