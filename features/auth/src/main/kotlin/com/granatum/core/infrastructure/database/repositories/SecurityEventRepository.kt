package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.SecurityEventEntity
import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * APPEND-ONLY, and the interface says so.
 *
 * Extends [Repository] and declares nothing but an insert and two reads. No
 * `delete`, no `deleteById`, no `saveAll` over existing rows. This is the last
 * rule of constitution principle III applied literally, and the lesson of
 * declared debt nº 2: `HistorialMaterialRepository` extends `JpaRepository` over
 * an append-only table, so it offers `delete` to anyone who reaches for it.
 * Nobody calls it today, which is not the same as it being impossible.
 */
interface SecurityEventRepository : Repository<SecurityEventEntity, UUID> {

    fun save(event: SecurityEventEntity): SecurityEventEntity

    fun findAllByAccountIdOrderByOccurredAtDesc(accountId: UUID): List<SecurityEventEntity>

    fun count(): Long

    /** Events with no account to hang them on - feature 005's sign-up events. */
    fun countByType(type: com.granatum.core.domain.type.SecurityEventType): Long
}
