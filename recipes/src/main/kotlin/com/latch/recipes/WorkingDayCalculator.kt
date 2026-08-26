package com.latch.recipes

import com.latch.core.model.Direction
import com.latch.core.model.Holiday
import com.latch.core.model.OffsetUnit
import com.latch.core.model.WorkingWeek
import java.time.LocalDate

/**
 * FR-604 / FR-605: offsets in calendar days or working days, against a configurable working
 * week and holiday list.
 */
class WorkingDayCalculator(
    private val workingWeek: WorkingWeek = WorkingWeek.MONDAY_TO_FRIDAY,
    private val holidays: HolidayCalendar = HolidayCalendar.EMPTY,
) {

    fun shift(
        from: LocalDate,
        value: Int,
        unit: OffsetUnit,
        direction: Direction,
    ): ShiftResult {
        require(value >= 0) { "Offset magnitude is $value; use Direction to go backwards" }
        val step = if (direction == Direction.BEFORE) -1L else 1L

        if (unit == OffsetUnit.CALENDAR_DAYS) {
            return ShiftResult(from.plusDays(step * value), emptyList(), emptyList())
        }

        var current = from
        var remaining = value
        val skippedNonWorking = mutableListOf<LocalDate>()
        val skippedHolidays = mutableListOf<Holiday>()

        while (remaining > 0) {
            current = current.plusDays(step)
            val holiday = holidays.on(current)
            when {
                holiday != null -> skippedHolidays += holiday
                !workingWeek.isWorkingDay(current) -> skippedNonWorking += current
                else -> remaining--
            }
        }

        return ShiftResult(current, skippedNonWorking, skippedHolidays)
    }
}

/**
 * The outcome of a shift, including what it stepped over.
 *
 * FR-606 requires the UI to say that non-working days were skipped ("weekend skipped —
 * 3 working days"). The wording is not built here: this module has no access to Android
 * resources and NFR-402 requires every user-facing string to be externalised for
 * translation. So the facts come back as data and the app phrases them.
 */
data class ShiftResult(
    val date: LocalDate,
    val skippedNonWorkingDays: List<LocalDate>,
    val skippedHolidays: List<Holiday>,
) {
    val skippedAnything: Boolean
        get() = skippedNonWorkingDays.isNotEmpty() || skippedHolidays.isNotEmpty()
}

/** FR-605: a bundled list plus user additions and removals. */
class HolidayCalendar(holidays: Collection<Holiday>) {

    private val byDate: Map<LocalDate, Holiday> = holidays.associateBy { it.date }

    fun on(date: LocalDate): Holiday? = byDate[date]

    operator fun plus(added: Collection<Holiday>) = HolidayCalendar(byDate.values + added)

    operator fun minus(removed: Collection<LocalDate>) =
        HolidayCalendar(byDate.values.filterNot { it.date in removed })

    companion object {
        val EMPTY = HolidayCalendar(emptyList())
    }
}
