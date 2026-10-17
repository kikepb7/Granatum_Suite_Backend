package com.granatum.core

import com.granatum.core.domain.model.NoVatCause
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.domain.model.InvoicingReport
import com.granatum.core.domain.model.GroupTotals
import com.granatum.core.domain.model.ClosedQuarter
import com.granatum.core.infrastructure.reports.InvoicingReportCsvWriter
import com.granatum.core.infrastructure.reports.InvoicingReportPdfWriter
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T058, T059: the report as CSV and as PDF, with exactly the figures of the
 * report itself (FR-022, SC-010).
 */
class InvoicingReportWriterTest {

    private fun d(v: String) = BigDecimal(v)

    private val report = InvoicingReport(
        period = ReportPeriod.Quarterly(2026, 3),
        issued = GroupTotals(12, d("8400.00"), mapOf(d("21.00") to d("1764.00")), d("0.00"), d("0.00"), d("10164.00"), emptyMap()),
        received = GroupTotals(
            31, d("-150.40"), mapOf(d("10.00") to d("90.00"), d("21.00") to d("903.00")), d("5.20"), d("150.00"), d("697.80"),
            mapOf(NoVatCause.INTRA_COMMUNITY to d("320.00"))
        ),
        pending = 3,
        closedQuarters = listOf(ClosedQuarter(2026, 3, Instant.parse("2026-10-15T07:12:00Z"))),
        calculatedAt = Instant.parse("2026-10-16T08:00:00Z")
    )

    private fun csv() = ByteArrayOutputStream().also { InvoicingReportCsvWriter.write(report, it) }.toByteArray()

    @Test
    fun `the CSV has the spreadsheet format and its file name`() {
        val bytes = csv()
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.copyOfRange(0, 3))
        assertTrue(String(bytes, Charsets.UTF_8).contains("\r\n"))
        assertEquals("reporte-facturacion_2026-T3.csv", InvoicingReportCsvWriter.fileName(report.period))
        assertEquals("reporte-facturacion_2026-T3.pdf", InvoicingReportPdfWriter.fileName(report.period))
    }

    @Test
    fun `amounts are numbers with a comma decimal mark, negative ones without an apostrophe`() {
        val text = String(csv(), Charsets.UTF_8)
        assertTrue(text.contains("Emitidas;Base imponible;8400,00\r\n"), text)
        assertTrue(text.contains("Emitidas;IVA 21,00 %;1764,00\r\n"))
        assertTrue(text.contains("Recibidas;Base imponible;-150,40\r\n"), "finding I2: a number, not text")
        assertFalse(text.contains("'-150,40"))
        assertTrue(text.contains("Recibidas;Recargo de equivalencia;5,20\r\n"))
        assertTrue(text.contains("Recibidas;Sin cuota: intracomunitaria;320,00\r\n"))
        assertTrue(text.contains("Recibidas;Facturas;31\r\n"))
        assertTrue(text.contains("Pendientes de confirmar;3\r\n"))
    }

    /** SC-010: every figure of the CSV is in the PDF too. */
    @Test
    fun `the PDF shows the same figures as the CSV`() {
        val pdf = ByteArrayOutputStream().also { InvoicingReportPdfWriter.write(report, it) }.toByteArray()
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        val figures = String(csv(), Charsets.UTF_8).split("\r\n")
            .mapNotNull { it.split(";").lastOrNull() }
            .filter { it.matches(Regex("-?\\d+(,\\d{2})?")) }
        assertTrue(figures.size >= 15, "the comparison covers the figures: $figures")
        figures.forEach { assertTrue(text.contains(it), "figure $it missing from the PDF") }
        assertTrue(text.contains("2026-T3") && text.contains("cerrado"), "period and closed state shown")
    }
}
