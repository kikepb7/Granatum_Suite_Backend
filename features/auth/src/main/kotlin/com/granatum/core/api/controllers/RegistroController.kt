package com.granatum.core.api.controllers

import com.granatum.core.api.dto.RegistroRequest
import com.granatum.core.api.dto.RegistroResponse
import com.granatum.core.service.DatosRegistro
import com.granatum.core.service.RegistroService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Sign-up of the business owner (feature 009): public, but only with the
 * deploy-time bootstrap code and only while the installation has no ADMIN.
 * Everyone else is onboarded by an ADMIN (`POST /api/auth/altas`).
 */
@RestController
@RequestMapping("/api/auth/registro")
class RegistroController(private val registroService: RegistroService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun registrar(@Valid @RequestBody request: RegistroRequest): RegistroResponse {
        registroService.registrar(
            DatosRegistro(
                email = request.email,
                password = request.password,
                nombre = request.nombre,
                documento = request.documentoIdentidad,
                codigoArranque = request.codigoArranque
            )
        )
        return RegistroResponse(estado = "ACTIVA", mensaje = "Cuenta de administración creada. Ya puedes iniciar sesión.")
    }
}
