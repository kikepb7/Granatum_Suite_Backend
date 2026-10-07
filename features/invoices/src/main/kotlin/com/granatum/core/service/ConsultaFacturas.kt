package com.granatum.core.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.TipoFactura
import com.granatum.core.domain.port.AlmacenDocumentos
import com.granatum.core.domain.port.DocumentoGuardado
import com.granatum.core.infrastructure.database.JsonFacturacion
import com.granatum.core.infrastructure.database.aDominio
import com.granatum.core.infrastructure.database.repositories.FacturaCambioRepository
import com.granatum.core.infrastructure.database.repositories.FacturaReconocimientoRepository
import com.granatum.core.infrastructure.database.repositories.FacturaRepository
import com.granatum.core.infrastructure.documentos.TipoDocumento
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class FiltroFacturas(
    val desde: LocalDate? = null,
    val hasta: LocalDate? = null,
    val parte: String? = null,
    val tipo: TipoFactura? = null,
    val estado: EstadoFactura? = null,
    val pagina: Int = 0,
    val tamano: Int = 50
) {
    override fun toString(): String = "FiltroFacturas(pagina=$pagina)"
}

data class ResumenFactura(
    val id: UUID,
    val estado: EstadoFactura,
    val tipo: TipoFactura?,
    val emisorNombre: String?,
    val emisorNif: String?,
    val destinatarioNombre: String?,
    val destinatarioNif: String?,
    val numero: String?,
    val fechaEmision: LocalDate?,
    val total: BigDecimal?,
    val numeroAvisos: Int
) {
    override fun toString(): String = "ResumenFactura(id=$id, estado=$estado)"
}

data class Pagina<T>(val elementos: List<T>, val pagina: Int, val tamano: Int, val total: Long)

data class ReconocimientoHistorial(
    val resultado: String,
    val modelo: String,
    val creadoEn: Instant,
    /** Plain maps and lists, so the web layer's Jackson 3 can write it. */
    val propuesta: Any?,
    val error: String?
) {
    override fun toString(): String = "ReconocimientoHistorial(resultado=$resultado)"
}

data class CambioHistorial(val accion: String, val autorId: UUID, val ocurridoEn: Instant, val valoresAnteriores: Any?) {
    override fun toString(): String = "CambioHistorial(accion=$accion)"
}

data class Historial(val reconocimientos: List<ReconocimientoHistorial>, val cambios: List<CambioHistorial>)

class OriginalDescargable(val documento: DocumentoGuardado, val nombre: String)

/** Finding invoices and justifying any figure with its document and history (FR-024, FR-025, FR-005, FR-017). */
@Service
class ConsultaFacturas(
    private val facturas: FacturaRepository,
    private val reconocimientos: FacturaReconocimientoRepository,
    private val cambios: FacturaCambioRepository,
    private val almacen: AlmacenDocumentos,
    private val revision: RevisionFacturas,
    private val lecturas: FacturaLecturas,
    private val json: JsonFacturacion
) {
    @Transactional(readOnly = true)
    fun listar(f: FiltroFacturas): Pagina<ResumenFactura> {
        val tamano = f.tamano.coerceIn(1, 200)
        val pagina = f.pagina.coerceAtLeast(0)
        // `%` and `_` are text here, not wildcards.
        val parte = f.parte?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace("\\", "\\\\")?.replace("%", "\\%")?.replace("_", "\\_")?.let { "%$it%" }
        val filas = facturas.buscar(f.desde, f.hasta, f.tipo?.name, f.estado?.name, parte, tamano, pagina * tamano)
        val total = facturas.contar(f.desde, f.hasta, f.tipo?.name, f.estado?.name, parte)
        return Pagina(
            filas.map {
                val d = it.aDominio()
                ResumenFactura(d.id, d.estado, d.tipo, d.emisor?.nombre, d.emisor?.nif, d.destinatario?.nombre,
                    d.destinatario?.nif, d.numero, d.fechaEmision, d.total, revision.avisosDe(d.id).size)
            },
            pagina, tamano, total
        )
    }

    /** The original exactly as uploaded; named by id, never by its original name (D-017 of feature 003). */
    fun original(id: UUID): OriginalDescargable {
        lecturas.estado(id)
        val documento = almacen.leer(id)
        return OriginalDescargable(documento, "factura-$id.${TipoDocumento.deMediaType(documento.mediaType).extension}")
    }

    @Transactional(readOnly = true)
    fun historial(id: UUID): Historial {
        lecturas.estado(id)
        return Historial(
            reconocimientos.findAllByFacturaIdOrderByCreadoEnAsc(id).map {
                ReconocimientoHistorial(it.resultado, it.modelo, it.creadoEn, it.propuesta?.let { p -> json.mapper.readValue<Map<String, Any?>>(p) }, it.error)
            },
            cambios.findAllByFacturaIdOrderByOcurridoEnAsc(id).map {
                CambioHistorial(it.accion, it.autorId, it.ocurridoEn, json.mapper.readValue<Map<String, Any?>>(it.valoresAnteriores))
            }
        )
    }
}
