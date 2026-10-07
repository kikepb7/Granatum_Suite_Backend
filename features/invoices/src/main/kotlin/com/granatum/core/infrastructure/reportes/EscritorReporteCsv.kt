package com.granatum.core.infrastructure.reportes

import com.granatum.core.csv.FormatoCsv
import com.granatum.core.domain.model.CausaSinCuota
import com.granatum.core.domain.model.Periodo
import com.granatum.core.domain.model.Reporte
import com.granatum.core.domain.model.TotalesGrupo
import java.io.OutputStream
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The rows of a report, shared by the CSV and the PDF so both show the same thing in the same order. */
object FilasReporte {
    private val MADRID = ZoneId.of("Europe/Madrid")
    private val FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(MADRID)

    /** `1764,00`, `-150,40`: what a Spanish spreadsheet reads as a number. */
    fun importe(v: BigDecimal): String = v.setScale(2).toPlainString().replace('.', ',')

    fun causa(c: CausaSinCuota) = when (c) {
        CausaSinCuota.EXENTA -> "exenta"
        CausaSinCuota.INVERSION_SUJETO_PASIVO -> "inversion del sujeto pasivo"
        CausaSinCuota.INTRACOMUNITARIA -> "intracomunitaria"
    }

    /** `(concepto, valor, esImporte)` for one group. */
    fun grupo(t: TotalesGrupo): List<Triple<String, String, Boolean>> = buildList {
        add(Triple("Facturas", t.facturas.toString(), true))
        add(Triple("Base imponible", importe(t.base), true))
        t.ivaPorTipo.forEach { (tipo, cuota) -> add(Triple("IVA ${importe(tipo)} %", importe(cuota), true)) }
        add(Triple("Recargo de equivalencia", importe(t.recargo), true))
        add(Triple("Retenciones", importe(t.retenciones), true))
        add(Triple("Total", importe(t.total), true))
        t.sinCuota.forEach { (c, base) -> add(Triple("Sin cuota: ${causa(c)}", importe(base), true)) }
    }

    fun cabecera(r: Reporte): List<Pair<String, String>> = buildList {
        add("Reporte de facturación" to r.periodo.etiqueta)
        add("Desde" to r.periodo.desde.toString())
        add("Hasta" to r.periodo.hasta.toString())
        add("Calculado" to FECHA_HORA.format(r.calculadoEn))
        add("Pendientes de confirmar" to r.pendientes.toString())
        r.trimestresCerrados.forEach {
            add("Trimestre cerrado" to "${it.anio}-T${it.trimestre} cerrado desde ${FECHA_HORA.format(it.desde)}")
        }
    }
}

/**
 * The report as a CSV for a spreadsheet (FR-022), with the same format as the
 * register export: common's [FormatoCsv]. Amounts go in numeric cells, so a
 * negative total stays a number (finding I2).
 */
object EscritorReporteCsv {

    fun nombre(periodo: Periodo) = "reporte-facturacion_${periodo.etiqueta}.csv"

    fun escribir(r: Reporte, out: OutputStream) {
        FormatoCsv.escribirBom(out)
        FilasReporte.cabecera(r).forEach { (etiqueta, valor) ->
            val celda = if (etiqueta == "Pendientes de confirmar") FormatoCsv.numero(valor) else FormatoCsv.texto(valor)
            FormatoCsv.escribirLinea(out, listOf(FormatoCsv.texto(etiqueta), celda))
        }
        FormatoCsv.escribirLineaVacia(out)
        FormatoCsv.escribirLineaDeTexto(out, listOf("Grupo", "Concepto", "Importe"))
        listOf("Emitidas" to r.emitidas, "Recibidas" to r.recibidas).forEach { (grupo, totales) ->
            FilasReporte.grupo(totales).forEach { (concepto, valor, _) ->
                FormatoCsv.escribirLinea(out, listOf(FormatoCsv.texto(grupo), FormatoCsv.texto(concepto), FormatoCsv.numero(valor)))
            }
        }
    }
}
