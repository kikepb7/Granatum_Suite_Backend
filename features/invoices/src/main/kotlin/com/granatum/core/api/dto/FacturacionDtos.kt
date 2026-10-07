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

data class ParteDto(
    @field:jakarta.validation.constraints.Size(max = 200) val nombre: String?,
    @field:jakarta.validation.constraints.Size(max = 20) val nif: String?
) {
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

private const val DECIMAL = "^-?\\d{1,10}(\\.\\d{1,2})?$"

data class LineaIvaRequest(
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val tipoIva: String,
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val base: String,
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val cuota: String,
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val recargo: String = "0.00",
    val causaSinCuota: com.granatum.core.domain.model.CausaSinCuota? = null
) {
    override fun toString(): String = "LineaIvaRequest(***)"
}

/** `PUT …/facturas/{id}`: every editable field, the breakdown whole, and the version read. */
data class FacturaRequest(
    val tipo: TipoFactura? = null,
    @field:jakarta.validation.Valid val emisor: ParteDto? = null,
    @field:jakarta.validation.Valid val destinatario: ParteDto? = null,
    @field:jakarta.validation.constraints.Size(max = 60) val numero: String? = null,
    val fechaEmision: java.time.LocalDate? = null,
    @field:jakarta.validation.constraints.Size(max = 500) val concepto: String? = null,
    @field:jakarta.validation.constraints.Pattern(regexp = "^[A-Z]{3}$") val moneda: String = "EUR",
    val rectificativa: Boolean = false,
    @field:jakarta.validation.Valid val lineas: List<LineaIvaRequest> = emptyList(),
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val retenciones: String = "0.00",
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val total: String? = null,
    val version: Int
) {
    override fun toString(): String = "FacturaRequest(version=$version)"
}

data class VersionRequest(val version: Int)
