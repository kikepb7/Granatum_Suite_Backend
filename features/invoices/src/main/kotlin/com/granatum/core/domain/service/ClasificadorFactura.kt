package com.granatum.core.domain.service

import com.granatum.core.domain.model.TipoFactura
import com.granatum.core.validation.NifValidator

/**
 * Issued or received, by the company's own tax id (research.md D-014). Tax ids
 * compare normalised: case, spaces, hyphens and the `ES` VAT prefix do not
 * matter. Null when neither party is the company: the reviewer decides.
 */
object ClasificadorFactura {
    fun clasificar(nifEmpresa: String?, emisorNif: String?, destinatarioNif: String?): TipoFactura? {
        val propio = nifEmpresa?.let(NifValidator::normalizarFiscal) ?: return null
        return when (propio) {
            emisorNif?.let(NifValidator::normalizarFiscal) -> TipoFactura.EMITIDA
            destinatarioNif?.let(NifValidator::normalizarFiscal) -> TipoFactura.RECIBIDA
            else -> null
        }
    }
}
