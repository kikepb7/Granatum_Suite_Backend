package com.granatum.core.infrastructure.reportes

import com.granatum.core.domain.model.Periodo
import com.granatum.core.domain.model.Reporte
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
 * (research.md D-010). Same rows as the CSV, from [FilasReporte]: the figures
 * cannot differ (SC-010). Standard Helvetica, whose WinAnsi encoding covers
 * Spanish accents and the euro sign; no font files to ship.
 */
object EscritorReportePdf {

    fun nombre(periodo: Periodo) = "reporte-facturacion_${periodo.etiqueta}.pdf"

    private val NORMAL = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val NEGRITA = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private const val MARGEN = 56f
    private const val LINEA = 16f

    fun escribir(r: Reporte, out: OutputStream) {
        PDDocument().use { doc ->
            doc.documentInformation.title = "Reporte de facturación ${r.periodo.etiqueta}"
            // The calculation time, not "now": the same report gives the same document.
            doc.documentInformation.creationDate = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = r.calculadoEn.toEpochMilli()
            } as Calendar

            val pagina = PDPage(PDRectangle.A4)
            doc.addPage(pagina)
            PDPageContentStream(doc, pagina).use { cs ->
                var y = pagina.mediaBox.height - MARGEN
                fun texto(x: Float, s: String, fuente: PDType1Font = NORMAL, tamano: Float = 10f) {
                    cs.beginText(); cs.setFont(fuente, tamano); cs.newLineAtOffset(x, y); cs.showText(s); cs.endText()
                }
                fun derecha(xFin: Float, s: String, fuente: PDType1Font = NORMAL) =
                    texto(xFin - fuente.getStringWidth(s) / 1000f * 10f, s, fuente)

                texto(MARGEN, "Reporte de facturación ${r.periodo.etiqueta}", NEGRITA, 16f)
                y -= LINEA * 2
                FilasReporte.cabecera(r).drop(1).forEach { (etiqueta, valor) ->
                    texto(MARGEN, etiqueta); texto(MARGEN + 160f, valor); y -= LINEA
                }
                if (r.pendientes > 0) {
                    y -= LINEA / 2
                    texto(MARGEN, "Hay ${r.pendientes} facturas del periodo sin confirmar: no cuentan en estos totales.", NEGRITA)
                    y -= LINEA
                }
                listOf("Emitidas" to r.emitidas, "Recibidas" to r.recibidas).forEach { (grupo, totales) ->
                    y -= LINEA
                    texto(MARGEN, grupo, NEGRITA, 12f)
                    y -= LINEA * 1.2f
                    FilasReporte.grupo(totales).forEach { (concepto, valor, _) ->
                        texto(MARGEN + 12f, concepto)
                        derecha(pagina.mediaBox.width - MARGEN, valor)
                        y -= LINEA
                    }
                }
            }
            doc.save(out)
        }
    }
}
