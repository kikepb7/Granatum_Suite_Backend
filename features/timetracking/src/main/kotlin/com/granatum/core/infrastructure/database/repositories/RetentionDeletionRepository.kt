package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.ShiftEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The **only** deletion capability in the module, isolated in one file so it can
 * be read in one sitting (constitution principle III since v2.0.0).
 *
 * Every method is a `@Modifying` query whose `WHERE` is bounded by the cut-off
 * instant. There is deliberately no `delete(entity)` and no `deleteById`: a
 * method that takes an id could remove a record whose retention period is still
 * running, so the guarantee would rest on whoever calls it remembering to
 * check. Here it rests on the query, which cannot do otherwise.
 *
 * Order matters when calling these - children before parents - because the
 * foreign keys are not declared `ON DELETE CASCADE`, on purpose: a cascade
 * would mean deleting a fichaje silently takes its breaks and corrections with
 * it, which is exactly the kind of quiet data loss principle III exists to
 * prevent. Making the caller delete each table explicitly also makes the counts
 * per table available for the audit record.
 */
interface RetentionDeletionRepository : Repository<ShiftEntity, UUID> {

    @Modifying
    @Query(
        """
        DELETE FROM ShiftBreakEntity p
         WHERE p.shift.id IN (
               SELECT f.id FROM ShiftEntity f WHERE f.clockIn < :cutoff
         )
        """
    )
    fun deleteBreaksBefore(@Param("cutoff") cutoff: Instant): Int

    @Modifying
    @Query(
        """
        DELETE FROM ShiftCorrectionRequestEntity s
         WHERE s.shift.id IN (
               SELECT f.id FROM ShiftEntity f WHERE f.clockIn < :cutoff
         )
        """
    )
    fun deleteRequestsBefore(@Param("cutoff") cutoff: Instant): Int

    /**
     * The event log. Filtered on `occurredAt`, which is when the fact happened -
     * the same clock the retention period is measured from - and not on
     * `receivedAt`, which could be days later and would keep events alive past
     * their shift.
     */
    @Modifying
    @Query("DELETE FROM ShiftEventEntity e WHERE e.occurredAt < :cutoff")
    fun deleteEventsBefore(@Param("cutoff") cutoff: Instant): Int

    @Modifying
    @Query("DELETE FROM ShiftEntity f WHERE f.clockIn < :cutoff")
    fun deleteShiftsBefore(@Param("cutoff") cutoff: Instant): Int

    /**
     * The export log (feature 003, D-013). The **only** way a row of
     * `exports` can be deleted: its repository has no delete at all.
     *
     * A row goes once its whole covered range is past the period - `to`
     * before the cut-off date, so every record it refers to is being purged in
     * the same run. One that still covers a single day inside the period stays:
     * it is evidence of who obtained data that still exists (FR-027).
     */
    @Modifying
    @Query("DELETE FROM RegisterExportEntity e WHERE e.to < :cutoff")
    fun deleteExportsBefore(@Param("cutoff") cutoff: LocalDate): Int
}
