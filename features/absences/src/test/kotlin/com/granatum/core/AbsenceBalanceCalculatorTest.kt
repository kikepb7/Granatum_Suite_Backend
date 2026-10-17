package com.granatum.core

import com.granatum.core.domain.model.Absence
import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.domain.model.AbsenceType
import com.granatum.core.domain.model.LeaveCause
import com.granatum.core.domain.service.AbsenceBalanceCalculator
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/** The holiday balance (feature 007, FR-014 to FR-017, research.md D-001). */
class AbsenceBalanceCalculatorTest {

    private val employee = UUID.randomUUID()

    private fun absence(
        from: String,
        to: String?,
        status: AbsenceStatus = AbsenceStatus.APPROVED,
        type: AbsenceType = AbsenceType.VACATION
    ) = Absence(
        UUID.randomUUID(), employee, type, if (type == AbsenceType.LEAVE) LeaveCause.HOUSE_MOVE else null,
        LocalDate.parse(from), to?.let(LocalDate::parse), status, null, null,
        employee, Instant.EPOCH, null, null, null, 0
    )

    @Test
    fun `calendar days, both ends included`() {
        assertEquals(12, AbsenceBalanceCalculator.daysInYear(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-14"), 2026))
        assertEquals(1, AbsenceBalanceCalculator.daysInYear(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-03"), 2026))
    }

    @Test
    fun `a range across new year counts in each year only its own days`() {
        val from = LocalDate.parse("2026-12-28")
        val to = LocalDate.parse("2027-01-04")

        assertEquals(4, AbsenceBalanceCalculator.daysInYear(from, to, 2026))
        assertEquals(4, AbsenceBalanceCalculator.daysInYear(from, to, 2027))
        assertEquals(0, AbsenceBalanceCalculator.daysInYear(from, to, 2025))
    }

    @Test
    fun `approved and pending are counted separately, and the rest is available`() {
        val balance = AbsenceBalanceCalculator.balance(
            2026, 30,
            listOf(
                absence("2026-08-03", "2026-08-14"),
                absence("2026-12-21", "2026-12-23", AbsenceStatus.PENDING)
            )
        )

        assertEquals(12, balance.approved)
        assertEquals(3, balance.pending)
        assertEquals(15, balance.available)
    }

    @Test
    fun `cancelled and rejected requests give their days back`() {
        val balance = AbsenceBalanceCalculator.balance(
            2026, 30,
            listOf(
                absence("2026-08-03", "2026-08-14", AbsenceStatus.CANCELLED),
                absence("2026-09-01", "2026-09-05", AbsenceStatus.REJECTED)
            )
        )

        assertEquals(30, balance.available)
    }

    @Test
    fun `paid leave and sick leave never use holidays, open or closed`() {
        val balance = AbsenceBalanceCalculator.balance(
            2026, 30,
            listOf(
                absence("2026-05-04", "2026-05-08", type = AbsenceType.LEAVE),
                absence("2026-03-01", "2026-03-20", type = AbsenceType.SICK_LEAVE),
                absence("2026-10-01", null, type = AbsenceType.SICK_LEAVE)
            )
        )

        assertEquals(30, balance.available)
    }
}
