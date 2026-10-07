package com.granatum.core.api.controllers

import com.granatum.core.api.dto.FacturaDto
import com.granatum.core.api.dto.FacturaRequest
import com.granatum.core.api.dto.ResultadoSubidaDto
import com.granatum.core.api.dto.VersionRequest
import com.granatum.core.domain.model.LineaIva
import com.granatum.core.service.RevisionFacturas
import com.granatum.core.service.ValoresFactura
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import java.math.BigDecimal
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
    private val ficha: FichaFacturas,
    private val revision: RevisionFacturas
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

    @PutMapping("/{id}")
    fun corregir(@PathVariable id: UUID, @Valid @RequestBody r: FacturaRequest): FacturaDto {
        revision.corregir(id, r.aValores(), r.version, requestUserId)
        return ficha.ficha(id)
    }

    @PostMapping("/{id}/confirmar")
    fun confirmar(@PathVariable id: UUID, @RequestBody r: VersionRequest): FacturaDto {
        revision.confirmar(id, r.version, requestUserId)
        return ficha.ficha(id)
    }

    @PostMapping("/{id}/descartar")
    fun descartar(@PathVariable id: UUID, @RequestBody r: VersionRequest): FacturaDto {
        revision.descartar(id, r.version, requestUserId)
        return ficha.ficha(id)
    }

    @PostMapping("/{id}/reconocer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun reconocer(@PathVariable id: UUID) = subida.reintentar(id)

    private fun FacturaRequest.aValores() = ValoresFactura(
        tipo = tipo,
        emisorNombre = emisor?.nombre, emisorNif = emisor?.nif,
        destinatarioNombre = destinatario?.nombre, destinatarioNif = destinatario?.nif,
        numero = numero, fechaEmision = fechaEmision, concepto = concepto, moneda = moneda,
        lineas = lineas.map { LineaIva(BigDecimal(it.tipoIva).setScale(2), BigDecimal(it.base).setScale(2), BigDecimal(it.cuota).setScale(2), BigDecimal(it.recargo).setScale(2), it.causaSinCuota) },
        retenciones = BigDecimal(retenciones).setScale(2),
        total = total?.let { BigDecimal(it).setScale(2) },
        rectificativa = rectificativa
    )
}
