package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.infrastructure.database.entities.AusenciaEntity
import com.granatum.core.infrastructure.database.entities.DerechoVacacionesEntity
import com.granatum.core.infrastructure.database.entities.DerechoVacacionesId
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/*
 * Feature 007. Both extend Repository<T, ID> and declare no delete: an absence
 * is cancelled or rejected, never removed (FR-020). SinBorradoAusenciasTest
 * checks it by reflection.
 */

interface AusenciaRepository : Repository<AusenciaEntity, UUID> {

    fun save(ausencia: AusenciaEntity): AusenciaEntity

    fun findById(id: UUID): Optional<AusenciaEntity>

    /**
     * A person's still-standing absences (pending or approved) that touch
     * [desde, hasta]; an open sick leave (hasta NULL) touches everything after
     * its start. The overlap check and the balance both read this.
     */
    @Query(
        """
        SELECT a FROM AusenciaEntity a
         WHERE a.empleadoId = :empleadoId
           AND a.estado IN :estados
           AND a.desde <= :hasta
           AND (a.hasta IS NULL OR a.hasta >= :desde)
        """
    )
    fun deEmpleadoEnRango(
        @Param("empleadoId") empleadoId: UUID,
        @Param("desde") desde: LocalDate,
        @Param("hasta") hasta: LocalDate,
        @Param("estados") estados: Collection<EstadoAusencia>
    ): List<AusenciaEntity>

    /** The staff calendar, or one person's, over a range, optionally by state. */
    @Query(
        """
        SELECT a FROM AusenciaEntity a
         WHERE (:empleadoId IS NULL OR a.empleadoId = :empleadoId)
           AND (:estado IS NULL OR a.estado = :estado)
           AND a.desde <= :hasta
           AND (a.hasta IS NULL OR a.hasta >= :desde)
         ORDER BY a.desde, a.id
        """
    )
    fun buscar(
        @Param("empleadoId") empleadoId: UUID?,
        @Param("estado") estado: EstadoAusencia?,
        @Param("desde") desde: LocalDate,
        @Param("hasta") hasta: LocalDate
    ): List<AusenciaEntity>
}

interface DerechoVacacionesRepository : Repository<DerechoVacacionesEntity, DerechoVacacionesId> {

    fun save(derecho: DerechoVacacionesEntity): DerechoVacacionesEntity

    fun findById(id: DerechoVacacionesId): Optional<DerechoVacacionesEntity>
}
