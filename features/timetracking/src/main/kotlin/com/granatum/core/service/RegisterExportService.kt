package com.granatum.core.service

import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.domain.exception.EmployeeNotFoundException
import com.granatum.core.domain.exception.ExportBusyException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.model.AppliedCorrection
import com.granatum.core.domain.model.RegisterRow
import com.granatum.core.domain.model.EffectiveRange
import com.granatum.core.domain.model.BreakSpan
import com.granatum.core.domain.model.OriginalShiftValues
import com.granatum.core.domain.service.MonthlySummaryCalculator
import com.granatum.core.domain.service.RegisterCsvWriter
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.CorrectionStatus
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.ShiftValuesJson
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.entities.ShiftEntity
import com.granatum.core.infrastructure.database.entities.ShiftCorrectionRequestEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.domain.model.ShiftModel
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.infrastructure.database.repositories.ShiftCorrectionRequestRepository
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.io.OutputStream
import java.lang.reflect.UndeclaredThrowableException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.text.Collator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands the register over as a file (FR-001 to FR-018).
 *
 * ## One read transaction, one snapshot (D-002)
 *
 * The whole file is read inside **one** read-only `REPEATABLE READ`
 * transaction. In Postgres that is snapshot isolation: every query sees the
 * database as it was at the first one. A transaction per batch would let a
 * correction approved mid-export land in the second half of the file and not
 * the first, producing a file that was never the register at any moment.
 *
 * The rows are streamed in batches of `tamano-batch`, walked by key, with four
 * queries per batch and none per row: the ids, the shifts with their breaks,
 * the approved corrections, and the names of whoever requested and approved
 * them. Four rather than the three the plan estimated, because fetching the
 * breaks in the paginated query would make Hibernate paginate in memory (see
 * [ShiftRepository.findNextBatchIds]).
 *
 * ## The audit row goes in afterwards (D-003)
 *
 * [RegisterExportLog] is called only once the read transaction has closed,
 * complete or not. Writing inside it would take a second connection while the
 * first is held, which is how feature 002 exhausted the pool.
 *
 * ## Exporting writes nothing to the register (FR-018)
 *
 * The transaction is read-only, and the persistence context is cleared after
 * each batch, so nothing read here is ever flushed back.
 */
