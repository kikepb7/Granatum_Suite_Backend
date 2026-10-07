package com.granatum.core

import com.granatum.core.domain.model.Aviso
import com.granatum.core.domain.model.CausaSinCuota
import com.granatum.core.domain.model.CodigoAviso
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.Factura
import com.granatum.core.domain.model.LineaIva
import com.granatum.core.domain.model.Parte
import com.granatum.core.domain.model.TipoFactura
import com.granatum.core.domain.service.ContextoValidacion
import com.granatum.core.domain.service.ValidadorFactura
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What stops an invoice from being confirmed, and what only warns (FR-011 to
 * FR-014, contracts/README.md). Pure: no database, no clock.
 */
class ValidadorFacturaTest {

    private val hoy = LocalDate.parse("2026-10-08")
    private fun d(v: String) = BigDecimal(v)

    /** 100.00 at 21% + 50.00 at 10% = 150.00 + 21.00 + 5.00 = 176.00, from A58818501 to the company. */
    private fun factura(
        emisorNif: String? = "A58818501",
        destinatarioNif: String? = "B12345674",
        numero: String? = "2026-0815",
        fecha: LocalDate? = LocalDate.parse("2026-10-02"),
        moneda: String = "EUR",
        lineas: List<LineaIva> = listOf(LineaIva(d("21.00"), d("100.00"), d("21.00")), LineaIva(d("10.00"), d("50.00"), d("5.00"))),
        retenciones: BigDecimal = d("0.00"),
        total: BigDecimal? = d("176.00"),
        rectificativa: Boolean = false,
        tipo: TipoFactura? = TipoFactura.RECIBIDA
    ) = Factura(
        UUID.randomUUID(), EstadoFactura.BORRADOR, tipo, Parte("Flores del Sur S.L.", emisorNif),
        destinatarioNif?.let { Parte("Floristeria Granatum S.L.", it) }, numero, fecha, "Rosas", moneda,
        lineas, retenciones, total, rectificativa, 0
    )

    private fun avisos(f: Factura, ctx: ContextoValidacion = ContextoValidacion(hoy = hoy)) = ValidadorFactura.validar(f, ctx)
    private fun List<Aviso>.codigos() = map { it.codigo }.toSet()
    private fun List<Aviso>.bloquean() = filter { it.bloquea }.map { it.codigo }.toSet()

    @Test
    fun `a correct invoice has no warnings`() {
        assertEquals(emptyList(), avisos(factura()))
    }

    /** FR-013 */
    @Test
    fun `issuer tax id, number, date, at least one line, the total and the type are required`() {
        val a = avisos(factura(emisorNif = null, numero = null, fecha = null, lineas = emptyList(), total = null, tipo = null))
        assertEquals(setOf("emisor.nif", "numero", "fechaEmision", "lineas", "total", "tipo"),
            a.filter { it.codigo == CodigoAviso.OBLIGATORIO }.map { it.campo }.toSet())
        assertTrue(a.all { it.codigo != CodigoAviso.OBLIGATORIO || it.bloquea })
    }

    /** FR-011: bases + VAT + surcharge − withholdings = total, within one cent. */
    @Test
    fun `the total must add up, within one cent`() {
        assertFalse(CodigoAviso.NO_CUADRA in avisos(factura(total = d("176.01"))).codigos(), "one cent of rounding")
        assertTrue(CodigoAviso.NO_CUADRA in avisos(factura(total = d("176.02"))).bloquean())
        assertTrue(CodigoAviso.NO_CUADRA in avisos(factura(total = d("175.00"))).bloquean())
        assertEquals(emptyList(), avisos(factura(retenciones = d("15.00"), total = d("161.00"))), "withholdings subtract")
        val conRecargo = listOf(LineaIva(d("21.00"), d("100.00"), d("21.00"), d("5.20")))
        assertEquals(emptyList(), avisos(factura(lineas = conRecargo, total = d("126.20"))), "surcharge adds")
    }

