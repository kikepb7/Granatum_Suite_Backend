package com.granatum.core.service

import com.granatum.core.domain.exception.PeriodoInvalidoException
import com.granatum.core.domain.model.Periodo
import com.granatum.core.domain.model.Reporte
import com.granatum.core.domain.model.TrimestreCerrado
import com.granatum.core.domain.service.CalculadoraReporte
import com.granatum.core.infrastructure.database.aDominio
import com.granatum.core.infrastructure.database.entities.TrimestreEventoEntity
import com.granatum.core.infrastructure.database.entities.TrimestreId
import com.granatum.core.infrastructure.database.repositories.FacturaRepository
import com.granatum.core.infrastructure.database.repositories.TrimestreEventoRepository
import com.granatum.core.infrastructure.database.repositories.TrimestreRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Reports by period (FR-018 to FR-023, FR-033). The figures all come from [CalculadoraReporte]. */
@Service
class ReportesFacturacion(
    private val facturas: FacturaRepository,
    private val trimestres: TrimestreRepository,
    private val eventos: TrimestreEventoRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    /** The period asked for, or `PERIODO_INVALIDO` for an impossible combination. */
    fun periodo(tipo: String, anio: Int, mes: Int?, trimestre: Int?): Periodo {
        if (anio !in 2000..2100) throw PeriodoInvalidoException("El ano tiene que estar entre 2000 y 2100")
        return when (tipo.uppercase()) {
            "MENSUAL" -> {
                if (trimestre != null) throw PeriodoInvalidoException("Un periodo mensual no lleva trimestre")
                if (mes == null || mes !in 1..12) throw PeriodoInvalidoException("El mes tiene que estar entre 1 y 12")
                Periodo.Mensual(anio, mes)
            }
            "TRIMESTRAL" -> {
                if (mes != null) throw PeriodoInvalidoException("Un periodo trimestral no lleva mes")
                if (trimestre == null || trimestre !in 1..4) throw PeriodoInvalidoException("El trimestre tiene que estar entre 1 y 4")
                Periodo.Trimestral(anio, trimestre)
            }
            "ANUAL" -> {
                if (mes != null || trimestre != null) throw PeriodoInvalidoException("Un periodo anual no lleva mes ni trimestre")
                Periodo.Anual(anio)
            }
            else -> throw PeriodoInvalidoException("El periodo es MENSUAL, TRIMESTRAL o ANUAL")
        }
    }

    @Transactional(readOnly = true)
    fun calcular(periodo: Periodo): Reporte = CalculadoraReporte.calcular(
        periodo = periodo,
        confirmadas = facturas.findConfirmadasEntre(periodo.desde, periodo.hasta).map { it.aDominio() },
        pendientes = facturas.contarPendientesEntre(periodo.desde, periodo.hasta).toInt(),
        trimestresCerrados = cerrados(periodo),
        calculadoEn = clock.instant()
    )

    /** FR-033: the closed quarters the period touches, and since when. */
    private fun cerrados(periodo: Periodo): List<TrimestreCerrado> {
        val tocados = when (periodo) {
            is Periodo.Mensual -> listOf(Periodo.trimestreDe(periodo.desde))
            is Periodo.Trimestral -> listOf(periodo.trimestre)
            is Periodo.Anual -> (1..4).toList()
        }
        return tocados.mapNotNull { t ->
            val anio = periodo.anio.toShort()
            val fila = trimestres.findById(TrimestreId(anio, t.toShort())).orElse(null)
            if (fila == null || !fila.cerrado) return@mapNotNull null
            val desde = eventos.findAllByAnioAndTrimestreOrderByOcurridoEnAsc(anio, t.toShort())
                .lastOrNull { it.accion == TrimestreEventoEntity.CIERRE }?.ocurridoEn ?: return@mapNotNull null
            TrimestreCerrado(periodo.anio, t, desde)
        }
    }
}
