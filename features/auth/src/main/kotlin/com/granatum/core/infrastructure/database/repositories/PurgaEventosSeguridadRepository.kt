package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.EventoSeguridadEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The only code that deletes security events, and only past their retention
 * period - the same split as timetracking's RetencionPurgaRepository.
 * EventoSeguridadRepository stays append-only: nothing that writes events can
 * delete them, and this interface can only delete what the cut-off allows.
 */
interface PurgaEventosSeguridadRepository : Repository<EventoSeguridadEntity, UUID> {

    @Transactional
    @Modifying
    @Query("DELETE FROM EventoSeguridadEntity e WHERE e.ocurridoEn < :corte")
    fun depurarAnterioresA(@Param("corte") corte: Instant): Int
}
