package com.granatum.core

import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.service.WorkedTimeCalculator
import com.granatum.core.domain.service.WorkedTimeCalculator.BreakInterval
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure unit tests: no Spring, no database. The computation is isolated in
 * `domain/service` precisely so the cases that matter can be checked without a
 * context.
 */
class WorkedTimeCalculatorTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun madrid(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.parse(time))
            .atZone(madrid)
            .toInstant()

    @Test
    fun `a nine hour shift with two breaks totalling an hour is 480 minutes`() {
        val minutes = WorkedTimeCalculator.workedMinutes(
            clockIn = madrid("2026-10-05", "07:00"),
            clockOut = madrid("2026-10-05", "16:00"),
            breaks = listOf(
                BreakInterval(madrid("2026-10-05", "09:00"), madrid("2026-10-05", "09:15")),
                BreakInterval(madrid("2026-10-05", "11:00"), madrid("2026-10-05", "11:45"))
            )
        )

        assertEquals(480, minutes)
    }

    @Test
    fun `a shift with no breaks counts the whole interval`() {
        val minutes = WorkedTimeCalculator.workedMinutes(
            clockIn = madrid("2026-10-05", "07:00"),
            clockOut = madrid("2026-10-05", "15:00"),
            breaks = emptyList()
        )

        assertEquals(480, minutes)
    }

    @Test
    fun `a shift crossing midnight is one shift of real elapsed time`() {
        val minutes = WorkedTimeCalculator.workedMinutes(
            clockIn = madrid("2026-10-07", "22:00"),
            clockOut = madrid("2026-10-08", "06:00"),
            breaks = emptyList()
        )

        assertEquals(480, minutes)
    }

    /**
     * The case that separates a correct implementation from one that looks
     * correct.
     *
     * On the night of 25 October 2026 the clocks in Spain go back an hour, so
     * between 00:00 and 08:00 *civil* time **nine** real hours elapse. A
     * computation over zone-less local times would answer 480 and under-record
     * an hour of someone's working day.
     */
    @Test
    fun `a shift across the autumn clock change counts the hour that really elapsed`() {
        val minutes = WorkedTimeCalculator.workedMinutes(
            clockIn = madrid("2026-10-25", "00:00"),
            clockOut = madrid("2026-10-25", "08:00"),
            breaks = emptyList()
        )

        assertEquals(540, minutes, "the night the clocks go back has 25 hours, so 00:00->08:00 is 9h")
    }

    /** And the spring change in the other direction: 7 real hours, not 8. */
    @Test
    fun `a shift across the spring clock change counts the hour that was lost`() {
        val minutes = WorkedTimeCalculator.workedMinutes(
            clockIn = madrid("2026-03-29", "00:00"),
            clockOut = madrid("2026-03-29", "08:00"),
            breaks = emptyList()
        )

        assertEquals(420, minutes, "the night the clocks go forward has 23 hours, so 00:00->08:00 is 7h")
    }

    /**
     * Breaks that exactly fill the shift give **zero** worked minutes, which is
     * a legitimate answer rather than an error.
     *
     * Worth pinning down, because it is the boundary that shows worked time can
     * never go negative while the overlap and in-range checks hold: breaks that
     * neither overlap nor fall outside the shift can sum at most its length. An
     * earlier version of this test asserted a rejection here and was simply
     * wrong about the requirement.
     */
    @Test
    fun `breaks exactly filling the shift give zero worked minutes, not an error`() {
        val twoAdjacentBreaks = WorkedTimeCalculator.workedMinutes(
            clockIn = madrid("2026-10-05", "08:00"),
            clockOut = madrid("2026-10-05", "09:00"),
            breaks = listOf(
                BreakInterval(madrid("2026-10-05", "08:00"), madrid("2026-10-05", "08:40")),
                BreakInterval(madrid("2026-10-05", "08:40"), madrid("2026-10-05", "09:00"))
            )
        )
        assertEquals(0, twoAdjacentBreaks)

        val oneFullBreak = WorkedTimeCalculator.workedMinutes(
            clockIn = madrid("2026-10-05", "08:00"),
            clockOut = madrid("2026-10-05", "09:00"),
            breaks = listOf(
                BreakInterval(madrid("2026-10-05", "08:00"), madrid("2026-10-05", "09:00"))
            )
        )
        assertEquals(0, oneFullBreak)
    }

    /** Contiguous breaks touch at a boundary; touching is not overlapping. */
    @Test
    fun `breaks that merely touch at a boundary are not treated as overlapping`() {
        val minutes = WorkedTimeCalculator.workedMinutes(
            clockIn = madrid("2026-10-05", "07:00"),
            clockOut = madrid("2026-10-05", "16:00"),
            breaks = listOf(
                BreakInterval(madrid("2026-10-05", "09:00"), madrid("2026-10-05", "09:30")),
                BreakInterval(madrid("2026-10-05", "09:30"), madrid("2026-10-05", "10:00"))
            )
        )
        assertEquals(480, minutes)
    }

    @Test
    fun `an exit before the entry is rejected`() {
        assertFailsWith<InconsistentValuesException> {
            WorkedTimeCalculator.workedMinutes(
                clockIn = madrid("2026-10-05", "16:00"),
                clockOut = madrid("2026-10-05", "07:00"),
                breaks = emptyList()
            )
        }
    }

    @Test
    fun `overlapping breaks are rejected`() {
        assertFailsWith<InconsistentValuesException> {
            WorkedTimeCalculator.workedMinutes(
                clockIn = madrid("2026-10-05", "07:00"),
                clockOut = madrid("2026-10-05", "16:00"),
                breaks = listOf(
                    BreakInterval(madrid("2026-10-05", "09:00"), madrid("2026-10-05", "10:00")),
                    BreakInterval(madrid("2026-10-05", "09:30"), madrid("2026-10-05", "10:30"))
                )
            )
        }
    }

    @Test
    fun `a break outside the shift is rejected`() {
        assertFailsWith<InconsistentValuesException> {
            WorkedTimeCalculator.workedMinutes(
                clockIn = madrid("2026-10-05", "07:00"),
                clockOut = madrid("2026-10-05", "16:00"),
                breaks = listOf(
                    BreakInterval(madrid("2026-10-05", "17:00"), madrid("2026-10-05", "17:30"))
                )
            )
        }
    }
}
