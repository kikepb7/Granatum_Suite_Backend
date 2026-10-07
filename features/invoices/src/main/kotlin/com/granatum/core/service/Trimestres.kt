package com.granatum.core.service

import com.granatum.core.api.dto.aDto
import com.granatum.core.domain.exception.PeriodoInvalidoException
import com.granatum.core.domain.exception.TrimestreAbiertoException
import com.granatum.core.domain.exception.TrimestreCerradoException
import com.granatum.core.domain.exception.TrimestreConPendientesException
import com.granatum.core.domain.model.Periodo
import com.granatum.core.infrastructure.database.JsonFacturacion
import com.granatum.core.infrastructure.database.entities.TrimestreEventoEntity
import com.granatum.core.infrastructure.database.repositories.FacturaRepository
import com.granatum.core.infrastructure.database.repositories.TrimestreEventoRepository
import com.granatum.core.infrastructure.database.repositories.TrimestreRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class EventoTrimestre(val accion: String, val autorId: UUID, val ocurridoEn: Instant, val motivo: String?)

data class EstadoTrimestre(val trimestre: Int, val cerrado: Boolean, val eventos: List<EventoTrimestre>)

/**
 * Closing and reopening fiscal quarters (FR-030 to FR-033, research.md D-016).
 *
 * A close takes the quarter's row lock - the same one every change to an
 * invoice of the quarter takes ([BloqueoTrimestres]) - so it waits for any
 * change in flight and nothing slips in after it. It refuses while drafts or
 * pending invoices of the quarter remain, and stores a snapshot of the report
 * totals: anyone can check later that a declared quarter has not changed.
 */
@Service
class Trimestres(
    private val bloqueo: BloqueoTrimestres,
    private val trimestres: TrimestreRepository,
    private val eventos: TrimestreEventoRepository,
    private val facturas: FacturaRepository,
    private val reportes: ReportesFacturacion,
    private val json: JsonFacturacion,
    private val clock: Clock = Clock.systemUTC()
) {
    @Transactional(readOnly = true)
    fun estado(anio: Int): List<EstadoTrimestre> {
        val filas = trimestres.findAllByIdAnioOrderByIdTrimestre(anio.toShort()).associateBy { it.id.trimestre.toInt() }
        val porTrimestre = eventos.findAllByAnioOrderByOcurridoEnAsc(anio.toShort()).groupBy { it.trimestre.toInt() }
        return (1..4).map { t ->
            EstadoTrimestre(
                t,
                filas[t]?.cerrado ?: false,
                porTrimestre[t].orEmpty().map { EventoTrimestre(it.accion, it.autorId, it.ocurridoEn, it.motivo) }
            )
        }
    }

    @Transactional
    fun cerrar(anio: Int, trimestre: Int, autor: UUID) {
        validar(anio, trimestre)
        val fila = bloqueo.bloquear(anio, trimestre)
        if (fila.cerrado) throw TrimestreCerradoException(anio, trimestre)

        val periodo = Periodo.Trimestral(anio, trimestre)
        val pendientes = facturas.contarPendientesEntre(periodo.desde, periodo.hasta)
        if (pendientes > 0) throw TrimestreConPendientesException(pendientes.toInt())

        val reporte = reportes.calcular(periodo)
        eventos.save(
            TrimestreEventoEntity(
                anio = anio.toShort(), trimestre = trimestre.toShort(), accion = TrimestreEventoEntity.CIERRE,
                motivo = null,
                // Amounts as decimal strings, as in the API: a JSON number would
                // come back as 352.0 and lose the cents' scale this record exists to prove.
                totales = json.escribir(reporte.aDto().let { mapOf("emitidas" to it.emitidas, "recibidas" to it.recibidas) }),
                autorId = autor, ocurridoEn = clock.instant()
            )
        )
        fila.cerrado = true
    }

    @Transactional
    fun reabrir(anio: Int, trimestre: Int, motivo: String, autor: UUID) {
        validar(anio, trimestre)
        val fila = bloqueo.bloquear(anio, trimestre)
        if (!fila.cerrado) throw TrimestreAbiertoException(anio, trimestre)
        eventos.save(
            TrimestreEventoEntity(
                anio = anio.toShort(), trimestre = trimestre.toShort(), accion = TrimestreEventoEntity.REAPERTURA,
                motivo = motivo.trim(), totales = null, autorId = autor, ocurridoEn = clock.instant()
            )
        )
        fila.cerrado = false
    }

    private fun validar(anio: Int, trimestre: Int) {
        if (anio !in 2000..2100 || trimestre !in 1..4) throw PeriodoInvalidoException("Trimestre $anio-T$trimestre fuera de rango")
    }
}
