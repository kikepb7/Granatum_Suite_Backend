package com.granatum.core.infrastructure.reports

import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.domain.model.InvoicingReport
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.OutputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * The report as a PDF to file or print (FR-022), with Apache PDFBox
 * (research.md D-010). Same rows as the CSV, from [InvoicingReportRows]: the figures
 * cannot differ (SC-010). Standard Helvetica, whose WinAnsi encoding covers
 * Spanish accents and the euro sign; no font files to ship.
 */
object InvoicingReportPdfWriter {

    fun fileName(period: ReportPeriod) = "reporte-facturacion_${period.label}.pdf"

    private val NORMAL = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val BOLD = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private const val MARGIN = 56f
    private const val LINE_HEIGHT = 16f

    fun write(r: InvoicingReport, out: OutputStream) {
        PDDocument().use { doc ->
            doc.documentInformation.title = "Reporte de facturación ${r.period.label}"
            // The calculation time, not "now": the same report gives the same document.
            doc.documentInformation.creationDate = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = r.calculatedAt.toEpochMilli()
            } as Calendar

            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                var y = page.mediaBox.height - MARGIN
                fun text(x: Float, s: String, font: PDType1Font = NORMAL, size: Float = 10f) {
                    cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(x, y); cs.showText(s); cs.endText()
                }
                fun rightAligned(xFin: Float, s: String, font: PDType1Font = NORMAL) =
                    text(xFin - font.getStringWidth(s) / 1000f * 10f, s, font)

                text(MARGIN, "Reporte de facturación ${r.period.label}", BOLD, 16f)
                y -= LINE_HEIGHT * 2
                InvoicingReportRows.header(r).drop(1).forEach { (label, value) ->
                    text(MARGIN, label); text(MARGIN + 160f, value); y -= LINE_HEIGHT
                }
                if (r.pending > 0) {
                    y -= LINE_HEIGHT / 2
                    text(MARGIN, "Hay ${r.pending} facturas del periodo sin confirmar: no cuentan en estos totales.", BOLD)
                    y -= LINE_HEIGHT
                }
                listOf("Emitidas" to r.issued, "Recibidas" to r.received).forEach { (group, totals) ->
                    y -= LINE_HEIGHT
                    text(MARGIN, group, BOLD, 12f)
                    y -= LINE_HEIGHT * 1.2f
                    InvoicingReportRows.group(totals).forEach { (concept, value, _) ->
                        text(MARGIN + 12f, concept)
                        rightAligned(page.mediaBox.width - MARGIN, value)
                        y -= LINE_HEIGHT
                    }
                }
            }
            doc.save(out)
        }
    }
}
