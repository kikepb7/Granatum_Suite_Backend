package com.granatum.core.infrastructure.database

import com.granatum.core.domain.model.Factura
import com.granatum.core.domain.model.LineaIva
import com.granatum.core.domain.model.Parte
import com.granatum.core.infrastructure.database.entities.FacturaEntity

fun FacturaEntity.aDominio(): Factura = Factura(
    id = id,
    estado = estado,
    tipo = tipo,
    emisor = if (emisorNombre == null && emisorNif == null) null else Parte(emisorNombre, emisorNif),
    destinatario = if (destinatarioNombre == null && destinatarioNif == null) null else Parte(destinatarioNombre, destinatarioNif),
    numero = numero,
    fechaEmision = fechaEmision,
    concepto = concepto,
    moneda = moneda,
    lineas = lineas.map { LineaIva(it.tipoIva, it.base, it.cuota, it.recargo, it.causaSinCuota) },
    retenciones = retenciones,
    total = total,
    rectificativa = rectificativa,
    version = version
)
