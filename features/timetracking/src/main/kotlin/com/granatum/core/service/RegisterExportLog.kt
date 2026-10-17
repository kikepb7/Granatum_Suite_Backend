package com.granatum.core.service

import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.model.EffectiveRange
import com.granatum.core.domain.type.ExportScope
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.RegisterExportEntity
import com.granatum.core.infrastructure.database.repositories.RegisterExportRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import com.granatum.core.api.util.CivilDateRange
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Writes the audit row of one export, once, complete or cut short (D-003).
 *
 * **NEVER CALLED WITH THE READ TRANSACTION STILL OPEN.** An export holds one
 * connection for its whole read; writing here inside it would take a second one
 * while the first is held. In feature 002 exactly that - a write in its own
 * transaction while another stayed open - exhausted the pool under load and
 * froze every request. So the export reads, closes its transaction, and only
 * then calls this.
 *
 * `Propagation.NEVER` makes that rule enforced rather than documented: a call
 * that arrives inside a transaction fails at once instead of quietly taking a
 * second connection. The insert then runs in the repository's own transaction.
 */
@Service
class RegisterExportLog(
    private val exports: RegisterExportRepository
) {
    @Transactional(propagation = Propagation.NEVER)
    fun record(
        requesterId: UUID,
        role: Role,
        scope: ExportTarget,
        range: EffectiveRange,
        generatedAt: Instant,
        completed: Boolean,
        rowCount: Int,
        fingerprint: String?
    ): RegisterExportEntity {
        val (type, employeeId) = when (scope) {
            is ExportTarget.Person -> ExportScope.PERSON to scope.employeeId
            is ExportTarget.Monthly -> ExportScope.MONTHLY to scope.employeeId
            ExportTarget.Staff -> ExportScope.STAFF to null
        }
        return exports.save(
            RegisterExportEntity(
                requesterId = requesterId,
                requesterRole = role,
                scope = type,
                employeeId = employeeId,
                from = range.from,
                to = range.to,
                generatedAt = generatedAt,
                completed = completed,
                rowCount = rowCount,
                // A cut-short file was never received whole, so there is nothing
                // to recognise later; the table enforces the same rule.
                fingerprint = if (completed) fingerprint else null
            )
        )
    }

    /**
     * The log, filtered as FR-029 asks (see [RegisterExportRepository.search]).
     *
     * Generation dates are whole civil days in Madrid, both ends inclusive: an
     * export made at 00:30 on May 8th belongs to May 8th, although in UTC it is
     * still May 7th.
     */
    @Transactional(readOnly = true)
    fun search(
        employeeId: UUID?,
        generatedFrom: LocalDate?,
        generatedTo: LocalDate?,
        coversFrom: LocalDate?,
        coversTo: LocalDate?
    ): List<RegisterExportEntity> =
        exports.search(
            employeeId,
            generatedFrom?.let(CivilDateRange::startOfDay),
            generatedTo?.let(CivilDateRange::endOfDayExclusive),
            coversFrom,
            coversTo
        )
}
