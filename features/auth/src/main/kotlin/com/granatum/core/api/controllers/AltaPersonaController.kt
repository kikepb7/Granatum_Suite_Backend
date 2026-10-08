package com.granatum.core.api.controllers

import com.granatum.core.api.dto.AltaPersonaRequest
import com.granatum.core.api.dto.AltaPersonaResponse
import com.granatum.core.service.CuentaAccesoService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Onboarding a person (feature 009): `ADMIN` only - the rule is in
 * `SecurityConfig`. Staff record and account in one call; the response carries
 * the temporary password the ADMIN hands to the person, and nothing else will
 * ever show it again.
 */
@RestController
@RequestMapping("/api/auth/altas")
class AltaPersonaController(private val cuentaAccesoService: CuentaAccesoService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun darDeAlta(@Valid @RequestBody r: AltaPersonaRequest): AltaPersonaResponse {
        val alta = cuentaAccesoService.darDeAlta(
            CuentaAccesoService.DatosAlta(
                nombre = r.nombre,
                documento = r.documentoIdentidad,
                puesto = r.puesto,
                tipoContrato = r.tipoContrato,
                fechaAlta = r.fechaAlta!!,
                email = r.email,
                rol = r.rol!!
            )
        )
        return AltaPersonaResponse(
            empleadoId = alta.cuenta.empleadoId,
            cuentaId = alta.cuenta.id,
            email = alta.cuenta.email,
            rol = alta.cuenta.rol,
            fichaCreada = alta.fichaCreada,
            passwordTemporal = alta.passwordTemporal
        )
    }
}
