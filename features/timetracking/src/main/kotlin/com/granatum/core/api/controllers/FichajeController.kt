package com.granatum.core.api.controllers

import com.granatum.core.api.dto.EntradaRequest
import com.granatum.core.api.dto.FichajeDto
import com.granatum.core.api.dto.FinPausaRequest
import com.granatum.core.api.dto.InicioPausaRequest
import com.granatum.core.api.dto.ResumenMensualDto
import com.granatum.core.api.dto.SalidaRequest
import com.granatum.core.api.dto.UbicacionDto
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.util.requestUserId
import java.time.LocalDate
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.GetMapping
import com.granatum.core.domain.type.Role
import com.granatum.core.api.util.requestUserRole
import com.granatum.core.domain.type.TipoOperacionFichaje
import com.granatum.core.service.OperacionFichajeHandler
import com.granatum.core.service.FichajeService
import com.granatum.core.service.UbicacionInput
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * HTTP entry point for the working-day lifecycle. No business logic: validates
 * with `@Valid` and delegates.
 *
 * There is deliberately **no** `PUT` or `PATCH` on `/api/fichajes/{id}`, and
 * none must be added. A finalised fichaje changes only through an approved
 * correction (constitution principle III), and a general update endpoint is
 * precisely how that rule would be broken without anyone noticing.
 *
 * The employee is taken from [requestUserId] - the JWT subject - and never from
 * the request body. A body-supplied id is how "only your own fichajes" gets
 * bypassed.
 */
@RestController
@RequestMapping("/api/fichajes")
class FichajeController(
    private val fichajeService: FichajeService,
    private val handler: OperacionFichajeHandler
) {

    @PostMapping("/entrada")
    @ResponseStatus(HttpStatus.CREATED)
    fun registrarEntrada(@Valid @RequestBody request: EntradaRequest): FichajeDto =
        handler.ejecutar(
            clientEventId = request.clientEventId,
            empleadoId = requestUserId,
            tipoOperacion = TipoOperacionFichaje.ENTRADA,
            occurredAt = request.occurredAt,
            estadoRespuesta = HttpStatus.CREATED.value()
        ) {
            fichajeService.registrarEntrada(
                empleadoId = requestUserId,
                occurredAt = request.occurredAt,
                ubicacion = request.ubicacion?.toInput()
            ).toDto()
        }

    @PostMapping("/{id}/pausa/inicio")
    fun iniciarPausa(
        @PathVariable id: UUID,
        @Valid @RequestBody request: InicioPausaRequest
    ): FichajeDto =
        handler.ejecutar(
            clientEventId = request.clientEventId,
            empleadoId = requestUserId,
            tipoOperacion = TipoOperacionFichaje.INICIO_PAUSA,
            occurredAt = request.occurredAt,
            estadoRespuesta = HttpStatus.OK.value()
        ) {
            fichajeService.iniciarPausa(
                fichajeId = id,
                empleadoId = requestUserId,
                occurredAt = request.occurredAt,
                tipo = request.tipo
            ).toDto()
        }

    @PostMapping("/{id}/pausa/fin")
    fun finalizarPausa(
        @PathVariable id: UUID,
        @Valid @RequestBody request: FinPausaRequest
    ): FichajeDto =
        handler.ejecutar(
            clientEventId = request.clientEventId,
            empleadoId = requestUserId,
            tipoOperacion = TipoOperacionFichaje.FIN_PAUSA,
            occurredAt = request.occurredAt,
            estadoRespuesta = HttpStatus.OK.value()
        ) {
            fichajeService.finalizarPausa(
                fichajeId = id,
                empleadoId = requestUserId,
                occurredAt = request.occurredAt
            ).toDto()
        }

    @PostMapping("/{id}/salida")
    fun registrarSalida(
        @PathVariable id: UUID,
        @Valid @RequestBody request: SalidaRequest
    ): FichajeDto =
        handler.ejecutar(
            clientEventId = request.clientEventId,
            empleadoId = requestUserId,
            tipoOperacion = TipoOperacionFichaje.SALIDA,
            occurredAt = request.occurredAt,
            estadoRespuesta = HttpStatus.OK.value()
        ) {
            fichajeService.registrarSalida(
                fichajeId = id,
                empleadoId = requestUserId,
                occurredAt = request.occurredAt,
                ubicacion = request.ubicacion?.toInput()
            ).toDto()
        }

    @GetMapping("/empleado/{empleadoId}")
    fun porEmpleadoYRango(
        @PathVariable empleadoId: UUID,
        @RequestParam desde: LocalDate,
        @RequestParam hasta: LocalDate
    ): List<FichajeDto> =
        fichajeService.findPorEmpleadoYRango(
            empleadoIdSolicitado = empleadoId,
            solicitanteId = requestUserId,
            rol = requestUserRole,
            desde = desde,
            hasta = hasta
        ).map { it.toDto(incluirUbicacion = puedeVerUbicacion()) }

    /** Whole workforce. ENCARGADO, ADMIN and REPRESENTANTE; never an EMPLEADO. */
    @GetMapping
    fun todosPorRango(
        @RequestParam desde: LocalDate,
        @RequestParam hasta: LocalDate
    ): List<FichajeDto> =
        fichajeService.findTodosPorRango(
            rol = requestUserRole,
            desde = desde,
            hasta = hasta
        ).map { it.toDto(incluirUbicacion = puedeVerUbicacion()) }

    /**
     * A month of someone's register, aggregated (FR-032 to FR-035).
     *
     * This is the calculation only. Downloading it as a file - and handing it to
     * part-time staff with their payslip, as article 12.4.c requires - belongs
     * to the export feature.
     */
    @GetMapping("/empleado/{empleadoId}/resumen")
    fun resumenMensual(
        @PathVariable empleadoId: UUID,
        @RequestParam anio: Int,
        @RequestParam mes: Int
    ): ResumenMensualDto =
        fichajeService.resumenMensual(
            empleadoIdSolicitado = empleadoId,
            solicitanteId = requestUserId,
            rol = requestUserRole,
            anio = anio,
            mes = mes
        ).toDto()

    /**
     * REPRESENTANTE never sees where someone clocked in (FR-023b).
     *
     * Article 34.9 entitles worker representatives to the register, not to each
     * person's whereabouts, and location is the most intrusive datum in it:
     * handing it over would be processing personal data with no obligation
     * behind it.
     *
     * Decided here rather than in the service because it is a presentation
     * concern - what this caller may be shown - and the service already returns
     * the full model to callers who are entitled to it.
     */
    private fun puedeVerUbicacion(): Boolean = requestUserRole != Role.REPRESENTANTE

    private fun UbicacionDto.toInput() = UbicacionInput(
        latitud = latitud,
        longitud = longitud,
        precisionMetros = precisionMetros
    )
}
