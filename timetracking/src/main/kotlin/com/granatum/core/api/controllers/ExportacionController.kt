package com.granatum.core.api.controllers

import com.granatum.core.api.util.requestUserId
import com.granatum.core.api.util.requestUserRole
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.type.Role
import com.granatum.core.service.ExportacionPreparada
import com.granatum.core.service.ExportacionService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.time.LocalDate
import java.util.UUID

/**
 * Downloads of the register as a file (contracts/README.md of feature 003).
 *
 * Covered by the existing rule for GET under `/api/fichajes`, open to the four roles;
 * who may export whom is decided in the service against the token subject.
 *
 * ## Why everything is settled before the body
 *
 * A [StreamingResponseBody] runs on another thread once the status line has
 * gone out. Two consequences:
 *
 * - The token's subject and role are read **here**, on the request thread. The
 *   `SecurityContext` is not on the streaming thread.
 * - Validation, ownership and the effective range are settled by
 *   [ExportacionService.preparar] before returning, so a refusal is still a
 *   proper `4xx` and not a `200` with half a file.
 *
 * The file name carries the person's id and the effective range, **never the
 * name**: `Content-Disposition` can end up in an access log (D-017).
 */
@RestController
@RequestMapping("/api/fichajes")
class ExportacionController(
    private val exportacion: ExportacionService
) {
    companion object {
        /** D-009: present only when the range was trimmed, saying where data starts. */
        const val DISPONIBLE_DESDE = "X-Registro-Disponible-Desde"
        private val CSV = MediaType("text", "csv", Charsets.UTF_8)
    }

    /**
     * `empleadoId` omitted means "myself" for an EMPLEADO and the whole staff
     * for everyone else (FR-013, FR-014).
     */
    @GetMapping("/export")
    fun exportar(
        @RequestParam(defaultValue = "csv") formato: String,
        @RequestParam desde: LocalDate,
        @RequestParam hasta: LocalDate,
        @RequestParam(required = false) empleadoId: UUID?
    ): ResponseEntity<StreamingResponseBody> {
        // D-015: one format. Anything else is refused rather than silently
        // answered with CSV under a name the caller did not ask for.
        if (formato != "csv") throw ValoresIncoherentesException("formato no admitido; solo csv")

        val solicitante = requestUserId
        val rol = requestUserRole
        val alcance = when {
            empleadoId != null -> AlcanceExportacion.Persona(empleadoId)
            rol == Role.EMPLEADO -> AlcanceExportacion.Persona(solicitante)
            else -> AlcanceExportacion.Plantilla
        }

        val preparada = exportacion.preparar(alcance, desde, hasta, solicitante, rol)
        val quien = (alcance as? AlcanceExportacion.Persona)?.empleadoId?.toString() ?: "plantilla"
        val nombre = "registro-jornada_${quien}_${preparada.rango.desde}_${preparada.rango.hasta}.csv"

        return descarga(nombre, preparada)
    }

    /**
     * One person's calendar month with its total (FR-019 to FR-023). The month
     * in the name is zero-padded so the files of a year sort in order.
     */
    @GetMapping("/empleado/{empleadoId}/resumen/descarga")
    fun descargarMensual(
        @PathVariable empleadoId: UUID,
        @RequestParam anio: Int,
        @RequestParam mes: Int
    ): ResponseEntity<StreamingResponseBody> {
        val preparada = exportacion.prepararMensual(empleadoId, anio, mes, requestUserId, requestUserRole)
        val nombre = "registro-mensual_${empleadoId}_$anio-${mes.toString().padStart(2, '0')}.csv"
        return descarga(nombre, preparada)
    }

    private fun descarga(nombre: String, preparada: ExportacionPreparada): ResponseEntity<StreamingResponseBody> {
        val rango = preparada.rango
        val cabeceras = HttpHeaders().apply {
            contentType = CSV
            contentDisposition = ContentDisposition.attachment().filename(nombre).build()
            if (rango.recortado) set(DISPONIBLE_DESDE, rango.desde.toString())
        }
        return ResponseEntity.ok()
            .headers(cabeceras)
            .body(StreamingResponseBody { salida -> exportacion.escribir(preparada, salida) })
    }
}
