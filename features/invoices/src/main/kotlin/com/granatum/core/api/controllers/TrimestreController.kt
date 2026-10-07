package com.granatum.core.api.controllers

import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.EstadoTrimestre
import com.granatum.core.service.Trimestres
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class ReaperturaRequest(@field:NotBlank @field:Size(min = 10, max = 500) val motivo: String) {
    override fun toString(): String = "ReaperturaRequest(***)"
}

/** Fiscal quarters (FR-030 to FR-033). `ADMIN` only, by the `/api/facturacion` rule. */
@RestController
@RequestMapping("/api/facturacion/trimestres")
class TrimestreController(private val trimestres: Trimestres) {

    @GetMapping
    fun estado(@RequestParam anio: Int): List<EstadoTrimestre> = trimestres.estado(anio)

    @PostMapping("/{anio}/{trimestre}/cerrar")
    fun cerrar(@PathVariable anio: Int, @PathVariable trimestre: Int): EstadoTrimestre {
        trimestres.cerrar(anio, trimestre, requestUserId)
        return trimestres.estado(anio).single { it.trimestre == trimestre }
    }

    /** A reason of 10 to 500 characters is required; without it, 400 VALIDACION (FR-032). */
    @PostMapping("/{anio}/{trimestre}/reabrir")
    fun reabrir(@PathVariable anio: Int, @PathVariable trimestre: Int, @Valid @RequestBody r: ReaperturaRequest): EstadoTrimestre {
        trimestres.reabrir(anio, trimestre, r.motivo, requestUserId)
        return trimestres.estado(anio).single { it.trimestre == trimestre }
    }
}
