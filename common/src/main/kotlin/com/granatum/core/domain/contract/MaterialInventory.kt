package com.granatum.core.domain.contract

import com.granatum.core.domain.type.EntityId

/**
 * What `events` needs from the inventory owned by `inventory` (feature 011): which materials
 * there are, and taking units out for an event and putting them back.
 *
 * ## Why this exists
 *
 * Assigning material to an event has to take it out of the inventory, in the same transaction
 * and failing when there is not enough. The materials live in `inventory`, and constitution
 * principle I forbids `events` from depending on it; this is the explicit contract the principle
 * allows, like [EmployeeDirectory]. `inventory` implements it, `events` compiles against it,
 * and neither mentions the other.
 *
 * It is a required dependency: if nobody implements it, `app` does not start.
 *
 * ## Transactions
 *
 * Implementations MUST join the caller's transaction (default propagation): an event line and
 * the units it took are saved together, or neither (specs/011-events research.md D-003).
 */
interface MaterialInventory {

    /** Every material with its units in stock, for choosing what an event takes. */
    fun available(): List<MaterialStock>

    /** The materials with these ids that still exist, by id. One query, whatever the size. */
    fun summarize(materialIds: Collection<EntityId>): Map<EntityId, MaterialStock>

    /**
     * Takes [quantity] units out, recording it in the material's history with [reason] on behalf
     * of [userId], and returns what is left.
     *
     * @throws MaterialMissingException if there is no such material.
     * @throws InsufficientStockException if there are fewer than [quantity] units; nothing changes.
     */
    fun withdraw(materialId: EntityId, quantity: Int, userId: EntityId, reason: String): MaterialStock

    /**
     * Puts [quantity] units back, recorded like [withdraw]. Returns `false`, changing nothing, when
     * the material no longer exists: there is nowhere to put them back (research.md D-007).
     */
    fun putBack(materialId: EntityId, quantity: Int, userId: EntityId, reason: String): Boolean
}

/**
 * A material as another feature may see it: what it is, where it is and how many units are free.
 * No history, no audit data. Location travels as text because its enum belongs to `inventory`.
 */
data class MaterialStock(
    val id: EntityId,
    val category: String,
    val description: String,
    val quantity: Int,
    val warehouse: String,
    val section: Int,
    /** Temporary signed address of its image, or `null`. */
    val imageUrl: String?
)

/** There are fewer units than asked for; [available] is how many there are. */
class InsufficientStockException(val materialId: EntityId, val available: Int) :
    RuntimeException("Solo hay $available unidades disponibles de ese material")

class MaterialMissingException(materialId: EntityId) :
    RuntimeException("El material $materialId no existe")
