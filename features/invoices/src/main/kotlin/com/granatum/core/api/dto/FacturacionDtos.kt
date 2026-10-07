package com.granatum.core.api.dto

import com.granatum.core.domain.model.Aviso
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.Factura
import com.granatum.core.domain.model.TipoFactura
import java.util.UUID

// DTOs of invoicing. Every one that carries a name, a tax id or an amount
// overrides toString(): at DEBUG, Spring MVC logs bodies through it (FR-027,
// the lesson of feature 002). Amounts travel as decimal strings
// (contracts/README.md): a JSON number could lose a cent on the client.

data class ResultadoSubidaDto(val fichero: Int, val resultado: String, val facturaId: UUID?)

data class ParteDto(val nombre: String?, val nif: String?) {
    override fun toString(): String = "ParteDto(***)"
}

data class LineaIvaDto(
    val tipoIva: String,
    val base: String,
    val cuota: String,
    val recargo: String,
    val causaSinCuota: String?
) {
    override fun toString(): String = "LineaIvaDto(***)"
}

data class ReconocimientoResumenDto(val resultado: String, val camposDudosos: List<String>)

data class AvisoDto(val campo: String, val codigo: String, val bloquea: Boolean, val mensaje: String)

data class FacturaDto(
    val id: UUID,
    val estado: EstadoFactura,
    val tipo: TipoFactura?,
    val version: Int,
    val emisor: ParteDto?,
    val destinatario: ParteDto?,
    val numero: String?,
    val fechaEmision: String?,
    val concepto: String?,
    val moneda: String,
    val rectificativa: Boolean,
    val lineas: List<LineaIvaDto>,
    val retenciones: String,
    val total: String?,
    val trimestreCerrado: Boolean,
    val reconocimiento: ReconocimientoResumenDto?,
    val avisos: List<AvisoDto>
) {
    override fun toString(): String = "FacturaDto(id=$id, estado=$estado)"
}

fun Factura.aDto(trimestreCerrado: Boolean, reconocimiento: ReconocimientoResumenDto?, avisos: List<Aviso>) = FacturaDto(
    id = id,
    estado = estado,
    tipo = tipo,
    version = version,
    emisor = emisor?.let { ParteDto(it.nombre, it.nif) },
    destinatario = destinatario?.let { ParteDto(it.nombre, it.nif) },
    numero = numero,
    fechaEmision = fechaEmision?.toString(),
    concepto = concepto,
    moneda = moneda,
    rectificativa = rectificativa,
    lineas = lineas.map {
        LineaIvaDto(it.tipoIva.toPlainString(), it.base.toPlainString(), it.cuota.toPlainString(),
            it.recargo.toPlainString(), it.causaSinCuota?.name)
    },
    retenciones = retenciones.toPlainString(),
    total = total?.toPlainString(),
    trimestreCerrado = trimestreCerrado,
    reconocimiento = reconocimiento,
    avisos = avisos.map { AvisoDto(it.campo, it.codigo.name, it.bloquea, it.mensaje) }
)
