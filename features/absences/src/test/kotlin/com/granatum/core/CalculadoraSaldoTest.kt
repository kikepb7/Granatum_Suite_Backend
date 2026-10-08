package com.granatum.core

import com.granatum.core.domain.model.Ausencia
import com.granatum.core.domain.model.CausaPermiso
import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.domain.model.TipoAusencia
import com.granatum.core.domain.service.CalculadoraSaldo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/** The holiday balance (feature 007, FR-014 to FR-017, research.md D-001). */
class CalculadoraSaldoTest {

    private val persona = UUID.randomUUID()

    private fun ausencia(
        desde: String,
        hasta: String?,
        estado: EstadoAusencia = EstadoAusencia.APROBADA,
        tipo: TipoAusencia = TipoAusencia.VACACIONES
    ) = Ausencia(
        UUID.randomUUID(), persona, tipo, if (tipo == TipoAusencia.PERMISO) CausaPermiso.MUDANZA else null,
        LocalDate.parse(desde), hasta?.let(LocalDate::parse), estado, null, null,
        persona, Instant.EPOCH, null, null, null, 0
    )

    @Test
    fun `calendar days, both ends included`() {
        assertEquals(12, CalculadoraSaldo.diasEnAnio(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-14"), 2026))
        assertEquals(1, CalculadoraSaldo.diasEnAnio(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-03"), 2026))
    }

    @Test
    fun `a range across new year counts in each year only its own days`() {
        val desde = LocalDate.parse("2026-12-28")
        val hasta = LocalDate.parse("2027-01-04")

        assertEquals(4, CalculadoraSaldo.diasEnAnio(desde, hasta, 2026))
        assertEquals(4, CalculadoraSaldo.diasEnAnio(desde, hasta, 2027))
        assertEquals(0, CalculadoraSaldo.diasEnAnio(desde, hasta, 2025))
    }

    @Test
    fun `approved and pending are counted separately, and the rest is available`() {
        val saldo = CalculadoraSaldo.saldo(
            2026, 30,
            listOf(
                ausencia("2026-08-03", "2026-08-14"),
                ausencia("2026-12-21", "2026-12-23", EstadoAusencia.PENDIENTE)
            )
        )

        assertEquals(12, saldo.aprobados)
        assertEquals(3, saldo.pendientes)
        assertEquals(15, saldo.disponibles)
    }

    @Test
    fun `cancelled and rejected requests give their days back`() {
        val saldo = CalculadoraSaldo.saldo(
            2026, 30,
            listOf(
                ausencia("2026-08-03", "2026-08-14", EstadoAusencia.CANCELADA),
                ausencia("2026-09-01", "2026-09-05", EstadoAusencia.RECHAZADA)
            )
        )

        assertEquals(30, saldo.disponibles)
    }

    @Test
    fun `paid leave and sick leave never use holidays, open or closed`() {
        val saldo = CalculadoraSaldo.saldo(
            2026, 30,
            listOf(
                ausencia("2026-05-04", "2026-05-08", tipo = TipoAusencia.PERMISO),
                ausencia("2026-03-01", "2026-03-20", tipo = TipoAusencia.BAJA_MEDICA),
                ausencia("2026-10-01", null, tipo = TipoAusencia.BAJA_MEDICA)
            )
        )

        assertEquals(30, saldo.disponibles)
    }
}
