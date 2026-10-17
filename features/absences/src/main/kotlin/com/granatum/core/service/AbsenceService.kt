package com.granatum.core.service

import com.granatum.core.domain.contract.EmployeeDirectory
import com.granatum.core.domain.event.DomainNotice
import com.granatum.core.domain.event.NoticeType
import com.granatum.core.domain.exception.AbsenceEmployeeInactiveException
import com.granatum.core.domain.exception.AbsenceEmployeeNotFoundException
import com.granatum.core.domain.exception.AbsenceNotFoundException
import com.granatum.core.domain.exception.AbsenceNotModifiableException
import com.granatum.core.domain.exception.AbsenceOverlapException
import com.granatum.core.domain.exception.InsufficientVacationBalanceException
import com.granatum.core.domain.exception.InvalidAbsenceDataException
import com.granatum.core.domain.exception.InvalidAbsenceRangeException
import com.granatum.core.domain.exception.OwnAbsenceResolutionException
import com.granatum.core.domain.model.Absence
import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.domain.model.AbsenceType
import com.granatum.core.domain.model.LeaveCause
import com.granatum.core.domain.model.VacationBalance
import com.granatum.core.domain.service.AbsenceBalanceCalculator
import com.granatum.core.domain.type.EmployeeStatus
import com.granatum.core.domain.type.EntityId
import com.granatum.core.infrastructure.database.entities.AbsenceEntity
import com.granatum.core.infrastructure.database.entities.VacationEntitlementEntity
import com.granatum.core.infrastructure.database.entities.VacationEntitlementId
import com.granatum.core.infrastructure.database.repositories.AbsenceRepository
import com.granatum.core.infrastructure.database.repositories.VacationEntitlementRepository
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What someone asks for or registers. `toString` hides the comment (FR-021). */
data class NewAbsence(
    val type: AbsenceType,
    val cause: LeaveCause?,
    val from: LocalDate,
    val to: LocalDate?,
    val comment: String?
) {
    override fun toString(): String = "NewAbsence(type=$type, from=$from, to=$to)"
}

/**
 * Absences (feature 007): request, record, resolve, cancel, discharge a sick
 * leave, query, balance and entitlement.
 *
 * ## The per-person lock
 *
 * Creating an absence takes a transaction-scoped advisory lock on the person
 * before checking overlaps and the balance (research.md D-002). Without it two
 * simultaneous requests would both read "no overlap, 12 days left" and both be
 * stored. A database exclusion constraint would need the btree_gist extension,
 * which a migration cannot count on in Supabase - and the balance needs the
 * same serialisation anyway.
 *
 * Resolving and cancelling do not take it: they never add days or ranges.
 * Two resolutions of the same absence at once are stopped by the version.
 */
