package com.granatum.core.infrastructure.reports

import com.granatum.core.csv.CsvFormat
import com.granatum.core.domain.model.NoVatCause
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.domain.model.InvoicingReport
import com.granatum.core.domain.model.GroupTotals
import java.io.OutputStream
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The rows of a report, shared by the CSV and the PDF so both show the same thing in the same order. */
object InvoicingReportRows {
    private val MADRID = ZoneId.of("Europe/Madrid")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(MADRID)

    /** `1764,00`, `-150,40`: what a Spanish spreadsheet reads as a number. */
    fun amount(v: BigDecimal): String = v.setScale(2).toPlainString().replace('.', ',')

    fun cause(c: NoVatCause) = when (c) {
        NoVatCause.EXEMPT -> "exenta"
        NoVatCause.REVERSE_CHARGE -> "inversion del sujeto pasivo"
        NoVatCause.INTRA_COMMUNITY -> "intracomunitaria"
    }

    /** `(concept, value, esImporte)` for one group. */
    fun group(t: GroupTotals): List<Triple<String, String, Boolean>> = buildList {
        add(Triple("Facturas", t.invoices.toString(), true))
        add(Triple("Base imponible", amount(t.base), true))
        t.vatByRate.forEach { (type, vatAmount) -> add(Triple("IVA ${amount(type)} %", amount(vatAmount), true)) }
        add(Triple("Recargo de equivalencia", amount(t.surcharge), true))
        add(Triple("Retenciones", amount(t.withholdings), true))
        add(Triple("Total", amount(t.total), true))
        t.noVatBase.forEach { (c, base) -> add(Triple("Sin cuota: ${cause(c)}", amount(base), true)) }
    }

    fun header(r: InvoicingReport): List<Pair<String, String>> = buildList {
        add("Reporte de facturación" to r.period.label)
        add("Desde" to r.period.from.toString())
        add("Hasta" to r.period.to.toString())
        add("Calculado" to DATE_TIME.format(r.calculatedAt))
        add("Pendientes de confirmar" to r.pending.toString())
        r.closedQuarters.forEach {
            add("Trimestre cerrado" to "${it.year}-T${it.quarter} cerrado desde ${DATE_TIME.format(it.from)}")
        }
    }
}

/**
 * The report as a CSV for a spreadsheet (FR-022), with the same format as the
 * register export: common's [CsvFormat]. Amounts go in numeric cells, so a
 * negative total stays a number (finding I2).
 */
object InvoicingReportCsvWriter {

    fun fileName(period: ReportPeriod) = "reporte-facturacion_${period.label}.csv"

    fun write(r: InvoicingReport, out: OutputStream) {
        CsvFormat.writeBom(out)
        InvoicingReportRows.header(r).forEach { (label, value) ->
            val cell = if (label == "Pendientes de confirmar") CsvFormat.number(value) else CsvFormat.text(value)
            CsvFormat.writeLine(out, listOf(CsvFormat.text(label), cell))
        }
        CsvFormat.writeEmptyLine(out)
        CsvFormat.writeTextLine(out, listOf("Grupo", "Concepto", "Importe"))
        listOf("Emitidas" to r.issued, "Recibidas" to r.received).forEach { (group, totals) ->
            InvoicingReportRows.group(totals).forEach { (concept, value, _) ->
                CsvFormat.writeLine(out, listOf(CsvFormat.text(group), CsvFormat.text(concept), CsvFormat.number(value)))
            }
        }
    }
}
