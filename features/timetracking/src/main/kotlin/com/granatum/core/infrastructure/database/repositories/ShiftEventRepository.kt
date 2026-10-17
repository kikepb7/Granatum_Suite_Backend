package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.ShiftEventEntity
import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * The append-only event log. Declares **only** what the table admits: one
 * insert and one lookup.
 *
 * No `delete`, no `deleteAll`, and no `saveAll` either - a bulk save would
 * bypass the per-event idempotency check that is the whole point of the
 * unique `client_event_id`.
 */
interface ShiftEventRepository : Repository<ShiftEventEntity, UUID> {
    fun save(event: ShiftEventEntity): ShiftEventEntity
    fun findByClientEventId(clientEventId: UUID): ShiftEventEntity?
}
