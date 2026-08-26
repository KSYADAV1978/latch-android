package com.latch.recipes

import com.latch.core.model.Direction
import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import com.latch.core.model.OffsetUnit
import com.latch.core.model.WorkingWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkingDayCalculatorTest {

    private val calculator = WorkingDayCalculator()

    @Test
    fun `AC-06 - three working days before a Tuesday meeting lands on the preceding Thursday`() {
        val tuesday = LocalDate.of(2026, 9, 1)

        val result = calculator.shift(tuesday, 3, OffsetUnit.WORKING_DAYS, Direction.BEFORE)

        assertEquals(LocalDate.of(2026, 8, 27), result.date, "should be the preceding Thursday")
        assertEquals(
            listOf(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 29)),
            result.skippedNonWorkingDays,
            "the weekend it stepped over, which FR-606 requires the UI to mention",
        )
        assertTrue(result.skippedAnything)
    }

    @Test
    fun `calendar days do not skip anything`() {
        val tuesday = LocalDate.of(2026, 9, 1)

        val result = calculator.shift(tuesday, 3, OffsetUnit.CALENDAR_DAYS, Direction.BEFORE)

        assertEquals(LocalDate.of(2026, 8, 29), result.date, "a Saturday, and that is correct")
        assertFalse(result.skippedAnything)
    }

    @Test
    fun `holidays are stepped over and reported separately from weekends`() {
        val gandhiJayanti = Holiday(LocalDate.of(2026, 10, 2), "Gandhi Jayanti", HolidaySource.BUNDLED)
        val withHolidays = WorkingDayCalculator(
            workingWeek = WorkingWeek.MONDAY_TO_FRIDAY,
            holidays = HolidayCalendar(listOf(gandhiJayanti)),
        )
        // 2 October 2026 is a Friday, so this offset meets a holiday before it meets a weekend.
        val thursday = LocalDate.of(2026, 10, 1)

        val result = withHolidays.shift(thursday, 1, OffsetUnit.WORKING_DAYS, Direction.AFTER)

        assertEquals(LocalDate.of(2026, 10, 5), result.date, "Monday, after the holiday and weekend")
        assertEquals(listOf(gandhiJayanti), result.skippedHolidays)
        assertEquals(2, result.skippedNonWorkingDays.size)
    }

    @Test
    fun `a six-day working week treats Saturday as a working day`() {
        val sixDay = WorkingDayCalculator(workingWeek = WorkingWeek.MONDAY_TO_SATURDAY)
        val monday = LocalDate.of(2026, 8, 31)

        val result = sixDay.shift(monday, 5, OffsetUnit.WORKING_DAYS, Direction.AFTER)

        assertEquals(LocalDate.of(2026, 9, 5), result.date, "Saturday")
        assertEquals(0, result.skippedNonWorkingDays.size)
    }

    @Test
    fun `a zero offset stays put`() {
        val date = LocalDate.of(2026, 9, 5) // a Saturday, deliberately not a working day
        assertEquals(
            date,
            calculator.shift(date, 0, OffsetUnit.WORKING_DAYS, Direction.BEFORE).date,
        )
    }
}
