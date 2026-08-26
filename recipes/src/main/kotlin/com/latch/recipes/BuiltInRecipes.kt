package com.latch.recipes

import com.latch.core.model.Direction
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import java.time.Duration

/**
 * FR-602: the eight recipes the app ships with.
 *
 * The English text here is a fallback, not the display string. Every recipe has a stable id
 * and the app resolves the name and title templates from string resources keyed on it, so
 * that NFR-402 (all strings externalised) and NFR-403 (Hindi UI) stay possible. Ids are part
 * of the stored data once a user has edited a copy, so they must not be renamed.
 *
 * Users can duplicate and edit any of these, and create their own (FR-603).
 */
object BuiltInRecipes {

    const val MEETING_WITH_PREP = "builtin.meeting_prep"
    const val APPOINTMENT = "builtin.appointment"
    const val SCHOOL_EVENT = "builtin.school_event"
    const val TRAVEL_BOOKING = "builtin.travel_booking"
    const val PAYMENT_DUE = "builtin.payment_due"
    const val RENEWAL = "builtin.renewal"
    const val EXAM_OR_INTERVIEW = "builtin.exam_interview"
    const val DELIVERY = "builtin.delivery"

    val all: List<Recipe> = listOf(
        Recipe(
            id = MEETING_WITH_PREP,
            name = "Meeting + prep",
            builtIn = true,
            steps = listOf(
                event(0, "{title}", reminders = listOf(Duration.ofMinutes(30))),
                task(3, OffsetUnit.WORKING_DAYS, Direction.BEFORE, "Prepare for {title}"),
                task(1, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, "Follow up: {title}"),
            ),
        ),
        Recipe(
            id = APPOINTMENT,
            name = "Appointment",
            builtIn = true,
            steps = listOf(
                event(0, "{title}", reminders = listOf(Duration.ofHours(2))),
                task(1, OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, "Confirm appointment: {title}"),
            ),
        ),
        Recipe(
            id = SCHOOL_EVENT,
            name = "School or class event",
            builtIn = true,
            steps = listOf(
                event(0, "{title}", reminders = listOf(Duration.ofHours(12))),
                task(2, OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, "Get ready for {title}"),
            ),
        ),
        Recipe(
            id = TRAVEL_BOOKING,
            name = "Travel booking",
            builtIn = true,
            steps = listOf(
                event(0, "{title}", reminders = listOf(Duration.ofHours(3))),
                task(7, OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, "Check in / documents: {title}"),
                task(1, OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, "Pack for {title}"),
            ),
        ),
        Recipe(
            id = PAYMENT_DUE,
            name = "Payment due",
            builtIn = true,
            steps = listOf(
                task(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, "{title}"),
                task(3, OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, "Pay: {title}"),
            ),
        ),
        Recipe(
            id = RENEWAL,
            name = "Renewal or subscription",
            builtIn = true,
            steps = listOf(
                task(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, "{title}"),
                task(14, OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, "Decide on renewal: {title}"),
            ),
        ),
        Recipe(
            id = EXAM_OR_INTERVIEW,
            name = "Exam or interview",
            builtIn = true,
            steps = listOf(
                event(0, "{title}", reminders = listOf(Duration.ofDays(1), Duration.ofHours(2))),
                task(7, OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, "Start preparing: {title}"),
                task(1, OffsetUnit.WORKING_DAYS, Direction.BEFORE, "Final revision: {title}"),
            ),
        ),
        Recipe(
            id = DELIVERY,
            name = "Delivery",
            builtIn = true,
            steps = listOf(
                task(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, "{title}"),
                task(1, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, "Check delivery: {title}"),
            ),
        ),
    )

    fun byId(id: String): Recipe? = all.firstOrNull { it.id == id }

    private fun event(
        offset: Int,
        template: String,
        reminders: List<Duration> = emptyList(),
    ) = RecipeStep(
        offsetValue = offset,
        offsetUnit = OffsetUnit.CALENDAR_DAYS,
        direction = Direction.AFTER,
        itemType = ItemType.EVENT,
        titleTemplate = template,
        reminderOffsets = reminders,
    )

    private fun task(
        offset: Int,
        unit: OffsetUnit,
        direction: Direction,
        template: String,
    ) = RecipeStep(
        offsetValue = offset,
        offsetUnit = unit,
        direction = direction,
        itemType = ItemType.TASK,
        titleTemplate = template,
    )
}
