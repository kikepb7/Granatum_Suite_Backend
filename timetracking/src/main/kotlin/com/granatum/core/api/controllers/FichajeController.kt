package com.granatum.core.api.controllers

import com.granatum.core.api.dto.EntradaRequest
import com.granatum.core.api.dto.FichajeDto
import com.granatum.core.api.dto.FinPausaRequest
import com.granatum.core.api.dto.InicioPausaRequest
import com.granatum.core.api.dto.SalidaRequest
import com.granatum.core.api.dto.UbicacionDto
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.util.requestUserId
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
    private val fichajeService: FichajeService
) {

    @PostMapping("/entrada")
    @ResponseStatus(HttpStatus.CREATED)
    fun registrarEntrada(@Valid @RequestBody request: EntradaRequest): FichajeDto =
        fichajeService.registrarEntrada(
            empleadoId = requestUserId,
            occurredAt = request.occurredAt,
            ubicacion = request.ubicacion?.toInput()
        ).toDto()

    @PostMapping("/{id}/pausa/inicio")
    fun iniciarPausa(
        @PathVariable id: UUID,
        @Valid @RequestBody request: InicioPausaRequest
    ): FichajeDto =
        fichajeService.iniciarPausa(
            fichajeId = id,
            empleadoId = requestUserId,
            occurredAt = request.occurredAt,
            tipo = request.tipo
        ).toDto()

    @PostMapping("/{id}/pausa/fin")
    fun finalizarPausa(
        @PathVariable id: UUID,
        @Valid @RequestBody request: FinPausaRequest
    ): FichajeDto =
        fichajeService.finalizarPausa(
            fichajeId = id,
            empleadoId = requestUserId,
            occurredAt = request.occurredAt
        ).toDto()

    @PostMapping("/{id}/salida")
    fun registrarSalida(
        @PathVariable id: UUID,
        @Valid @RequestBody request: SalidaRequest
    ): FichajeDto =
        fichajeService.registrarSalida(
            fichajeId = id,
            empleadoId = requestUserId,
            occurredAt = request.occurredAt,
            ubicacion = request.ubicacion?.toInput()
        ).toDto()

    private fun UbicacionDto.toInput() = UbicacionInput(
        latitud = latitud,
        longitud = longitud,
        precisionMetros = precisionMetros
    )
}
