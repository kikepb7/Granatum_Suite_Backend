package com.granatum.core.service

import com.granatum.core.domain.exception.NotificacionNoEncontradaException
import com.granatum.core.domain.model.Notificacion
import com.granatum.core.domain.type.EntityId
import com.granatum.core.infrastructure.database.repositories.NotificacionRepository
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * A person's inbox (feature 008, FR-009, FR-010). Every operation takes the
 * recipient from the token subject; the repository never reaches a notice by
 * id alone.
 */
@Service
class NotificacionService(
    private val notificaciones: NotificacionRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    @Transactional(readOnly = true)
    fun bandeja(destinatario: EntityId, soloNoLeidas: Boolean): List<Notificacion> {
        val pagina = Pageable.ofSize(MAXIMO)
        val filas = if (soloNoLeidas) {
            notificaciones.findAllByDestinatarioIdAndLeidaEnIsNullOrderByCreadaEnDescIdDesc(destinatario, pagina)
        } else {
            notificaciones.findAllByDestinatarioIdOrderByCreadaEnDescIdDesc(destinatario, pagina)
        }
        return filas.map { Notificacion(it.id, it.tipo, it.referenciaId, it.creadaEn, it.leidaEn) }
    }

    @Transactional(readOnly = true)
    fun noLeidas(destinatario: EntityId): Long = notificaciones.countByDestinatarioIdAndLeidaEnIsNull(destinatario)

    /** Marking one already read is fine and changes nothing; someone else's is not found. */
    @Transactional
    fun marcarLeida(id: EntityId, destinatario: EntityId) {
        if (notificaciones.marcarLeida(id, destinatario, clock.instant()) == 0 &&
            !notificaciones.existsByIdAndDestinatarioId(id, destinatario)
        ) {
            throw NotificacionNoEncontradaException(id)
        }
    }

    @Transactional
    fun marcarTodasLeidas(destinatario: EntityId): Int = notificaciones.marcarTodasLeidas(destinatario, clock.instant())

    companion object {
        /** FR-009: an inbox, not an archive. */
        const val MAXIMO = 100
    }
}
