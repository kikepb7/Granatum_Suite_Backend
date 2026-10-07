package com.granatum.core.service

import com.granatum.core.domain.exception.TrimestreCerradoException
import com.granatum.core.domain.model.Periodo
import com.granatum.core.infrastructure.database.entities.TrimestreEntity
import com.granatum.core.infrastructure.database.entities.TrimestreId
import com.granatum.core.infrastructure.database.repositories.TrimestreRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * The quarter lock (research.md D-016). Everything that changes an invoice of a
 * quarter, and closing the quarter itself, takes `SELECT … FOR UPDATE` on the
 * quarter's row first: they cannot interleave. Must run inside the caller's
 * transaction.
 */
@Component
class BloqueoTrimestres(private val trimestres: TrimestreRepository) {

    fun bloquear(anio: Int, trimestre: Int): TrimestreEntity {
        trimestres.crearSiNoExiste(anio.toShort(), trimestre.toShort())
        return trimestres.bloquear(TrimestreId(anio.toShort(), trimestre.toShort()))
    }

    fun bloquear(fecha: LocalDate): TrimestreEntity = bloquear(fecha.year, Periodo.trimestreDe(fecha))

    /** Locks the quarter of [fecha] and refuses if it is closed (FR-031). */
    fun exigirAbierto(fecha: LocalDate) {
        val t = bloquear(fecha)
        if (t.cerrado) throw TrimestreCerradoException(fecha.year, Periodo.trimestreDe(fecha))
    }

    /** Without locking: for displaying, not for deciding. */
    fun estaCerrado(fecha: LocalDate): Boolean =
        trimestres.findById(TrimestreId(fecha.year.toShort(), Periodo.trimestreDe(fecha).toShort()))
            .map { it.cerrado }.orElse(false)
}
