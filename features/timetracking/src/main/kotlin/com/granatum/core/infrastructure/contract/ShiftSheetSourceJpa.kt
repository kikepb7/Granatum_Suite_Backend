package com.granatum.core.infrastructure.contract

import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.domain.contract.ShiftSheetMonth
import com.granatum.core.domain.contract.ShiftSheetRow
import com.granatum.core.domain.contract.ShiftSheetSource
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.service.RetentionPeriod
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * This module's side of the `ShiftSheetSource` contract (feature 012, US2,
 * research.md D-002), read the way `RegisterExportService.walk` reads the CSV:
 *
 * - **one snapshot**: a single read-only `REPEATABLE READ` transaction, so a
 *   correction approved half-way through cannot land in half of the month;
 * - **in batches** of `timetracking.export.batch-size`, walked by key
 *   `(clockIn, id)`, clearing the persistence context after each, so nothing
 *   read is ever flushed back (FR-013);
 * - **current values**: approved corrections are already applied to `shifts`;
 * - **in Europe/Madrid** ([CivilDateRange.ZONE]): the month's bounds, and the
 *   date and times of each row. A shift belongs to the date of its clock-in.
 *
 * Unlike the CSV, the rows are ordered by date and clock-in across the whole
 * staff (spec US2 scenario 1), not grouped by person. Times are cut to the
 * minute: the sheet shows `HH:mm`.
 */
@Component
class ShiftSheetSourceJpa(
    private val shifts: ShiftRepository,
    private val employees: EmployeeRepository,
    private val retention: RetentionPeriod,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${timetracking.export.batch-size:500}") private val batchSize: Int,
    @param:Value("\${timetracking.export.timeout-seconds:120}") timeoutSeconds: Int
) : ShiftSheetSource {

    private val readTransaction = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        timeout = timeoutSeconds
    }

    override fun retentionCutoff(): LocalDate = retention.cutoffDate()

    override fun month(month: YearMonth): ShiftSheetMonth = readTransaction.execute {
        val (start, end) = CivilDateRange.range(month.atDay(1), month.atEndOfMonth())
        // People are few and appear in many shifts: each is read once.
        val people = mutableMapOf<UUID, EmployeeEntity>()
        val rows = mutableListOf<ShiftSheetRow>()
        val pending = mutableSetOf<UUID>()

        // Before the first possible key, as in RegisterExportService.walk.
        var previousClockIn = start.minusNanos(1_000)
        var previousId = UUID(0, 0)
        while (true) {
            val ids = shifts.findNextBatchIdsOfAnyone(start, end, previousClockIn, previousId, PageRequest.of(0, batchSize))
            if (ids.isEmpty()) break
            val byId = shifts.findAllByIdIn(ids).associateBy { it.id }
            val batch = ids.map { byId.getValue(it) }

            val missing = batch.map { it.employee.id }.filterNot(people::containsKey).toSet()
            if (missing.isNotEmpty()) employees.findAllByIdIn(missing).forEach { people[it.id] = it }

            batch.forEach { shift ->
                val person = people.getValue(shift.employee.id)
                if (person.lastName == null) pending += person.id
                rows += ShiftSheetRow(
                    name = person.name,
                    lastName = person.lastName,
                    identityDocument = person.identityDocument,
                    date = CivilDateRange.civilDate(shift.clockIn),
                    clockIn = time(shift.clockIn),
                    clockOut = shift.clockOut?.let(::time)
                )
            }

            val last = batch.last()
            previousClockIn = last.clockIn
            previousId = last.id
            entityManager.clear()
            if (ids.size < batchSize) break
        }
        ShiftSheetMonth(rows, pending.size)
    }!!

    private fun time(instant: java.time.Instant): LocalTime =
        instant.atZone(CivilDateRange.ZONE).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
}
