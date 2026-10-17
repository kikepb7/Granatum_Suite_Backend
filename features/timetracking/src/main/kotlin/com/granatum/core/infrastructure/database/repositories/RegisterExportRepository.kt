package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.RegisterExportEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The export log (FR-025 to FR-029). Insert and read, **no delete**: that is
 * what FR-027 rests on. The only removal is the retention purge in
 * [RetentionDeletionRepository], bounded by the cut-off, and
 * `NoDeletionWithinRetentionIT` fails if a deletion appears here.
 */
interface RegisterExportRepository : Repository<RegisterExportEntity, UUID> {

    fun save(exportService: RegisterExportEntity): RegisterExportEntity

    /**
     * Every export whose bytes had this fingerprint. Not unique: two identical
     * exports give the same file, and both are answers (FR-011, FR-028).
     */
    fun findAllByFingerprintOrderByGeneratedAtAsc(fingerprint: String): List<RegisterExportEntity>

    /**
     * FR-029, with every filter optional and combinable:
     *
     * - by the person exported - **staff exports included**: they contain
     *   everyone, so they answer "who obtained X's data?" as much as a
     *   personal one does, and an audit that left them out would miss exactly
     *   the broadest disclosures;
     * - by **when** it was generated, `[generatedFrom, generatedTo)`: "what was
     *   exported this week?";
     * - by the **period covered**, by overlap: an export from February to April
     *   answers "who got March's data?", which is the question of an audit.
     */
    @Query(
        // Native, with explicit casts: a null parameter in `:p IS NULL` reaches
        // Postgres without a type, and it refuses the statement ("could not
        // determine data type of parameter") for timestamps and dates.
        value = """
        SELECT * FROM exports e
         WHERE (CAST(:employeeId AS uuid) IS NULL
                OR e.employee_id = CAST(:employeeId AS uuid)
                OR e.scope = 'STAFF')
           AND (CAST(:generatedFrom AS timestamptz) IS NULL OR e.generated_at >= CAST(:generatedFrom AS timestamptz))
           AND (CAST(:generatedTo AS timestamptz) IS NULL OR e.generated_at < CAST(:generatedTo AS timestamptz))
           AND (CAST(:coversTo AS date) IS NULL OR e.from_date <= CAST(:coversTo AS date))
           AND (CAST(:coversFrom AS date) IS NULL OR e.to_date >= CAST(:coversFrom AS date))
         ORDER BY e.generated_at DESC, e.id
        """,
        nativeQuery = true
    )
    fun search(
        @Param("employeeId") employeeId: UUID?,
        @Param("generatedFrom") generatedFrom: Instant?,
        @Param("generatedTo") generatedTo: Instant?,
        @Param("coversFrom") coversFrom: LocalDate?,
        @Param("coversTo") coversTo: LocalDate?
    ): List<RegisterExportEntity>
}
