package com.latch.core.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/**
 * A Recipe expands one captured date into a chain of related items (FR-601).
 * All items in a chain go to the same calendar so the chain can be hidden or removed
 * as a unit (FR-607).
 */
data class Recipe(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val targetCalendarId: String? = null,
    val steps: List<RecipeStep>,
)

data class RecipeStep(
    val offsetValue: Int,
    val offsetUnit: OffsetUnit,
    val direction: Direction,
    val itemType: ItemType,
    /** May contain `{title}`, substituted with the captured item's title. */
    val titleTemplate: String,
    val reminderOffsets: List<Duration> = emptyList(),
)

/** FR-604: offsets are in calendar days or working days. */
enum class OffsetUnit { CALENDAR_DAYS, WORKING_DAYS }

enum class Direction { BEFORE, AFTER }

/**
 * FR-605: working-day arithmetic uses a configurable working week and a holiday list.
 * Six-day weeks are common in the target market, so this is not a Mon–Fri constant.
 */
data class WorkingWeek(val workingDays: Set<DayOfWeek>) {
    init {
        require(workingDays.isNotEmpty()) { "A working week with no working days never terminates" }
    }

    fun isWorkingDay(date: LocalDate): Boolean = date.dayOfWeek in workingDays

    companion object {
        val MONDAY_TO_FRIDAY = WorkingWeek(
            setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
        )
        val MONDAY_TO_SATURDAY = WorkingWeek(MONDAY_TO_FRIDAY.workingDays + DayOfWeek.SATURDAY)
    }
}

/** FR-605: bundled or user-added. Removals are user edits to the bundled set. */
data class Holiday(
    val date: LocalDate,
    val name: String,
    val source: HolidaySource,
)

enum class HolidaySource { BUNDLED, USER }
