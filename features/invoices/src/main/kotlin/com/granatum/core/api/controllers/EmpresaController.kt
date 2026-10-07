package com.granatum.core.api.controllers

import com.granatum.core.api.dto.EmpresaDto
import com.granatum.core.api.dto.EmpresaRequest
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.DatosEmpresa
import com.granatum.core.service.EmpresaService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `ADMIN` only, by the `/api/facturacion` rule in `SecurityConfig`. */
@RestController
@RequestMapping("/api/facturacion/empresa")
class EmpresaController(private val empresa: EmpresaService) {

    @GetMapping
    fun consultar(): EmpresaDto = empresa.consultar().aDto()

    @PutMapping
    fun guardar(@Valid @RequestBody request: EmpresaRequest): EmpresaDto =
        empresa.guardar(request.razonSocial, request.nif, requestUserId).aDto()

    private fun DatosEmpresa.aDto() = EmpresaDto(razonSocial, nif, reconocimientoActivo)
}
