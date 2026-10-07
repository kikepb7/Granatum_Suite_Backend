package com.granatum.core.api.controllers

import com.granatum.core.api.dto.aDto
import com.granatum.core.domain.exception.PeriodoInvalidoException
import com.granatum.core.infrastructure.reportes.EscritorReporteCsv
import com.granatum.core.infrastructure.reportes.EscritorReportePdf
import com.granatum.core.service.ReportesFacturacion
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream

/** `GET /api/facturacion/reportes` (FR-018 to FR-023): JSON, CSV or PDF, with the same figures. */
@RestController
@RequestMapping("/api/facturacion/reportes")
class ReporteFacturacionController(private val reportes: ReportesFacturacion) {

    @GetMapping
    fun reporte(
        @RequestParam periodo: String,
        @RequestParam anio: Int,
        @RequestParam(required = false) mes: Int?,
        @RequestParam(required = false) trimestre: Int?,
        @RequestParam(defaultValue = "json") formato: String
    ): ResponseEntity<*> {
        // A missing month or quarter is a missing parameter (400 VALIDACION); an
        // impossible one is PERIODO_INVALIDO (contracts/README.md).
        when (periodo.uppercase()) {
            "MENSUAL" -> if (mes == null) throw MissingServletRequestParameterException("mes", "Integer")
            "TRIMESTRAL" -> if (trimestre == null) throw MissingServletRequestParameterException("trimestre", "Integer")
        }
        val p = reportes.periodo(periodo, anio, mes, trimestre)
        val reporte = reportes.calcular(p)
        return when (formato.lowercase()) {
            "json" -> ResponseEntity.ok(reporte.aDto())
            "csv" -> fichero(
                ByteArrayOutputStream().also { EscritorReporteCsv.escribir(reporte, it) }.toByteArray(),
                MediaType("text", "csv", Charsets.UTF_8), EscritorReporteCsv.nombre(p)
            )
            "pdf" -> fichero(
                ByteArrayOutputStream().also { EscritorReportePdf.escribir(reporte, it) }.toByteArray(),
                MediaType.APPLICATION_PDF, EscritorReportePdf.nombre(p)
            )
            else -> throw PeriodoInvalidoException("El formato es json, csv o pdf")
        }
    }

    private fun fichero(bytes: ByteArray, tipo: MediaType, nombre: String): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(tipo)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre).build().toString())
            .body(bytes)
}