@Service
class AbsenceService(
    private val absences: AbsenceRepository,
    private val entitlements: VacationEntitlementRepository,
    private val directory: EmployeeDirectory,
    private val entityManager: EntityManager,
    // Feature 008: requests and resolutions become notices, once committed.
    private val notices: ApplicationEventPublisher,
    @param:Value("\${absences.vacation.annual-days}") private val annualDays: Int,
    private val clock: Clock = Clock.systemUTC()
) {

    private val today: LocalDate get() = LocalDate.now(clock.withZone(MADRID))

    /**
     * A person asks for holidays or paid leave for themselves (FR-001,
     * FR-002, FR-011). Pending until a MANAGER or ADMIN resolves it.
     */
    @Transactional
    fun request(employeeId: EntityId, newAbsence: NewAbsence): Absence {
        // FR-006, FR-007: sick leave is recorded by someone else, never
        // self-reported - it is health data and the responsibility of who
        // manages the staff.
        if (newAbsence.type == AbsenceType.SICK_LEAVE) throw OwnAbsenceResolutionException()
        validate(newAbsence)
        val to = newAbsence.to ?: throw InvalidAbsenceRangeException("Indica la fecha de fin")
        if (newAbsence.type == AbsenceType.VACATION && newAbsence.from.isBefore(today)) {
            throw InvalidAbsenceRangeException("No se piden vacaciones que ya han empezado")
        }
        checkEmployee(employeeId)
        return create(employeeId, newAbsence, to, AbsenceStatus.PENDING, author = employeeId).also {
            notices.publishEvent(DomainNotice(NoticeType.ABSENCE_PENDING, it.id, ownerId = employeeId, authorId = employeeId))
        }
    }

    /**
     * A MANAGER or ADMIN records an absence on someone else's behalf,
     * approved from the start: sick leave, or regularising past holidays
     * (FR-006, FR-008, FR-011).
     */
    @Transactional
    fun record(authorId: EntityId, employeeId: EntityId, newAbsence: NewAbsence): Absence {
        if (authorId == employeeId) throw OwnAbsenceResolutionException()
        validate(newAbsence)
        if (newAbsence.to == null && newAbsence.type != AbsenceType.SICK_LEAVE) {
            throw InvalidAbsenceRangeException("Solo una baja médica puede quedar abierta")
        }
        checkEmployee(employeeId)
        // Approved from the start: the person hears about it as an approval.
        return create(employeeId, newAbsence, newAbsence.to, AbsenceStatus.APPROVED, author = authorId).also {
            notices.publishEvent(DomainNotice(NoticeType.ABSENCE_APPROVED, it.id, ownerId = employeeId, authorId = authorId))
        }
    }

    @Transactional
    fun approve(id: EntityId, authorId: EntityId): Absence {
        val absence = pendingOfSomeoneElse(id, authorId)
        absence.approve(authorId, clock.instant())
        notices.publishEvent(DomainNotice(NoticeType.ABSENCE_APPROVED, id, ownerId = absence.employeeId, authorId = authorId))
        return absences.save(absence).toModel()
    }

    @Transactional
    fun reject(id: EntityId, authorId: EntityId, reason: String): Absence {
        val absence = pendingOfSomeoneElse(id, authorId)
        absence.reject(authorId, reason.trim(), clock.instant())
        notices.publishEvent(DomainNotice(NoticeType.ABSENCE_REJECTED, id, ownerId = absence.employeeId, authorId = authorId))
        return absences.save(absence).toModel()
    }

    /**
     * The person cancels a pending request, or approved one that has not
     * started (FR-012). Someone else's absence is "not found", not "forbidden":
     * an EMPLOYEE must not learn that an id exists.
     */
    @Transactional
    fun cancel(id: EntityId, callerId: EntityId): Absence {
        val absence = absences.findById(id).orElse(null)?.takeIf { it.employeeId == callerId }
            ?: throw AbsenceNotFoundException(id)
        when (absence.status) {
            AbsenceStatus.PENDING -> Unit
            AbsenceStatus.APPROVED ->
                if (!absence.from.isAfter(today)) throw AbsenceNotModifiableException("Esa ausencia ya ha empezado")
            else -> throw AbsenceNotModifiableException("Esa ausencia ya está ${STATUS_TEXT.getValue(absence.status)}")
        }
        absence.cancel(clock.instant())
        return absences.save(absence).toModel()
    }

    /** Closes an open sick leave with the date of discharge, once (FR-008). */
    @Transactional
    fun discharge(id: EntityId, authorId: EntityId, to: LocalDate): Absence {
        val absence = absences.findById(id).orElseThrow { AbsenceNotFoundException(id) }
        if (absence.employeeId == authorId) throw OwnAbsenceResolutionException()
        if (absence.type != AbsenceType.SICK_LEAVE || absence.to != null || absence.status != AbsenceStatus.APPROVED) {
            throw AbsenceNotModifiableException("Solo se da de alta una baja médica abierta")
        }
        validateRange(absence.from, to)
        absence.closeSickLeave(to)
        return absences.save(absence).toModel()
    }

    /** One absence; someone else's is "not found" for whoever may only see their own (FR-018). */
    @Transactional(readOnly = true)
    fun get(id: EntityId, callerId: EntityId, seesAll: Boolean): Absence =
        absences.findById(id).orElse(null)?.takeIf { seesAll || it.employeeId == callerId }?.toModel()
            ?: throw AbsenceNotFoundException(id)

    @Transactional(readOnly = true)
    fun search(employeeId: EntityId?, status: AbsenceStatus?, from: LocalDate, to: LocalDate): List<Absence> {
        if (to.isBefore(from)) throw InvalidAbsenceRangeException("La fecha de fin es anterior a la de inicio")
        return absences.search(employeeId, status, from, to).map { it.toModel() }
    }

    @Transactional(readOnly = true)
    fun balance(employeeId: EntityId, year: Int): VacationBalance =
        AbsenceBalanceCalculator.balance(year, entitlement(employeeId, year), standingIn(employeeId, year))

    /** The ADMIN sets a person's entitlement for a year (FR-014). */
    @Transactional
    fun setEntitlement(employeeId: EntityId, year: Int, days: Int, adminId: EntityId) {
        val id = VacationEntitlementId(employeeId, year.toShort())
        val existing = entitlements.findById(id).orElse(null)
        if (existing != null) {
            existing.days = days.toShort()
            existing.updatedBy = adminId
            existing.updatedAt = clock.instant()
            entitlements.save(existing)
        } else {
            entitlements.save(VacationEntitlementEntity(id, days.toShort(), adminId, clock.instant()))
        }
    }

    // --- internals ---------------------------------------------------------

    private fun create(
        employeeId: EntityId,
        newAbsence: NewAbsence,
        to: LocalDate?,
        status: AbsenceStatus,
        author: EntityId
    ): Absence {
        lockEmployee(employeeId)

        // FR-009: an open sick leave runs to "forever" for the overlap check.
        val overlapEnd = to ?: NO_END
        if (absences.findForEmployeeInRange(employeeId, newAbsence.from, overlapEnd, STANDING).isNotEmpty()) {
            throw AbsenceOverlapException()
        }

        if (newAbsence.type == AbsenceType.VACATION) {
            val end = to!!
            for (year in AbsenceBalanceCalculator.years(newAbsence.from, end)) {
                val balance = balance(employeeId, year)
                if (AbsenceBalanceCalculator.daysInYear(newAbsence.from, end, year) > balance.available) {
                    throw InsufficientVacationBalanceException(year, balance.available)
                }
            }
        }

        val now = clock.instant()
        val approved = status == AbsenceStatus.APPROVED
        return absences.save(
            AbsenceEntity(
                employeeId = employeeId,
                type = newAbsence.type,
                cause = newAbsence.cause,
                from = newAbsence.from,
                to = to,
                comment = newAbsence.comment?.trim()?.takeIf { it.isNotEmpty() },
                requestedBy = author,
                requestedAt = now,
                initialStatus = status,
                initialResolvedBy = if (approved) author else null,
                initialResolvedAt = if (approved) now else null
            )
        ).toModel()
    }

    private fun validate(newAbsence: NewAbsence) {
        when (newAbsence.type) {
            AbsenceType.LEAVE ->
                if (newAbsence.cause == null) throw InvalidAbsenceDataException("Un permiso necesita su causa")
            AbsenceType.VACATION ->
                if (newAbsence.cause != null) throw InvalidAbsenceDataException("Solo un permiso lleva causa")
            AbsenceType.SICK_LEAVE -> {
                if (newAbsence.cause != null) throw InvalidAbsenceDataException("Solo un permiso lleva causa")
                // FR-007: no free text on sick leave - it would be health data.
                if (!newAbsence.comment.isNullOrBlank()) {
                    throw InvalidAbsenceDataException("Una baja médica no admite comentarios")
                }
            }
        }
        newAbsence.to?.let { validateRange(newAbsence.from, it) }
    }

    private fun validateRange(from: LocalDate, to: LocalDate) {
        if (to.isBefore(from)) throw InvalidAbsenceRangeException("La fecha de fin es anterior a la de inicio")
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS_BETWEEN_ENDS) {
            throw InvalidAbsenceRangeException("Una ausencia no puede pasar de 366 días; una baja larga se registra abierta")
        }
    }

    private fun checkEmployee(employeeId: EntityId) {
        when (directory.status(employeeId)) {
            null -> throw AbsenceEmployeeNotFoundException(employeeId)
            EmployeeStatus.INACTIVE -> throw AbsenceEmployeeInactiveException()
            EmployeeStatus.ACTIVE -> Unit
        }
    }

    private fun pendingOfSomeoneElse(id: EntityId, authorId: EntityId): AbsenceEntity {
        val absence = absences.findById(id).orElseThrow { AbsenceNotFoundException(id) }
        if (absence.employeeId == authorId) throw OwnAbsenceResolutionException()
        if (absence.status != AbsenceStatus.PENDING) {
            throw AbsenceNotModifiableException("Esa ausencia ya está ${STATUS_TEXT.getValue(absence.status)}")
        }
        return absence
    }

    private fun entitlement(employeeId: EntityId, year: Int): Int =
        entitlements.findById(VacationEntitlementId(employeeId, year.toShort())).map { it.days.toInt() }.orElse(annualDays)

    private fun standingIn(employeeId: EntityId, year: Int): List<Absence> =
        absences.findForEmployeeInRange(employeeId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), STANDING)
            .map { it.toModel() }

    /** research.md D-002. 7007 namespaces this lock among the product's advisory locks. */
    private fun lockEmployee(employeeId: EntityId) {
        entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(7007, hashtext(CAST(:id AS text))) AS text)")
            .setParameter("id", employeeId.toString())
            .singleResult
    }

    companion object {
        private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
        private val STANDING = listOf(AbsenceStatus.PENDING, AbsenceStatus.APPROVED)

        /**
         * How a status reads inside an error message, which stays in Spanish:
         * the enum names are English now, so `name.lowercase()` would no
         * longer give the text people read.
         */
        private val STATUS_TEXT = mapOf(
            AbsenceStatus.PENDING to "pendiente",
            AbsenceStatus.APPROVED to "aprobada",
            AbsenceStatus.REJECTED to "rechazada",
            AbsenceStatus.CANCELLED to "cancelada"
        )

        /** "No end" for the overlap query. Not LocalDate.MAX: it overflows Postgres's DATE. */
        private val NO_END: LocalDate = LocalDate.of(9999, 12, 31)

        /** 366 days inclusive (research.md D-006). */
        private const val MAX_DAYS_BETWEEN_ENDS = 365L
    }
}
