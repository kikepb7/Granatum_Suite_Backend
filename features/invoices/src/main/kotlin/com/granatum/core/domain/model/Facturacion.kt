package com.granatum.core.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

// Invoicing domain (feature 004). No JPA, no HTTP.
//
// Every type that carries names, tax ids or amounts overrides toString():
// Spring MVC logs objects through it at DEBUG, which is how feature 002 leaked
// passwords, and an invoice is a list of exactly those things (FR-027).

enum class EstadoFactura { PENDIENTE_RECONOCER, BORRADOR, CONFIRMADA, DESCARTADA }

enum class TipoFactura { EMITIDA, RECIBIDA }

/**
 * Why a VAT line carries no tax although the operation exists (research.md
 * D-020). Without a cause, a 0% line looks like a misread one.
 */
enum class CausaSinCuota { EXENTA, INVERSION_SUJETO_PASIVO, INTRACOMUNITARIA }

/** Issuer or recipient. Both fields optional: a simplified invoice has no recipient. */
data class Parte(val nombre: String?, val nif: String?) {
    override fun toString(): String = "Parte(***)"
}

/** One VAT rate of an invoice, with its base, tax and equivalence surcharge. */
data class LineaIva(
    val tipoIva: BigDecimal,
    val base: BigDecimal,
    val cuota: BigDecimal,
    val recargo: BigDecimal = BigDecimal.ZERO,
    val causaSinCuota: CausaSinCuota? = null
) {
    override fun toString(): String = "LineaIva(tipoIva=$tipoIva)"
}

data class Factura(
    val id: UUID,
    val estado: EstadoFactura,
    val tipo: TipoFactura?,
    val emisor: Parte?,
    val destinatario: Parte?,
    val numero: String?,
    val fechaEmision: LocalDate?,
    val concepto: String?,
    val moneda: String,
    val lineas: List<LineaIva>,
    val retenciones: BigDecimal,
    val total: BigDecimal?,
    val rectificativa: Boolean,
    val version: Int
) {
    override fun toString(): String = "Factura(id=$id, estado=$estado)"
}

enum class CodigoAviso {
    OBLIGATORIO, NO_CUADRA, NIF_INVALIDO, DUDOSO, DUPLICADA, MONEDA, FECHA_FUTURA,
    FECHA_ANTIGUA, NO_ES_DE_LA_EMPRESA, SIN_CAUSA_CUOTA_CERO, NO_ES_FACTURA, IMPORTE_NEGATIVO,
    VARIAS_FACTURAS, TRIMESTRE_CERRADO
}

/**
 * Something about an invoice worth looking at before confirming it. Part of the
 * resource, not an error (contracts/README.md): [bloquea] says whether it
 * prevents confirmation. The message never quotes a name or a tax id.
 */
data class Aviso(
    val campo: String,
    val codigo: CodigoAviso,
    val bloquea: Boolean,
    val mensaje: String
)

/**
 * What the recognition proposed, exactly as it came back (FR-005). Every field
 * nullable: what could not be read stays empty instead of being invented
 * (FR-004). Amounts as decimal strings, so no cent is lost in transit
 * (research.md D-003).
 */
data class PropuestaReconocida(
    val esFactura: Boolean,
    val variasFacturas: Boolean,
    val emisorNombre: String?,
    val emisorNif: String?,
    val destinatarioNombre: String?,
    val destinatarioNif: String?,
    val numero: String?,
    val fechaEmision: String?,
    val concepto: String?,
    val moneda: String?,
    val lineas: List<LineaPropuesta>,
    val retenciones: String?,
    val total: String?,
    val rectificativa: Boolean?,
    val camposDudosos: List<String>
) {
    override fun toString(): String = "PropuestaReconocida(esFactura=$esFactura, lineas=${lineas.size})"
}

data class LineaPropuesta(
    val tipoIva: String?,
    val base: String?,
    val cuota: String?,
    val recargo: String?,
    val causaSinCuota: String?
) {
    override fun toString(): String = "LineaPropuesta(***)"
}

/** A reporting period. Invoices belong to the period of their issue date (FR-018). */
sealed interface Periodo {
    val anio: Int
    val desde: LocalDate
    val hasta: LocalDate
    /** For file names: `2026-10`, `2026-T3`, `2026`. */
    val etiqueta: String

    data class Mensual(override val anio: Int, val mes: Int) : Periodo {
        override val desde: LocalDate get() = YearMonth.of(anio, mes).atDay(1)
        override val hasta: LocalDate get() = YearMonth.of(anio, mes).atEndOfMonth()
        override val etiqueta: String get() = "$anio-${mes.toString().padStart(2, '0')}"
    }

    data class Trimestral(override val anio: Int, val trimestre: Int) : Periodo {
        override val desde: LocalDate get() = LocalDate.of(anio, (trimestre - 1) * 3 + 1, 1)
        override val hasta: LocalDate get() = YearMonth.of(anio, trimestre * 3).atEndOfMonth()
        override val etiqueta: String get() = "$anio-T$trimestre"
    }

    data class Anual(override val anio: Int) : Periodo {
        override val desde: LocalDate get() = LocalDate.of(anio, 1, 1)
        override val hasta: LocalDate get() = LocalDate.of(anio, 12, 31)
        override val etiqueta: String get() = "$anio"
    }

    companion object {
        /** The fiscal quarter (1-4) a date falls in. */
        fun trimestreDe(fecha: LocalDate): Int = (fecha.monthValue - 1) / 3 + 1
    }
}

/** Totals of one group (issued or received) in a period (FR-019). */
data class TotalesGrupo(
    val facturas: Int,
    val base: BigDecimal,
    /** VAT per rate, ordered by rate. */
    val ivaPorTipo: Map<BigDecimal, BigDecimal>,
    val recargo: BigDecimal,
    val retenciones: BigDecimal,
    val total: BigDecimal,
    /** Base of the lines carrying no VAT, by cause (research.md D-020). */
    val sinCuota: Map<CausaSinCuota, BigDecimal>
) {
    override fun toString(): String = "TotalesGrupo(facturas=$facturas)"

    companion object {
        val VACIO = TotalesGrupo(0, BigDecimal.ZERO.setScale(2), emptyMap(), BigDecimal.ZERO.setScale(2),
            BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), emptyMap())
    }
}

data class TrimestreCerrado(val anio: Int, val trimestre: Int, val desde: Instant)

data class Reporte(
    val periodo: Periodo,
    val emitidas: TotalesGrupo,
    val recibidas: TotalesGrupo,
    /** Invoices of the period not yet confirmed: they do not count, and the report says so (FR-021). */
    val pendientes: Int,
    val trimestresCerrados: List<TrimestreCerrado>,
    val calculadoEn: Instant
) {
    override fun toString(): String = "Reporte(periodo=${periodo.etiqueta})"
}
