package com.granatum.core.api.controllers

import com.granatum.core.api.dto.FacturaDto
import com.granatum.core.api.dto.ResultadoSubidaDto
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.FichaFacturas
import com.granatum.core.service.FicheroSubido
import com.granatum.core.service.SubidaFacturas
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/** Invoices: upload, review, originals. `ADMIN` only, by the `/api/facturacion` rule. */
@RestController
@RequestMapping("/api/facturacion/facturas")
class FacturaController(
    private val subida: SubidaFacturas,
    private val ficha: FichaFacturas
) {

    /**
     * One or more files in the `ficheros` part. `202`: the accepted ones are
     * recognised in the background (research.md D-005). Each result is
     * identified by position, never by file name (contracts/README.md).
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun subir(@RequestPart("ficheros") ficheros: List<MultipartFile>): List<ResultadoSubidaDto> =
        subida.subir(ficheros.map { FicheroSubido(it.originalFilename, it.bytes) }, requestUserId)
            .map { ResultadoSubidaDto(it.fichero, it.resultado, it.facturaId) }

    @GetMapping("/{id}")
    fun consultar(@PathVariable id: UUID): FacturaDto = ficha.ficha(id)

    @PostMapping("/{id}/reconocer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun reconocer(@PathVariable id: UUID) = subida.reintentar(id)
}
