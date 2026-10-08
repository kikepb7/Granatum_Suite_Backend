package com.granatum.core.api.controllers

import com.granatum.core.api.dto.NotificacionResponse
import com.granatum.core.api.dto.TotalNoLeidasResponse
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.NotificacionService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Everyone's own inbox (feature 008). The recipient is always the token subject. */
@RestController
@RequestMapping("/api/notificaciones")
class NotificacionController(private val servicio: NotificacionService) {

    @GetMapping
    fun bandeja(@RequestParam(defaultValue = "false") soloNoLeidas: Boolean): List<NotificacionResponse> =
        servicio.bandeja(requestUserId, soloNoLeidas).map(NotificacionResponse::de)

    @GetMapping("/no-leidas")
    fun noLeidas(): TotalNoLeidasResponse = TotalNoLeidasResponse(servicio.noLeidas(requestUserId))

    @PostMapping("/{id}/leida")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun marcarLeida(@PathVariable id: UUID) = servicio.marcarLeida(id, requestUserId)

    @PostMapping("/leidas")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun marcarTodasLeidas() {
        servicio.marcarTodasLeidas(requestUserId)
    }
}
