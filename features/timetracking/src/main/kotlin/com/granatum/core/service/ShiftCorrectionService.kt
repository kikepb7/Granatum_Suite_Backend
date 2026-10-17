package com.granatum.core.service

import com.granatum.core.domain.exception.ShiftNotFinishedException
import com.granatum.core.domain.exception.ShiftNotFoundException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.CorrectionRequestNotFoundException
import com.granatum.core.domain.exception.CorrectionRequestAlreadyResolvedException
import com.granatum.core.domain.model.ShiftCorrectionModel
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.model.BreakValues
import com.granatum.core.domain.service.ShiftValuesValidator
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.CorrectionStatus
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.ShiftValuesJson
import com.granatum.core.infrastructure.database.entities.ShiftEntity
import com.granatum.core.infrastructure.database.entities.ShiftBreakEntity
import com.granatum.core.infrastructure.database.entities.ShiftCorrectionRequestEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.infrastructure.database.repositories.ShiftCorrectionRequestRepository
import com.granatum.core.domain.event.DomainNotice
import com.granatum.core.domain.event.NoticeType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The only path by which a finalised fichaje changes value (constitution
 * principle III). There is no other write path to a CLOSED or INCOMPLETE
 * fichaje anywhere in the module, and none must be added.
 */
@Service
class ShiftCorrectionService(
    private val correctionRepository: ShiftCorrectionRequestRepository,
    private val shiftRepository: ShiftRepository,
    private val json: ShiftValuesJson,
    // Feature 008: requests and resolutions become notices. Published inside
    // the transaction; the notices module only acts once it commits.
    private val notices: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemUTC()
) {

    @Transactional
    fun request(
        shiftId: UUID,
        requesterId: UUID,
        role: Role,
        reason: String,
        proposed: ShiftValues
    ): ShiftCorrectionModel {
        val shift = shiftRepository.findById(shiftId)
            .orElseThrow { ShiftNotFoundException(shiftId) }

        // An EMPLOYEE may only correct their own. MANAGER and ADMIN may act on
        // anyone's; REPRESENTATIVE reaches no write path at all.
        if (role == Role.EMPLOYEE && shift.employee.id != requesterId) {
            throw ForbiddenException("El fichaje no pertenece al usuario autenticado")
        }
        if (role == Role.REPRESENTATIVE) {
            throw ForbiddenException("El rol REPRESENTANTE no puede solicitar correcciones")
        }

        // Only finalised shifts. An open one is fixed by carrying on with the
        // day, not by filing a correction against it.
        if (shift.status == ShiftStatus.IN_PROGRESS) throw ShiftNotFinishedException()

        // Rejected here as well as at approval time, so an incoherent proposal
        // never reaches a reviewer's queue in the first place.
        ShiftValuesValidator.validate(proposed)

        val correctionRequest = correctionRepository.save(
            ShiftCorrectionRequestEntity(
                shift = shift,
                requesterId = requesterId,
                reason = reason,
                proposedValues = json.write(proposed),
                status = CorrectionStatus.PENDING
            )
        )

        notices.publishEvent(
            DomainNotice(NoticeType.CORRECTION_PENDING, correctionRequest.id, ownerId = shift.employee.id, authorId = requesterId)
        )
        return correctionRequest.toModel(json)
    }

    @Transactional
    fun approve(requestId: UUID, resolverId: UUID, role: Role): ShiftCorrectionModel {
        val correctionRequest = loadResolvable(requestId, resolverId, role)
        val shift = correctionRequest.shift

        val proposed = json.read(correctionRequest.proposedValues)

        // Re-validated at approval time and not only when requested: another
        // correction may have been approved in between, so the proposal has to
        // be coherent against the fichaje as it stands now.
        val minutes = ShiftValuesValidator.validate(proposed)

        // Snapshot the state immediately BEFORE applying. This is what makes
        // the original recoverable (FR-017), and it has to happen first -
        // afterwards the previous values no longer exist anywhere.
        correctionRequest.originalValues = json.write(shift.toValues())

        applyValues(shift, proposed, minutes)
        shiftRepository.save(shift)
        correctionRepository.save(correctionRequest)

        // Claimed LAST, and conditionally.
        //
        // The order is deliberate: both of two simultaneous approvals would get
        // this far, and then exactly one wins the conditional update while the
        // loser sees zero rows and throws - rolling back everything it just did
        // to the fichaje. Claiming first and applying afterwards would leave the
        // winner's work outside the claim, so a failure in between would mark a
        // request resolved without its correction applied.
        claim(
            requestId = requestId,
            newStatus = CorrectionStatus.APPROVED,
            resolverId = resolverId,
            resolutionReason = null
        )
        notices.publishEvent(
            DomainNotice(NoticeType.CORRECTION_APPROVED, requestId, ownerId = shift.employee.id, authorId = resolverId)
        )

        return correctionRepository.findById(requestId).orElseThrow {
            CorrectionRequestNotFoundException(requestId)
        }.toModel(json)
    }

    @Transactional
    fun reject(
        requestId: UUID,
        resolverId: UUID,
        role: Role,
        resolutionReason: String
    ): ShiftCorrectionModel {
        // Read now: reclamar's conditional update clears the persistence
        // context, and the lazy fichaje would no longer load afterwards.
        val ownerId = loadResolvable(requestId, resolverId, role).shift.employee.id

        // originalValues stays null: nothing was ever applied, so there is no
        // "before" to record.
        claim(
            requestId = requestId,
            newStatus = CorrectionStatus.REJECTED,
            resolverId = resolverId,
            resolutionReason = resolutionReason
        )
        notices.publishEvent(
            DomainNotice(NoticeType.CORRECTION_REJECTED, requestId, ownerId = ownerId, authorId = resolverId)
        )

        return correctionRepository.findById(requestId).orElseThrow {
            CorrectionRequestNotFoundException(requestId)
        }.toModel(json)
    }

    @Transactional(readOnly = true)
    fun findByShift(shiftId: UUID): List<ShiftCorrectionModel> =
        correctionRepository.findAllByShiftIdOrderByCreatedAtDesc(shiftId)
            .map { it.toModel(json) }

    @Transactional(readOnly = true)
    fun findByStatus(status: CorrectionStatus): List<ShiftCorrectionModel> =
        correctionRepository.findAllByStatus(status).map { it.toModel(json) }

    /**
     * Moves the request out of PENDING, or fails if someone else already did.
     *
     * The row count is the whole mechanism: zero means another transaction won
     * the race, and throwing here rolls back this one's work.
     */
    private fun claim(
        requestId: UUID,
        newStatus: CorrectionStatus,
        resolverId: UUID,
        resolutionReason: String?
    ) {
        val rowCount = correctionRepository.resolveIfStillPending(
            id = requestId,
            newStatus = newStatus,
            resolverId = resolverId,
            resolvedAt = clock.instant(),
            resolutionReason = resolutionReason
        )
        if (rowCount == 0) throw CorrectionRequestAlreadyResolvedException()
    }

    private fun loadResolvable(
        requestId: UUID,
        resolverId: UUID,
        role: Role
    ): ShiftCorrectionRequestEntity {
        val correctionRequest = correctionRepository.findById(requestId)
            .orElseThrow { CorrectionRequestNotFoundException(requestId) }

        // Only MANAGER and ADMIN resolve (FR-015).
        if (role != Role.MANAGER && role != Role.ADMIN) {
            throw ForbiddenException("Solo ENCARGADO o ADMIN pueden resolver una correccion")
        }

        // Nobody resolves their own request, whatever their role.
        //
        // Stricter than FR-015 as originally written, deliberately. That
        // requirement said "prevent the requester from resolving it when their
        // role is EMPLOYEE", which is vacuous next to the clause above: an
        // EMPLOYEE never reaches this point. The case that can actually happen
        // is an MANAGER filing a correction for their own shift and approving
        // it themselves - they are an employee too, with their own fichajes -
        // and that is precisely the self-approval the law's approval step
        // exists to prevent. FR-015a was added to the spec to say so.
        if (correctionRequest.requesterId == resolverId) {
            throw ForbiddenException("Quien solicita una correccion no puede resolverla")
        }

        // Both APPROVED and REJECTED are terminal (FR-019). Checked here, and
        // the state transition below is guarded again by the write itself, so
        // two simultaneous approvals cannot both apply.
        if (correctionRequest.status != CorrectionStatus.PENDING) throw CorrectionRequestAlreadyResolvedException()

        return correctionRequest
    }

    /**
     * Replaces the shift's times and its whole break list.
     *
     * `orphanRemoval = true` on the association is what makes removing a break
     * actually delete its row; clearing the list without it would leave orphans
     * pointing at the fichaje.
     */
    private fun applyValues(shift: ShiftEntity, values: ShiftValues, minutes: Int) {
        shift.clockIn = values.clockIn
        shift.clockOut = values.clockOut
        shift.workedMinutes = minutes

        shift.breaks.clear()
        values.breaks.forEach { p ->
            shift.addBreak(ShiftBreakEntity(type = p.type, startedAt = p.start, endedAt = p.end))
        }

        // An INCOMPLETE shift completed by a correction becomes CLOSED, but
        // `wasIncomplete` is deliberately NOT reset (FR-012c, SC-010): a
        // reconstructed day must stay distinguishable from one closed at the
        // time, so an inspection report can say which is which.
        shift.status = ShiftStatus.CLOSED
    }

    private fun ShiftEntity.toValues() = ShiftValues(
        clockIn = clockIn,
        // A finalised fichaje always has an exit, except an INCOMPLETE one -
        // which is exactly the case a correction supplies it for. Falling back
        // to the entry keeps the snapshot well-formed without inventing a time.
        clockOut = clockOut ?: clockIn,
        breaks = breaks.mapNotNull { p ->
            p.endedAt?.let { BreakValues(p.type, p.startedAt, it) }
        }
    )
}