    @Test
    fun `the message of a mismatch shows the sums, not the parties`() {
        val aviso = avisos(factura(total = d("175.00"))).single { it.codigo == CodigoAviso.NO_CUADRA }
        assertTrue(aviso.mensaje.contains("176.00") && aviso.mensaje.contains("175.00"))
        assertFalse(aviso.mensaje.contains("A58818501") || aviso.mensaje.contains("Flores"))
    }

    /** FR-012 */
    @Test
    fun `a tax id with a wrong control blocks, the issuer's or the recipient's`() {
        assertTrue(CodigoAviso.NIF_INVALIDO in avisos(factura(emisorNif = "A58818502")).bloquean())
        assertTrue(CodigoAviso.NIF_INVALIDO in avisos(factura(destinatarioNif = "B12345675")).bloquean())
        assertEquals(emptyList(), avisos(factura(emisorNif = "ES-A58818501")), "the VAT prefix and punctuation are fine")
    }

    @Test
    fun `a simplified invoice without recipient is fine`() {
        assertEquals(emptyList(), avisos(factura(destinatarioNif = null)))
    }

    @Test
    fun `negative amounts only in a credit note`() {
        val negativas = listOf(LineaIva(d("21.00"), d("-100.00"), d("-21.00")))
        assertTrue(CodigoAviso.IMPORTE_NEGATIVO in avisos(factura(lineas = negativas, total = d("-121.00"))).bloquean())
        assertEquals(emptyList(), avisos(factura(lineas = negativas, total = d("-121.00"), rectificativa = true)))
    }

    /** Research D-020: a line with no VAT must say why. */
    @Test
    fun `a zero-VAT line needs a cause`() {
        val sinCausa = listOf(LineaIva(d("0.00"), d("100.00"), d("0.00")))
        assertTrue(CodigoAviso.SIN_CAUSA_CUOTA_CERO in avisos(factura(lineas = sinCausa, total = d("100.00"))).bloquean())
        val exenta = listOf(LineaIva(d("0.00"), d("100.00"), d("0.00"), causaSinCuota = CausaSinCuota.EXENTA))
        assertEquals(emptyList(), avisos(factura(lineas = exenta, total = d("100.00"))))
    }

    @Test
    fun `a currency other than euro blocks`() {
        assertTrue(CodigoAviso.MONEDA in avisos(factura(moneda = "USD")).bloquean())
    }

    @Test
    fun `a future date or one older than four years only warns`() {
        val futura = avisos(factura(fecha = hoy.plusDays(1)))
        assertEquals(setOf(CodigoAviso.FECHA_FUTURA), futura.codigos())
        assertTrue(futura.bloquean().isEmpty())
        val antigua = avisos(factura(fecha = hoy.minusYears(4).minusDays(1)))
        assertEquals(setOf(CodigoAviso.FECHA_ANTIGUA), antigua.codigos())
        assertTrue(antigua.bloquean().isEmpty())
    }

    @Test
    fun `context warnings - doubtful fields, duplicates, closed quarter, what the recognition said`() {
        val ctx = ContextoValidacion(
            hoy = hoy,
            camposDudosos = listOf("numero"),
            duplicada = true,
            trimestreCerrado = true,
            resultadoReconocimiento = "VARIAS_FACTURAS",
            noEsDeLaEmpresa = true
        )
        val a = avisos(factura(), ctx)
        assertEquals(setOf(CodigoAviso.DUDOSO, CodigoAviso.DUPLICADA, CodigoAviso.TRIMESTRE_CERRADO, CodigoAviso.VARIAS_FACTURAS, CodigoAviso.NO_ES_DE_LA_EMPRESA), a.codigos())
        assertEquals(setOf(CodigoAviso.DUPLICADA, CodigoAviso.TRIMESTRE_CERRADO), a.bloquean(), "doubtful, several and not-ours only warn")
        assertEquals("numero", a.single { it.codigo == CodigoAviso.DUDOSO }.campo)
        assertTrue(CodigoAviso.NO_ES_FACTURA in avisos(factura(), ContextoValidacion(hoy = hoy, resultadoReconocimiento = "NO_ES_FACTURA")).codigos())
    }
}
