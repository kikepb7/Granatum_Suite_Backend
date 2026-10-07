package com.granatum.core.domain.service

import com.granatum.core.domain.model.Aviso
import com.granatum.core.domain.model.CodigoAviso
import com.granatum.core.domain.model.Factura
import com.granatum.core.validation.NifValidator
import java.math.BigDecimal
import java.time.LocalDate

/** What the validator needs from outside the invoice itself. */
data class ContextoValidacion(
    val hoy: LocalDate,
    /** Fields the last recognition could not read with certainty. */
    val camposDudosos: List<String> = emptyList(),
    /** Another confirmed invoice has the same issuer, number and date. */
    val duplicada: Boolean = false,
    val trimestreCerrado: Boolean = false,
    /** `NO_ES_FACTURA` or `VARIAS_FACTURAS` from the last recognition, if so. */
    val resultadoReconocimiento: String? = null,
    /** Neither tax id is the company's. */
    val noEsDeLaEmpresa: Boolean = false
)

/**
 * The warnings of an invoice (FR-011 to FR-014, contracts/README.md): those that
 * **block** confirmation and those that only ask for a second look. Pure - no
 * database, no clock - so every rule has a unit test.
 *
 * Messages name fields and sums, never a party's name or tax id (FR-027).
 */
object ValidadorFactura {

    private val TOLERANCIA = BigDecimal("0.01")

    fun validar(f: Factura, ctx: ContextoValidacion): List<Aviso> = buildList {
        fun bloquea(campo: String, codigo: CodigoAviso, mensaje: String) = add(Aviso(campo, codigo, true, mensaje))
        fun avisa(campo: String, codigo: CodigoAviso, mensaje: String) = add(Aviso(campo, codigo, false, mensaje))

        // FR-013: what a confirmed invoice must have.
        if (f.emisor?.nif.isNullOrBlank()) bloquea("emisor.nif", CodigoAviso.OBLIGATORIO, "Falta el NIF del emisor")
        if (f.numero.isNullOrBlank()) bloquea("numero", CodigoAviso.OBLIGATORIO, "Falta el numero de factura")
        if (f.fechaEmision == null) bloquea("fechaEmision", CodigoAviso.OBLIGATORIO, "Falta la fecha de emision")
        if (f.lineas.isEmpty()) bloquea("lineas", CodigoAviso.OBLIGATORIO, "Falta al menos una base con su tipo de IVA")
        if (f.total == null) bloquea("total", CodigoAviso.OBLIGATORIO, "Falta el total")
        if (f.tipo == null) bloquea("tipo", CodigoAviso.OBLIGATORIO, "No se sabe si es emitida o recibida")

        // FR-012: control characters, the recipient's only if there is one.
        f.emisor?.nif?.takeIf { it.isNotBlank() }?.let {
            if (!NifValidator.esValido(NifValidator.normalizarFiscal(it))) {
                bloquea("emisor.nif", CodigoAviso.NIF_INVALIDO, "El NIF del emisor no tiene un control valido")
            }
        }
        f.destinatario?.nif?.takeIf { it.isNotBlank() }?.let {
            if (!NifValidator.esValido(NifValidator.normalizarFiscal(it))) {
                bloquea("destinatario.nif", CodigoAviso.NIF_INVALIDO, "El NIF del destinatario no tiene un control valido")
            }
        }

        // FR-011: bases + VAT + surcharge - withholdings = total, within a cent.
        val total = f.total
        if (total != null && f.lineas.isNotEmpty()) {
            val calculado = f.lineas.fold(BigDecimal.ZERO) { s, l -> s + l.base + l.cuota + l.recargo } - f.retenciones
            if ((calculado - total).abs() > TOLERANCIA) {
                bloquea(
                    "total", CodigoAviso.NO_CUADRA,
                    "Bases + IVA + recargo - retenciones = ${calculado.setScale(2).toPlainString()}; " +
                        "el total es ${total.setScale(2).toPlainString()}"
                )
            }
        }

        if (!f.rectificativa) {
            val importes = f.lineas.flatMap { listOf(it.base, it.cuota, it.recargo) } + listOfNotNull(total)
            if (importes.any { it.signum() < 0 }) {
                bloquea("total", CodigoAviso.IMPORTE_NEGATIVO, "Hay importes negativos y la factura no es rectificativa")
            }
        }

        // Research D-020: a line without VAT must say why.
        f.lineas.forEachIndexed { i, l ->
            if (l.cuota.signum() == 0 && l.causaSinCuota == null) {
                bloquea("lineas[$i].causaSinCuota", CodigoAviso.SIN_CAUSA_CUOTA_CERO, "Una linea sin cuota de IVA tiene que decir por que")
            }
        }

        if (f.moneda != "EUR") bloquea("moneda", CodigoAviso.MONEDA, "Solo se confirman importes en euros; introducelos en euros")

        f.fechaEmision?.let { fecha ->
            if (fecha.isAfter(ctx.hoy)) avisa("fechaEmision", CodigoAviso.FECHA_FUTURA, "La fecha de emision es posterior a hoy")
            if (fecha.isBefore(ctx.hoy.minusYears(4))) avisa("fechaEmision", CodigoAviso.FECHA_ANTIGUA, "La fecha de emision tiene mas de cuatro anos")
        }

        // From the context.
        if (ctx.duplicada) bloquea("numero", CodigoAviso.DUPLICADA, "Ya hay una factura confirmada con el mismo emisor, numero y fecha")
        if (ctx.trimestreCerrado) bloquea("fechaEmision", CodigoAviso.TRIMESTRE_CERRADO, "Su trimestre esta cerrado")
        if (ctx.noEsDeLaEmpresa) avisa("tipo", CodigoAviso.NO_ES_DE_LA_EMPRESA, "Ni el emisor ni el destinatario son la empresa")
        when (ctx.resultadoReconocimiento) {
            "NO_ES_FACTURA" -> avisa("documento", CodigoAviso.NO_ES_FACTURA, "El reconocimiento dice que el documento no es una factura")
            "VARIAS_FACTURAS" -> avisa("documento", CodigoAviso.VARIAS_FACTURAS, "El documento parece contener varias facturas; subelas por separado")
        }
        ctx.camposDudosos.forEach { avisa(it, CodigoAviso.DUDOSO, "El reconocimiento no lo leyo con seguridad") }
    }

    fun bloqueantes(avisos: List<Aviso>) = avisos.filter { it.bloquea }
}
