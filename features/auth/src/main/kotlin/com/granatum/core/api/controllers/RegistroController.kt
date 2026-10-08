package com.granatum.core.api.controllers

import com.granatum.core.api.dto.RegistroRequest
import com.granatum.core.api.dto.RegistroResponse
import com.granatum.core.service.DatosRegistro
import com.granatum.core.service.RegistroService
import com.granatum.core.service.ResultadoRegistro
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Sign-up (feature 005). Public: the rule is in `SecurityConfig`.
 *
 * `202` for an ordinary sign-up, whatever the address: the request is accepted
 * for an ADMIN to decide, nothing has been granted. `201` only for the first
 * ADMIN, which is the one path that creates an account on the spot.
 */
@RestController
@RequestMapping("/api/auth/registro")
class RegistroController(private val registroService: RegistroService) {

    @PostMapping
    fun registrar(@Valid @RequestBody request: RegistroRequest): ResponseEntity<RegistroResponse> =
        when (
            val resultado = registroService.registrar(
                DatosRegistro(
                    email = request.email,
                    password = request.password,
                    nombre = request.nombre,
                    documento = request.documentoIdentidad,
                    codigoArranque = request.codigoArranque
                )
            )
        ) {
            is ResultadoRegistro.Pendiente -> ResponseEntity.status(HttpStatus.ACCEPTED).body(
                RegistroResponse(
                    estado = "PENDIENTE_APROBACION",
                    codigoVerificacion = resultado.codigoVerificacion,
                    mensaje = "Solicitud recibida. Dile este código a la persona que administra " +
                        "Granatum para que la apruebe."
                )
            )
            is ResultadoRegistro.AdminCreado -> ResponseEntity.status(HttpStatus.CREATED).body(
                RegistroResponse(
                    estado = "ACTIVA",
                    codigoVerificacion = null,
                    mensaje = "Cuenta de administración creada. Ya puedes iniciar sesión."
                )
            )
        }
}
