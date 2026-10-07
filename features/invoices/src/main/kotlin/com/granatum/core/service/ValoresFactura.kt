package com.granatum.core.service

import com.granatum.core.domain.model.Factura
import com.granatum.core.domain.model.LineaIva
import com.granatum.core.domain.model.TipoFactura
import java.math.BigDecimal
import java.time.LocalDate

/** Everything a reviewer can set on an invoice (contracts/README.md, PUT). The breakdown is replaced whole. */
data class ValoresFactura(
    val tipo: TipoFactura?,
    val emisorNombre: String?,
    val emisorNif: String?,
    val destinatarioNombre: String?,
    val destinatarioNif: String?,
    val numero: String?,
    val fechaEmision: LocalDate?,
    val concepto: String?,
    val moneda: String,
    val lineas: List<LineaIva>,
    val retenciones: BigDecimal,
    val total: BigDecimal?,
    val rectificativa: Boolean
) {
    override fun toString(): String = "ValoresFactura(***)"

    companion object {
        fun de(f: Factura) = ValoresFactura(
            f.tipo, f.emisor?.nombre, f.emisor?.nif, f.destinatario?.nombre, f.destinatario?.nif, f.numero,
            f.fechaEmision, f.concepto, f.moneda, f.lineas, f.retenciones, f.total, f.rectificativa
        )
    }
}