@Service
class RegisterExportService(
    private val employeeRepository: EmployeeRepository,
    private val shiftRepository: ShiftRepository,
    private val correctionRepository: ShiftCorrectionRequestRepository,
    private val json: ShiftValuesJson,
    private val retentionPeriod: RetentionPeriod,
    private val exportLog: RegisterExportLog,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${timetracking.export.batch-size:500}")
    private val batchSize: Int,
    @param:Value("\${timetracking.export.timeout-seconds:120}")
    timeoutSeconds: Int,
    @param:Value("\${timetracking.export.concurrency:2}")
    concurrency: Int,
    @param:Value("\${timetracking.export.wait-ms:1000}")
    private val waitMs: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    /**
     * Exports in flight (D-002). Each one holds a connection for as long as its
     * client takes to read, and the pool is shared with everything else, so
     * the cap is what keeps a few slow downloads from starving the API. Fair,
     * like `VerificadorAcotado` in `auth`, so a waiting export is served in
     * order.
     */
    private val permits = Semaphore(concurrency, true)

    private val readTransaction = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        timeout = timeoutSeconds
    }

    /** Validates, authorises and trims, then writes. See [prepare] and [write]. */
    fun export(
        scope: ExportTarget,
        from: LocalDate,
        to: LocalDate,
        requesterId: UUID,
        role: Role,
        output: OutputStream
    ): RegisterExportResult = write(prepare(scope, from, to, requesterId, role), output)

    /**
     * One person's calendar month, with the closing block: total, contract type
     * and whether the month has ended (FR-019 to FR-023, D-011).
     */
    fun downloadMonthly(
        employeeId: UUID,
        year: Int,
        month: Int,
        requesterId: UUID,
        role: Role,
        output: OutputStream
    ): RegisterExportResult = write(prepareMonthly(employeeId, year, month, requesterId, role), output)

    /** Same rules as the on-screen summary: month 1 to 12, own month for an EMPLOYEE. */
    fun prepareMonthly(
        employeeId: UUID,
        year: Int,
        month: Int,
        requesterId: UUID,
        role: Role
    ): PreparedRegisterExport {
        if (month !in 1..12) throw InconsistentValuesException("el mes debe estar entre 1 y 12")
        val firstDay = LocalDate.of(year, month, 1)
        return prepare(
            ExportTarget.Monthly(employeeId, year, month),
            firstDay,
            firstDay.withDayOfMonth(firstDay.lengthOfMonth()),
            requesterId,
            role
        )
    }

    /**
     * Everything that can refuse the request, done **before** the first byte.
     *
     * Split from [write] for the controller: once a streamed body has
     * started, the status is already `200` and an error can no longer change
     * it. So the range, the ownership and the person are all settled here,
     * while a refusal can still be a proper `4xx`.
     */
    fun prepare(
        scope: ExportTarget,
        from: LocalDate,
        to: LocalDate,
        requesterId: UUID,
        role: Role
    ): PreparedRegisterExport {
        // FR-016
        if (to.isBefore(from)) throw InconsistentValuesException("hasta es anterior a desde")

        when (scope) {
            is ExportTarget.Person -> checkPerson(scope.employeeId, requesterId, role)
            is ExportTarget.Monthly -> checkPerson(scope.employeeId, requesterId, role)
            ExportTarget.Staff ->
                if (role == Role.EMPLOYEE) throw ForbiddenException("Solo puede exportar su propio registro")
        }

        val range = effectiveRange(from, to)

        // Last, once nothing else can refuse the request: a refusal after this
        // point would have to remember to give the permit back.
        return PreparedRegisterExport(scope, range, requesterId, role, reserve())
    }

    /**
     * Taken on the request thread, so saturation is still a `503` with
     * `Retry-After` rather than a `200` that breaks off. Given back by
     * [write], however it ends.
     */
    private fun reserve(): RegisterExportPermit {
        if (!permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) throw ExportBusyException()
        return RegisterExportPermit(permits)
    }

    /**
     * FR-013: an EMPLOYEE is bound to the token subject. The id in the request
     * is compared against it, never trusted.
     */
    private fun checkPerson(employeeId: UUID, requesterId: UUID, role: Role) {
        if (role == Role.EMPLOYEE && employeeId != requesterId) {
            throw ForbiddenException("Solo puede exportar su propio registro")
        }
        if (employeeRepository.findExistingIds(listOf(employeeId)).isEmpty()) {
            throw EmployeeNotFoundException(employeeId)
        }
    }

    /**
     * FR-017, D-009: the part of the range the retention period still covers.
     *
     * Data older than the boundary may still be in the table - the purge is off
     * by default - but it is outside the period, so it is not handed over as if
     * the period did not exist. A range entirely before the boundary is
     * refused: an empty file would read as "no shifts", which is exactly the
     * misreading FR-017 is there to prevent.
     */
    private fun effectiveRange(from: LocalDate, to: LocalDate): EffectiveRange {
        val cutoff = retentionPeriod.cutoffDate()
        if (to.isBefore(cutoff)) {
            throw InconsistentValuesException(
                "el rango es anterior al plazo de conservacion; hay registros desde $cutoff"
            )
        }
        return if (from.isBefore(cutoff)) {
            EffectiveRange(cutoff, to, trimmed = true)
        } else {
            EffectiveRange(from, to, trimmed = false)
        }
    }

    /**
     * Writes the file and records the export, complete or cut short.
     *
     * The fingerprint is SHA-256 over the exact bytes written, BOM included
     * (D-006), so a file can be recognised later by its content alone.
     */
    fun write(prepared: PreparedRegisterExport, output: OutputStream): RegisterExportResult =
        try {
            writeWithPermit(prepared, output)
        } finally {
            // The release must survive any failure: a semaphore that loses its
            // permits is a permanent outage, worse than the one it prevents.
            prepared.permit.release()
        }

    private fun writeWithPermit(prepared: PreparedRegisterExport, output: OutputStream): RegisterExportResult {
        val generatedAt = clock.instant()
        val digest = MessageDigest.getInstance("SHA-256")
        val out = DigestOutputStream(output, digest)
        // D-012, FR-012: REPRESENTATIVE gets the register without the identity
        // document. The column is OMITTED, not left blank: a "Documento" header
        // over empty cells suggests the data exists and is missing. Location
        // never enters RegisterRow at all, for any role (FR-007).
        val includeDocument = prepared.role != Role.REPRESENTATIVE
        var rowCount = 0

        try {
            readTransaction.executeWithoutResult {
                RegisterCsvWriter.writeHeader(out, includeDocument)
                val monthly = prepared.scope as? ExportTarget.Monthly
                val monthShifts = mutableListOf<ShiftModel>()
                val correctedIds = mutableSetOf<UUID>()
                var person: EmployeeEntity? = null

                walk(prepared, includeDocument) { who, shift, row ->
                    RegisterCsvWriter.writeRow(out, row, includeDocument)
                    rowCount++
                    if (monthly != null) {
                        person = who
                        monthShifts += shift.toModel()
                        if (row.corrected) correctedIds += shift.id
                    }
                }

                if (monthly != null) {
                    writeMonthlyClosing(out, monthly, person, monthShifts, correctedIds, includeDocument)
                }
            }
            out.flush()
        } catch (e: Throwable) {
            // TransactionTemplate wraps a checked exception - the IOException of
            // a client that hung up - and the caller should see the real one.
            val cause = (e as? UndeclaredThrowableException)?.undeclaredThrowable ?: e
            try {
                recordExport(prepared, generatedAt, completed = false, rowCount = rowCount, fingerprint = null)
            } catch (failure: Exception) {
                cause.addSuppressed(failure)
            }
            throw cause
        }

        val fingerprint = HexFormat.of().formatHex(digest.digest())
        val recorded = recordExport(prepared, generatedAt, completed = true, rowCount = rowCount, fingerprint = fingerprint)
        return RegisterExportResult(recorded.id, prepared.range, rowCount, fingerprint)
    }

    private fun recordExport(
        prepared: PreparedRegisterExport,
        generatedAt: Instant,
        completed: Boolean,
        rowCount: Int,
        fingerprint: String?
    ) = exportLog.record(
        requesterId = prepared.requesterId,
        role = prepared.role,
        scope = prepared.scope,
        range = prepared.range,
        generatedAt = generatedAt,
        completed = completed,
        rowCount = rowCount,
        fingerprint = fingerprint
    )

    /**
     * The closing block of the monthly download, inside the same snapshot as
     * its rows.
     *
     * The total comes from [MonthlySummaryCalculator], the very function behind
     * the on-screen summary, fed the shifts written above it. Reimplementing it
     * is exactly how two totals end up disagreeing (FR-020, D-011).
     */
    private fun writeMonthlyClosing(
        out: OutputStream,
        monthly: ExportTarget.Monthly,
        withShifts: EmployeeEntity?,
        shifts: List<ShiftModel>,
        correctedIds: Set<UUID>,
        includeDocument: Boolean
    ) {
        // A month without shifts still needs the contract type, so the person is
        // read here when the walk did not already bring them.
        val person = withShifts ?: employeeRepository.findAllByIdIn(listOf(monthly.employeeId)).single()
        val summary = MonthlySummaryCalculator.calculate(
            employeeId = monthly.employeeId,
            year = monthly.year,
            month = monthly.month,
            contractType = person.contractType,
            shifts = shifts,
            correctedShifts = correctedIds
        )
        // FR-022: a month is closed once its last day is over, in Madrid.
        val lastDay = LocalDate.of(monthly.year, monthly.month, 1).let { it.withDayOfMonth(it.lengthOfMonth()) }
        val closed = LocalDate.now(clock.withZone(CivilDateRange.ZONE)).isAfter(lastDay)

        RegisterCsvWriter.writeMonthlyBlock(
            out, summary.totalWorkedMinutes, summary.contractType, closed, includeDocument
        )
    }

    /** Must run inside [readTransaction]: every query here belongs to the same snapshot. */
    private fun walk(
        prepared: PreparedRegisterExport,
        includeDocument: Boolean,
        emit: (EmployeeEntity, ShiftEntity, RegisterRow) -> Unit
    ) {
        val (start, end) = CivilDateRange.range(prepared.range.from, prepared.range.to)
        // Names of requesters and approvers, kept across batches: the same few
        // managers approve most corrections, so they are looked up once.
        val names = mutableMapOf<UUID, String>()

        people(prepared).forEach { person ->
            // Before the first possible key: Postgres keeps microseconds, so one
            // microsecond earlier than the range start precedes every shift in it.
            var previousClockIn = start.minusNanos(1_000)
            var previousId = UUID(0, 0)

            while (true) {
                val ids = shiftRepository.findNextBatchIds(
                    person.id, start, end, previousClockIn, previousId, PageRequest.of(0, batchSize)
                )
                if (ids.isEmpty()) break

                val byId = shiftRepository.findAllWithBreaksByIdIn(ids).associateBy { it.id }
                val shifts = ids.map { byId.getValue(it) }
                val approved = correctionRepository
                    .findAllByShiftIdInAndStatusOrderByResolvedAtAsc(ids, CorrectionStatus.APPROVED)
                    .groupBy { it.shift.id }

                val missing = approved.values.flatten()
                    .flatMap { listOfNotNull(it.requesterId, it.resolvedById) }
                    .filterNot(names::containsKey)
                    .toSet()
                if (missing.isNotEmpty()) {
                    employeeRepository.findAllByIdIn(missing).forEach { names[it.id] = it.name }
                }

                shifts.forEach { f ->
                    emit(person, f, row(person, f, approved[f.id].orEmpty(), names, includeDocument))
                }

                val last = shifts.last()
                previousClockIn = last.clockIn
                previousId = last.id
                // A long export would otherwise keep every shift it has read in
                // memory until the transaction ends.
                entityManager.clear()
                if (ids.size < batchSize) break
            }
        }
    }

    private fun people(prepared: PreparedRegisterExport): List<EmployeeEntity> = when (val scope = prepared.scope) {
        is ExportTarget.Person -> employeeRepository.findAllByIdIn(listOf(scope.employeeId))
        is ExportTarget.Monthly -> employeeRepository.findAllByIdIn(listOf(scope.employeeId))
        ExportTarget.Staff -> staff(prepared.range)
    }

    /**
     * Everyone with shifts in the range, active or not (FR-015), ordered by
     * name and then by id (D-008).
     *
     * Ordered here and not in SQL: `ORDER BY name` depends on the server's
     * collation, which is not the same in local Docker and in Supabase, so the
     * same query could put `Ángel` before or after `Zoe` depending on where it
     * runs - and two exports of the same data would differ (FR-011). A Spanish
     * [Collator] sorts accented letters with their base letter, as a reader
     * expects. The tie is broken by the id's text form: `UUID.compareTo`
     * compares signed halves, an order no reader could predict.
     */
    private fun staff(range: EffectiveRange): List<EmployeeEntity> {
        val (start, end) = CivilDateRange.range(range.from, range.to)
        val ids = shiftRepository.findEmployeeIdsWithShiftsBetween(start, end)
        if (ids.isEmpty()) return emptyList()
        val spanish = Collator.getInstance(Locale.of("es", "ES"))
        return employeeRepository.findAllByIdIn(ids)
            .sortedWith(compareBy(spanish) { e: EmployeeEntity -> e.name }.thenBy { it.id.toString() })
    }

    private fun row(
        person: EmployeeEntity,
        f: ShiftEntity,
        approved: List<ShiftCorrectionRequestEntity>,
        names: Map<UUID, String>,
        includeDocument: Boolean
    ) = RegisterRow(
        person = person.name,
        document = if (includeDocument) person.identityDocument else null,
        position = person.position,
        date = CivilDateRange.civilDate(f.clockIn),
        clockIn = f.clockIn,
        clockOut = f.clockOut,
        breaks = f.breaks
            .sortedWith(compareBy({ it.startedAt }, { it.id.toString() }))
            .map { BreakSpan(it.type, it.startedAt, it.endedAt) },
        // FR-006: only a closed shift has worked time. Anything else is empty,
        // never a zero that would claim nothing was worked.
        workedMinutes = f.workedMinutes.takeIf { f.status == ShiftStatus.CLOSED },
        status = f.status,
        completedAfterwards = f.wasIncomplete,
        corrected = approved.isNotEmpty(),
        original = approved.firstOrNull()?.originalValues?.let(::originals),
        corrections = approved.map {
            AppliedCorrection(
                resolvedAt = it.resolvedAt ?: it.createdAt,
                requester = names[it.requesterId] ?: it.requesterId.toString(),
                approver = it.resolvedById?.let { id -> names[id] ?: id.toString() }.orEmpty()
            )
        }
    )

    /**
     * The values of the FIRST approved correction are the ones actually clocked
     * (D-010): each later approval snapshots values that were already corrected.
     *
     * An INCOMPLETE shift had no exit, and the snapshot stores its entry in that
     * place to stay well-formed (`ShiftCorrectionService.toValues`). Shown as is, it
     * would claim the person left at the moment they arrived, so it is dropped.
     */
    private fun originals(document: String): OriginalShiftValues {
        val v = json.read(document)
        return OriginalShiftValues(
            clockIn = v.clockIn,
            clockOut = v.clockOut.takeIf { it != v.clockIn },
            breaks = v.breaks.map { BreakSpan(it.type, it.start, it.end) }
        )
    }
}

/**
 * A request already validated and authorised, ready to write, holding one of
 * the export permits until [RegisterExportService.write] gives it back.
 */
class PreparedRegisterExport(
    val scope: ExportTarget,
    val range: EffectiveRange,
    val requesterId: UUID,
    val role: Role,
    internal val permit: RegisterExportPermit
)

/** One export permit, returned at most once whatever path ends the export. */
class RegisterExportPermit(private val semaphore: Semaphore) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) semaphore.release()
    }
}

data class RegisterExportResult(
    val exportId: UUID,
    val range: EffectiveRange,
    val rowCount: Int,
    val fingerprint: String
)
