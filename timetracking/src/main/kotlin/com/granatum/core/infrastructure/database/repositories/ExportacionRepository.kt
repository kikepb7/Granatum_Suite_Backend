package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.ExportacionEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The export log (FR-025 to FR-029). Insert and read, **no delete**: that is
 * what FR-027 rests on. The only removal is the retention purge in
 * [RetencionPurgaRepository], bounded by the cut-off, and
 * `SinBorradoDentroDelPlazoIT` fails if a deletion appears here.
 */
interface ExportacionRepository : Repository<ExportacionEntity, UUID> {

    fun save(exportacion: ExportacionEntity): ExportacionEntity

    /**
     * Every export whose bytes had this fingerprint. Not unique: two identical
     * exports give the same file, and both are answers (FR-011, FR-028).
     */
    fun findAllByHuellaOrderByGeneradaEnAsc(huella: String): List<ExportacionEntity>

    /**
     * FR-029, with every filter optional and combinable:
     *
     * - by the person exported - **staff exports included**: they contain
     *   everyone, so they answer "who obtained X's data?" as much as a
     *   personal one does, and an audit that left them out would miss exactly
     *   the broadest disclosures;
     * - by **when** it was generated, `[generadaDesde, generadaHasta)`: "what was
     *   exported this week?";
     * - by the **period covered**, by overlap: an export from February to April
     *   answers "who got March's data?", which is the question of an audit.
     */
    @Query(
        // Native, with explicit casts: a null parameter in `:p IS NULL` reaches
        // Postgres without a type, and it refuses the statement ("could not
        // determine data type of parameter") for timestamps and dates.
        value = """
        SELECT * FROM exportaciones e
         WHERE (CAST(:empleadoId AS uuid) IS NULL
                OR e.empleado_id = CAST(:empleadoId AS uuid)
                OR e.alcance = 'PLANTILLA')
           AND (CAST(:generadaDesde AS timestamptz) IS NULL OR e.generada_en >= CAST(:generadaDesde AS timestamptz))
           AND (CAST(:generadaHasta AS timestamptz) IS NULL OR e.generada_en < CAST(:generadaHasta AS timestamptz))
           AND (CAST(:cubreHasta AS date) IS NULL OR e.desde <= CAST(:cubreHasta AS date))
           AND (CAST(:cubreDesde AS date) IS NULL OR e.hasta >= CAST(:cubreDesde AS date))
         ORDER BY e.generada_en DESC, e.id
        """,
        nativeQuery = true
    )
    fun buscar(
        @Param("empleadoId") empleadoId: UUID?,
        @Param("generadaDesde") generadaDesde: Instant?,
        @Param("generadaHasta") generadaHasta: Instant?,
        @Param("cubreDesde") cubreDesde: LocalDate?,
        @Param("cubreHasta") cubreHasta: LocalDate?
    ): List<ExportacionEntity>
}
