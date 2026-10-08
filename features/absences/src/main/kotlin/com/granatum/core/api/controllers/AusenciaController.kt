package com.granatum.core.api.controllers

import com.granatum.core.api.dto.AltaBajaRequest
import com.granatum.core.api.dto.AusenciaResponse
import com.granatum.core.api.dto.DerechoVacacionesRequest
import com.granatum.core.api.dto.RechazoAusenciaRequest
import com.granatum.core.api.dto.RegistroAusenciaRequest
import com.granatum.core.api.dto.SaldoVacacionesResponse
import com.granatum.core.api.dto.SolicitudAusenciaRequest
import com.granatum.core.api.util.requestUserId
import com.granatum.core.api.util.requestUserRole
import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.domain.type.Role
import com.granatum.core.service.AusenciaService
import com.granatum.core.service.NuevaAusencia
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Absences (feature 007). Which role reaches which route is declared in
 * `SecurityConfig`; whose absences a caller sees is decided here and in the
 * service, always against the token subject - an id in the request never
 * widens what an EMPLEADO can see (principle IV).
 */
@RestController
@RequestMapping("/api/ausencias")
class AusenciaController(private val servicio: AusenciaService) {

    /** ENCARGADO and ADMIN see everyone's; EMPLEADO only their own (FR-018). */
    private val veTodas: Boolean get() = requestUserRole == Role.ADMIN || requestUserRole == Role.ENCARGADO

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun solicitar(@Valid @RequestBody r: SolicitudAusenciaRequest): AusenciaResponse =
        AusenciaResponse.de(servicio.solicitar(requestUserId, NuevaAusencia(r.tipo!!, r.causa, r.desde!!, r.hasta, r.comentario)))

    @PostMapping("/registro")
    @ResponseStatus(HttpStatus.CREATED)
    fun registrar(@Valid @RequestBody r: RegistroAusenciaRequest): AusenciaResponse =
        AusenciaResponse.de(
            servicio.registrar(requestUserId, r.empleadoId!!, NuevaAusencia(r.tipo!!, r.causa, r.desde!!, r.hasta, r.comentario))
        )

    /**
     * The calendar. Without dates, the current year. An EMPLEADO's `empleadoId`
     * is ignored and replaced by their own: asking for someone else's returns
     * one's own, never a 403 that would confirm the other id exists.
     */
    @GetMapping
    fun buscar(
        @RequestParam(required = false) empleadoId: UUID?,
        @RequestParam(required = false) estado: EstadoAusencia?,
        @RequestParam(required = false) desde: LocalDate?,
        @RequestParam(required = false) hasta: LocalDate?
    ): List<AusenciaResponse> {
        val anio = LocalDate.now(MADRID).year
        val persona = if (veTodas) empleadoId else requestUserId
        return servicio.buscar(persona, estado, desde ?: LocalDate.of(anio, 1, 1), hasta ?: LocalDate.of(anio, 12, 31))
            .map(AusenciaResponse::de)
    }

    @GetMapping("/saldo")
    fun saldo(
        @RequestParam(required = false) anio: Int?,
        @RequestParam(required = false) empleadoId: UUID?
    ): SaldoVacacionesResponse {
        val persona = if (veTodas) empleadoId ?: requestUserId else requestUserId
        val ejercicio = anio ?: LocalDate.now(MADRID).year
        return SaldoVacacionesResponse.de(persona, servicio.saldo(persona, ejercicio))
    }

    @GetMapping("/{id}")
    fun obtener(@PathVariable id: UUID): AusenciaResponse =
        AusenciaResponse.de(servicio.obtener(id, requestUserId, veTodas))

    @PostMapping("/{id}/aprobar")
    fun aprobar(@PathVariable id: UUID): AusenciaResponse = AusenciaResponse.de(servicio.aprobar(id, requestUserId))

    @PostMapping("/{id}/rechazar")
    fun rechazar(@PathVariable id: UUID, @Valid @RequestBody r: RechazoAusenciaRequest): AusenciaResponse =
        AusenciaResponse.de(servicio.rechazar(id, requestUserId, r.motivo))

    @PostMapping("/{id}/cancelar")
    fun cancelar(@PathVariable id: UUID): AusenciaResponse = AusenciaResponse.de(servicio.cancelar(id, requestUserId))

    @PostMapping("/{id}/alta")
    fun darAlta(@PathVariable id: UUID, @Valid @RequestBody r: AltaBajaRequest): AusenciaResponse =
        AusenciaResponse.de(servicio.darAlta(id, requestUserId, r.hasta!!))

    @PutMapping("/derechos/{empleadoId}/{anio}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun fijarDerecho(
        @PathVariable empleadoId: UUID,
        @PathVariable anio: Int,
        @Valid @RequestBody r: DerechoVacacionesRequest
    ) {
        servicio.fijarDerecho(empleadoId, anio, r.dias!!, requestUserId)
    }

    companion object {
        private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
    }
}
