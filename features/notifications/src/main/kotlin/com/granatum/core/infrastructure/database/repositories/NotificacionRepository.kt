package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.NotificacionEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Notices (feature 008). Every method that reads or marks takes the recipient,
 * so no query can reach someone else's notice by id alone (FR-010).
 */
interface NotificacionRepository : Repository<NotificacionEntity, UUID> {

    /** FR-007: the same (recipient, type, reference) only once (D-002). Returns 1 if inserted. */
    @Modifying
    @Query(
        value = """
            INSERT INTO notificaciones (id, destinatario_id, tipo, referencia_id, creada_en)
            VALUES (:id, :destinatario, :tipo, :referencia, :ahora)
            ON CONFLICT (destinatario_id, tipo, referencia_id) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertarSiNoExiste(
        @Param("id") id: UUID,
        @Param("destinatario") destinatario: UUID,
        @Param("tipo") tipo: String,
        @Param("referencia") referencia: UUID,
        @Param("ahora") ahora: Instant
    ): Int

    fun findAllByDestinatarioIdOrderByCreadaEnDescIdDesc(destinatarioId: UUID, pagina: Pageable): List<NotificacionEntity>

    fun findAllByDestinatarioIdAndLeidaEnIsNullOrderByCreadaEnDescIdDesc(destinatarioId: UUID, pagina: Pageable): List<NotificacionEntity>

    fun countByDestinatarioIdAndLeidaEnIsNull(destinatarioId: UUID): Long

    @Modifying
    @Query("UPDATE NotificacionEntity n SET n.leidaEn = :ahora WHERE n.id = :id AND n.destinatarioId = :destinatario AND n.leidaEn IS NULL")
    fun marcarLeida(@Param("id") id: UUID, @Param("destinatario") destinatario: UUID, @Param("ahora") ahora: Instant): Int

    fun existsByIdAndDestinatarioId(id: UUID, destinatarioId: UUID): Boolean

    @Modifying
    @Query("UPDATE NotificacionEntity n SET n.leidaEn = :ahora WHERE n.destinatarioId = :destinatario AND n.leidaEn IS NULL")
    fun marcarTodasLeidas(@Param("destinatario") destinatario: UUID, @Param("ahora") ahora: Instant): Int

    /** D-005: read ones older than [leidasAntes], and any older than [todasAntes]. */
    @Modifying
    @Query(
        """
        DELETE FROM NotificacionEntity n
         WHERE (n.leidaEn IS NOT NULL AND n.creadaEn < :leidasAntes)
            OR n.creadaEn < :todasAntes
        """
    )
    fun limpiar(@Param("leidasAntes") leidasAntes: Instant, @Param("todasAntes") todasAntes: Instant): Int
}
