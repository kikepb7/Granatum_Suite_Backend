package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.infrastructure.database.entities.SolicitudRegistroEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Sign-up requests (feature 005). Extends [Repository] and declares no
 * `delete`: a request is never removed, only resolved, and its row is the record
 * of what happened (FR-027). `SinBorradoSolicitudesTest` checks this by
 * reflection.
 *
 * The bulk updates empty the personal fields in the same statement that changes
 * the state - V20's CHECKs would refuse anything else. They are `@Transactional`
 * so they join the caller's transaction or open their own: an `UPDATE` with no
 * transaction at all is refused by JPA.
 */
interface SolicitudRegistroRepository : Repository<SolicitudRegistroEntity, UUID> {

    fun save(solicitud: SolicitudRegistroEntity): SolicitudRegistroEntity

    fun findById(id: UUID): Optional<SolicitudRegistroEntity>

    /** `SELECT ... FOR UPDATE`: two approvals of the same request serialise (D-006). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SolicitudRegistroEntity s WHERE s.id = :id")
    fun bloquear(@Param("id") id: UUID): SolicitudRegistroEntity?

    fun countByEstado(estado: EstadoSolicitudRegistro): Long

    fun findAllByEstadoOrderByCreadaEnAsc(estado: EstadoSolicitudRegistro): List<SolicitudRegistroEntity>

    /**
     * Cancels the other pending requests of the same person - same address or
     * same document - except [excepto] (FR-014, FR-022).
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE SolicitudRegistroEntity s
           SET s.estado = com.granatum.core.domain.type.EstadoSolicitudRegistro.ANULADA,
               s.resueltaEn = :ahora,
               s.email = NULL, s.nombre = NULL, s.documentoIdentidad = NULL,
               s.passwordHash = NULL, s.codigoHash = NULL
         WHERE s.estado = com.granatum.core.domain.type.EstadoSolicitudRegistro.PENDIENTE
           AND (s.email = :email OR s.documentoIdentidad = :documento)
           AND s.id <> :excepto
        """
    )
    fun anularPendientesDe(
        @Param("email") email: String,
        @Param("documento") documento: String,
        @Param("excepto") excepto: UUID,
        @Param("ahora") ahora: Instant
    ): Int

    /** Expires the pending requests created before [corte] (FR-025). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE SolicitudRegistroEntity s
           SET s.estado = com.granatum.core.domain.type.EstadoSolicitudRegistro.CADUCADA,
               s.resueltaEn = :ahora,
               s.email = NULL, s.nombre = NULL, s.documentoIdentidad = NULL,
               s.passwordHash = NULL, s.codigoHash = NULL
         WHERE s.estado = com.granatum.core.domain.type.EstadoSolicitudRegistro.PENDIENTE
           AND s.creadaEn < :corte
        """
    )
    fun caducarAnterioresA(@Param("corte") corte: Instant, @Param("ahora") ahora: Instant): Int
}
