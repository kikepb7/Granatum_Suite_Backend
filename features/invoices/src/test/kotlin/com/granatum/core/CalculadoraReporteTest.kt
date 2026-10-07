package com.granatum.core

import com.granatum.core.domain.model.CausaSinCuota
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.Factura
import com.granatum.core.domain.model.LineaIva
import com.granatum.core.domain.model.Parte
import com.granatum.core.domain.model.Periodo
import com.granatum.core.domain.model.TipoFactura
import com.granatum.core.domain.service.CalculadoraReporte
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/** FR-018 to FR-023: exact totals, by group and VAT rate. Pure. */
class CalculadoraReporteTest {

    private fun d(v: String) = BigDecimal(v)
    private val ahora = Instant.parse("2026-10-08T10:00:00Z")

    private fun factura(
        tipo: TipoFactura,
        fecha: String,
        lineas: List<LineaIva>,
        retenciones: String = "0.00",
        rectificativa: Boolean = false
    ): Factura {
        val total = lineas.fold(BigDecimal.ZERO) { s, l -> s + l.base + l.cuota + l.recargo } - d(retenciones)
        return Factura(UUID.randomUUID(), EstadoFactura.CONFIRMADA, tipo, Parte("X", "A58818501"), null, "N",
            LocalDate.parse(fecha), null, "EUR", lineas, d(retenciones), total, rectificativa, 0)
    }

    private fun linea(tipo: String, base: String, cuota: String, recargo: String = "0.00", causa: CausaSinCuota? = null) =
        LineaIva(d(tipo), d(base), d(cuota), d(recargo), causa)

    @Test
    fun `issued and received are totalled apart, VAT by rate`() {
        val facturas = listOf(
            factura(TipoFactura.EMITIDA, "2026-07-10", listOf(linea("21.00", "100.00", "21.00"))),
            factura(TipoFactura.EMITIDA, "2026-08-10", listOf(linea("21.00", "200.00", "42.00"), linea("10.00", "50.00", "5.00"))),
            factura(TipoFactura.RECIBIDA, "2026-09-10", listOf(linea("21.00", "1000.00", "210.00")), retenciones = "150.00")
        )
        val r = CalculadoraReporte.calcular(Periodo.Trimestral(2026, 3), facturas, pendientes = 2, trimestresCerrados = emptyList(), calculadoEn = ahora)

        assertEquals(2, r.emitidas.facturas)
        assertEquals(d("350.00"), r.emitidas.base)
        assertEquals(mapOf(d("10.00") to d("5.00"), d("21.00") to d("63.00")), r.emitidas.ivaPorTipo)
        assertEquals(d("418.00"), r.emitidas.total)
        assertEquals(1, r.recibidas.facturas)
        assertEquals(d("150.00"), r.recibidas.retenciones)
        assertEquals(d("1060.00"), r.recibidas.total)
        assertEquals(2, r.pendientes)
        assertEquals(listOf(d("10.00"), d("21.00")), r.emitidas.ivaPorTipo.keys.toList(), "ordered by rate")
    }

    @Test
    fun `surcharge and lines without VAT are reported on their own`() {
        val facturas = listOf(
            factura(TipoFactura.RECIBIDA, "2026-07-01", listOf(linea("21.00", "100.00", "21.00", recargo = "5.20"))),
            factura(TipoFactura.RECIBIDA, "2026-07-02", listOf(linea("0.00", "320.00", "0.00", causa = CausaSinCuota.INTRACOMUNITARIA))),
            factura(TipoFactura.RECIBIDA, "2026-07-03", listOf(linea("0.00", "80.00", "0.00", causa = CausaSinCuota.EXENTA)))
        )
        val r = CalculadoraReporte.calcular(Periodo.Mensual(2026, 7), facturas, 0, emptyList(), ahora)
        assertEquals(d("5.20"), r.recibidas.recargo)
        assertEquals(mapOf(CausaSinCuota.INTRACOMUNITARIA to d("320.00"), CausaSinCuota.EXENTA to d("80.00")), r.recibidas.sinCuota)
        assertEquals(mapOf(d("21.00") to d("21.00")), r.recibidas.ivaPorTipo, "a 0% line is not a VAT rate with tax")
    }

    /** FR-023: a credit note's negative amounts subtract. */
    @Test
    fun `credit notes subtract`() {
        val facturas = listOf(
            factura(TipoFactura.EMITIDA, "2026-07-01", listOf(linea("21.00", "100.00", "21.00"))),
            factura(TipoFactura.EMITIDA, "2026-07-20", listOf(linea("21.00", "-250.00", "-52.50")), rectificativa = true)
        )
        val r = CalculadoraReporte.calcular(Periodo.Mensual(2026, 7), facturas, 0, emptyList(), ahora)
        assertEquals(d("-150.00"), r.emitidas.base)
        assertEquals(d("-181.50"), r.emitidas.total)
        assertEquals(2, r.emitidas.facturas)
    }

    /** FR-020: three months add up to their quarter, four quarters to the year. */
    @Test
    fun `smaller periods add up to the period that contains them`() {
        val r = java.util.Random(7)
        val facturas = (1..120).map { i ->
            val fecha = LocalDate.of(2026, 1, 1).plusDays(r.nextInt(365).toLong())
            val base = BigDecimal(r.nextInt(100_000)).movePointLeft(2)
            val cuota = (base * d("0.21")).setScale(2, java.math.RoundingMode.HALF_UP)
            factura(if (i % 2 == 0) TipoFactura.EMITIDA else TipoFactura.RECIBIDA, fecha.toString(), listOf(linea("21.00", base.toPlainString(), cuota.toPlainString())))
        }
        fun de(p: Periodo) = CalculadoraReporte.calcular(p, facturas.filter { it.fechaEmision!! in p.desde..p.hasta }, 0, emptyList(), ahora)

        val anual = de(Periodo.Anual(2026))
        val trimestres = (1..4).map { de(Periodo.Trimestral(2026, it)) }
        assertEquals(anual.emitidas.total, trimestres.fold(BigDecimal.ZERO) { s, t -> s + t.emitidas.total })
        assertEquals(anual.recibidas.base, trimestres.fold(BigDecimal.ZERO) { s, t -> s + t.recibidas.base })
        (1..4).forEach { t ->
            val meses = ((t - 1) * 3 + 1..t * 3).map { de(Periodo.Mensual(2026, it)) }
            assertEquals(trimestres[t - 1].emitidas.total, meses.fold(BigDecimal.ZERO) { s, m -> s + m.emitidas.total })
            assertEquals(trimestres[t - 1].recibidas.facturas, meses.sumOf { it.recibidas.facturas })
        }
        assertEquals(120, anual.emitidas.facturas + anual.recibidas.facturas)
    }

    @Test
    fun `an empty period has zero totals, not missing ones`() {
        val r = CalculadoraReporte.calcular(Periodo.Anual(2025), emptyList(), 0, emptyList(), ahora)
        assertEquals(0, r.emitidas.facturas)
        assertEquals(d("0.00"), r.emitidas.total)
    }
}
