package com.granatum.core.domain.service

import com.granatum.core.domain.model.CausaSinCuota
import com.granatum.core.domain.model.Factura
import com.granatum.core.domain.model.Periodo
import com.granatum.core.domain.model.Reporte
import com.granatum.core.domain.model.TipoFactura
import com.granatum.core.domain.model.TotalesGrupo
import com.granatum.core.domain.model.TrimestreCerrado
import java.math.BigDecimal
import java.time.Instant

/**
 * The report of a period from its confirmed invoices (FR-018 to FR-023).
 *
 * One pure function, the single source of every figure: the screen, the CSV,
 * the PDF and the snapshot taken when a quarter is closed all come from here,
 * so they cannot disagree (SC-010). Sums in `BigDecimal`, exact to the cent
 * (research.md D-013).
 *
 * Credit notes carry negative amounts and so subtract (FR-023). A credit note
 * correcting an invoice upwards carries positive amounts and adds, which is
 * what it means.
 */
object CalculadoraReporte {

    private val CERO = BigDecimal.ZERO.setScale(2)

    fun calcular(
        periodo: Periodo,
        confirmadas: List<Factura>,
        pendientes: Int,
        trimestresCerrados: List<TrimestreCerrado>,
        calculadoEn: Instant
    ): Reporte = Reporte(
        periodo = periodo,
        emitidas = totales(confirmadas.filter { it.tipo == TipoFactura.EMITIDA }),
        recibidas = totales(confirmadas.filter { it.tipo == TipoFactura.RECIBIDA }),
        pendientes = pendientes,
        trimestresCerrados = trimestresCerrados,
        calculadoEn = calculadoEn
    )

    private fun totales(facturas: List<Factura>): TotalesGrupo {
        if (facturas.isEmpty()) return TotalesGrupo.VACIO
        val lineas = facturas.flatMap { it.lineas }
        val iva = lineas.filter { it.cuota.signum() != 0 }
            .groupBy { it.tipoIva.setScale(2) }
            .mapValues { (_, ls) -> ls.fold(CERO) { s, l -> s + l.cuota } }
            .toSortedMap()
        val sinCuota = lineas.filter { it.causaSinCuota != null }
            .groupBy { it.causaSinCuota!! }
            .mapValues { (_, ls) -> ls.fold(CERO) { s, l -> s + l.base } }
        return TotalesGrupo(
            facturas = facturas.size,
            base = lineas.fold(CERO) { s, l -> s + l.base },
            ivaPorTipo = LinkedHashMap(iva),
            recargo = lineas.fold(CERO) { s, l -> s + l.recargo },
            retenciones = facturas.fold(CERO) { s, f -> s + f.retenciones },
            total = facturas.fold(CERO) { s, f -> s + (f.total ?: CERO) },
            sinCuota = CausaSinCuota.entries.filter { it in sinCuota }.associateWith { sinCuota.getValue(it) }
        )
    }
}
