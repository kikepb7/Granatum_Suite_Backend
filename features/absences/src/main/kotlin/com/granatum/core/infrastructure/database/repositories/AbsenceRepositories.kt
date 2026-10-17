package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.infrastructure.database.entities.AbsenceEntity
import com.granatum.core.infrastructure.database.entities.VacationEntitlementEntity
import com.granatum.core.infrastructure.database.entities.VacationEntitlementId
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/*
 * Feature 007. Both extend Repository<T, ID> and declare no delete: an absence
 * is cancelled or rejected, never removed (FR-020). AbsenceNoDeletionTest
 * checks it by reflection.
 */

interface AbsenceRepository : Repository<AbsenceEntity, UUID> {

    fun save(absence: AbsenceEntity): AbsenceEntity

    fun findById(id: UUID): Optional<AbsenceEntity>

    /**
     * A person's still-standing absences (pending or approved) that touch
     * [from, to]; an open sick leave (to NULL) touches everything after
     * its start. The overlap check and the balance both read this.
     */
    @Query(
        """
        SELECT a FROM AbsenceEntity a
         WHERE a.employeeId = :employeeId
           AND a.status IN :statuses
           AND a.from <= :toDate
           AND (a.to IS NULL OR a.to >= :fromDate)
        """
    )
    fun findForEmployeeInRange(
        @Param("employeeId") employeeId: UUID,
        @Param("fromDate") from: LocalDate,
        @Param("toDate") to: LocalDate,
        @Param("statuses") statuses: Collection<AbsenceStatus>
    ): List<AbsenceEntity>

    /** The staff calendar, or one person's, over a range, optionally by status. */
    @Query(
        """
        SELECT a FROM AbsenceEntity a
         WHERE (:employeeId IS NULL OR a.employeeId = :employeeId)
           AND (:status IS NULL OR a.status = :status)
           AND a.from <= :toDate
           AND (a.to IS NULL OR a.to >= :fromDate)
         ORDER BY a.from, a.id
        """
    )
    fun search(
        @Param("employeeId") employeeId: UUID?,
        @Param("status") status: AbsenceStatus?,
        @Param("fromDate") from: LocalDate,
        @Param("toDate") to: LocalDate
    ): List<AbsenceEntity>
}

interface VacationEntitlementRepository : Repository<VacationEntitlementEntity, VacationEntitlementId> {

    fun save(entitlement: VacationEntitlementEntity): VacationEntitlementEntity

    fun findById(id: VacationEntitlementId): Optional<VacationEntitlementEntity>
}
