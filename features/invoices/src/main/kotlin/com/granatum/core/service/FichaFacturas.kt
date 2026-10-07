package com.granatum.core.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.granatum.core.api.dto.FacturaDto
import com.granatum.core.api.dto.ReconocimientoResumenDto
import com.granatum.core.api.dto.aDto
import com.granatum.core.infrastructure.database.JsonFacturacion
import org.springframework.stereotype.Service
import java.util.UUID

/** The whole picture of one invoice, as `GET …/facturas/{id}` returns it. */
@Service
class FichaFacturas(
    private val lecturas: FacturaLecturas,
    private val revision: RevisionFacturas,
    private val trimestres: BloqueoTrimestres,
    private val json: JsonFacturacion
) {
    fun ficha(id: UUID): FacturaDto {
        val factura = lecturas.factura(id)
        val ultimo = lecturas.ultimoReconocimiento(id)
        val resumen = ultimo?.let {
            ReconocimientoResumenDto(it.resultado, it.camposDudosos?.let { c -> json.mapper.readValue<List<String>>(c) } ?: emptyList())
        }
        return factura.aDto(
            trimestreCerrado = factura.fechaEmision?.let(trimestres::estaCerrado) ?: false,
            reconocimiento = resumen,
            avisos = revision.avisosDe(id)
        )
    }
}
