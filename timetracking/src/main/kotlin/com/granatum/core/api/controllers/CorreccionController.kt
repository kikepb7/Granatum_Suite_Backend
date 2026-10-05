package com.granatum.core.api.controllers

import com.granatum.core.api.dto.CorreccionDto
import com.granatum.core.api.dto.CrearCorreccionRequest
import com.granatum.core.api.dto.RechazarCorreccionRequest
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.mappers.toModel
import com.granatum.core.api.util.requestUserId
import com.granatum.core.domain.exception.UbicacionNoCorregibleException
import com.granatum.core.api.util.requestUserRole
import com.granatum.core.domain.type.EstadoSolicitud
import com.granatum.core.service.CorreccionService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Correction requests: the only route by which a finalised fichaje changes
 * value.
 *
 * Mapped under two prefixes on purpose - requests belong to a fichaje, while
 * resolving one is an action on the request itself, so forcing both under the
 * same path would make the URLs read worse than the model behind them.
 */
@RestController
class CorreccionController(
    private val correccionService: CorreccionService
) {

    @PostMapping("/api/fichajes/{id}/correcciones")
    @ResponseStatus(HttpStatus.CREATED)
    fun solicitar(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CrearCorreccionRequest
    ): CorreccionDto {
        // Refused, not ignored (FR-020a). Ignoring it would return 201 to a
        // client convinced it had corrected the location, and the divergence
        // would surface only much later - if ever.
        if (request.valoresPropuestos.ubicacion != null) {
            throw UbicacionNoCorregibleException()
        }

        return correccionService.solicitar(
            fichajeId = id,
            solicitanteId = requestUserId,
            rol = requestUserRole,
            motivo = request.motivo,
            propuestos = request.valoresPropuestos.toModel()
        ).toDto()
    }

    @PostMapping("/api/correcciones/{id}/aprobar")
    fun aprobar(@PathVariable id: UUID): CorreccionDto =
        correccionService.aprobar(
            solicitudId = id,
            resolutorId = requestUserId,
            rol = requestUserRole
        ).toDto()

    @PostMapping("/api/correcciones/{id}/rechazar")
    fun rechazar(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RechazarCorreccionRequest
    ): CorreccionDto =
        correccionService.rechazar(
            solicitudId = id,
            resolutorId = requestUserId,
            rol = requestUserRole,
            motivoResolucion = request.motivoResolucion
        ).toDto()

    /**
     * A fichaje's correction history.
     *
     * Not in the original brief, and added because without it the original
     * values are stored but unreadable through the API: SC-003 ("the original
     * stays recoverable") would not be verifiable from outside, and the export
     * feature needs it for its "corrections applied" column.
     */
    @GetMapping("/api/fichajes/{id}/correcciones")
    fun historial(@PathVariable id: UUID): List<CorreccionDto> =
        correccionService.findByFichaje(id).map { it.toDto() }

    /** The reviewer's queue. Also absent from the brief; the flow is unusable without it. */
    @GetMapping("/api/correcciones")
    fun porEstado(
        @RequestParam(defaultValue = "PENDIENTE") estado: EstadoSolicitud
    ): List<CorreccionDto> =
        correccionService.findByEstado(estado).map { it.toDto() }
}
