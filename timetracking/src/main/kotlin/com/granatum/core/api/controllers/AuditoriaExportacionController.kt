package com.granatum.core.api.controllers

import com.granatum.core.api.dto.ExportacionDto
import com.granatum.core.api.dto.VerificacionDto
import com.granatum.core.api.dto.toDto
import com.granatum.core.service.RegistroExportaciones
import com.granatum.core.service.VerificacionExportaciones
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID

/**
 * Who exported what, and whether a file is one of ours (FR-028, FR-029).
 * `ADMIN` only, by the `/api/exportaciones` rule in `SecurityConfig` (D-014).
 */
@RestController
@RequestMapping("/api/exportaciones")
class AuditoriaExportacionController(
    private val registro: RegistroExportaciones,
    private val verificacion: VerificacionExportaciones
) {

    /** Every filter optional and combinable; newest first. */
    @GetMapping
    fun consultar(
        @RequestParam(required = false) empleadoId: UUID?,
        @RequestParam(required = false) generadaDesde: LocalDate?,
        @RequestParam(required = false) generadaHasta: LocalDate?,
        @RequestParam(required = false) cubreDesde: LocalDate?,
        @RequestParam(required = false) cubreHasta: LocalDate?
    ): List<ExportacionDto> =
        registro.consultar(empleadoId, generadaDesde, generadaHasta, cubreDesde, cubreHasta).map { it.toDto() }

    /**
     * The file is the raw request body, taken as an [InputStream] and never as
     * a DTO or a multipart part (D-007): nothing deserialises it, nothing logs
     * it, nothing writes it to disk. It is hashed as it arrives and dropped.
     */
    @PostMapping(
        "/verificar",
        consumes = ["text/csv", MediaType.APPLICATION_OCTET_STREAM_VALUE]
    )
    fun verificar(fichero: InputStream): VerificacionDto {
        val r = verificacion.verificar(fichero)
        return VerificacionDto(
            huella = r.huella,
            coincide = r.coincidencias.isNotEmpty(),
            exportaciones = r.coincidencias.map { it.toDto() }
        )
    }
}
