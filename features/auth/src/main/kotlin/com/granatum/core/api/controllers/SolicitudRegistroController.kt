package com.granatum.core.api.controllers

import com.granatum.core.api.dto.AprobarRegistroRequest
import com.granatum.core.api.dto.RegistroAprobadoResponse
import com.granatum.core.api.dto.SolicitudRegistroResponse
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.AprobacionRegistroService
import com.granatum.core.service.DatosFicha
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Sign-up requests, from the ADMIN's side (feature 005). `ADMIN` only - the rule
 * is in `SecurityConfig`. The ADMIN's id is the token subject, never a field of
 * the request (principle IV).
 */
@RestController
@RequestMapping("/api/auth/registros")
class SolicitudRegistroController(private val aprobacion: AprobacionRegistroService) {

    @GetMapping
    fun pendientes(): List<SolicitudRegistroResponse> =
        aprobacion.listarPendientes().map {
            SolicitudRegistroResponse(it.id, it.email, it.nombre, it.documentoIdentidad, it.creadaEn, it.empleadoExistenteId)
        }

    /**
     * `puesto`, `tipoContrato` and `fechaAlta` only matter when no staff record
     * has the request's document; all three or none, and the service says
     * which case applies (DATOS_FICHA_REQUERIDOS).
     */
    @PostMapping("/{id}/aprobar")
    @ResponseStatus(HttpStatus.CREATED)
    fun aprobar(@PathVariable id: UUID, @Valid @RequestBody request: AprobarRegistroRequest): RegistroAprobadoResponse {
        val ficha = if (request.puesto != null && request.tipoContrato != null && request.fechaAlta != null) {
            DatosFicha(request.puesto.trim(), request.tipoContrato, request.fechaAlta)
        } else {
            null
        }
        val aprobado = aprobacion.aprobar(id, request.codigoVerificacion, request.rol!!, ficha, requestUserId)
        return RegistroAprobadoResponse(aprobado.solicitudId, aprobado.cuentaId, aprobado.empleadoId, aprobado.rol, aprobado.fichaCreada)
    }

    @PostMapping("/{id}/rechazar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun rechazar(@PathVariable id: UUID) {
        aprobacion.rechazar(id, requestUserId)
    }
}
