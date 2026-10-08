package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.EventoSeguridadEntity
import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * APPEND-ONLY, and the interface says so.
 *
 * Extends [Repository] and declares nothing but an insert and two reads. No
 * `delete`, no `deleteById`, no `saveAll` over existing rows. This is the last
 * rule of constitution principle III applied literally, and the lesson of
 * declared debt nº 2: `HistorialMaterialRepository` extends `JpaRepository` over
 * an append-only table, so it offers `delete` to anyone who reaches for it.
 * Nobody calls it today, which is not the same as it being impossible.
 */
interface EventoSeguridadRepository : Repository<EventoSeguridadEntity, UUID> {

    fun save(evento: EventoSeguridadEntity): EventoSeguridadEntity

    fun findAllByCuentaIdOrderByOcurridoEnDesc(cuentaId: UUID): List<EventoSeguridadEntity>

    fun count(): Long

    /** Events with no account to hang them on - feature 005's sign-up events. */
    fun countByTipo(tipo: com.granatum.core.domain.type.TipoEventoSeguridad): Long
}
