package com.latch.recipes

import com.latch.core.model.ItemType
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * FR-601: expands one captured date into a chain of related items.
 *
 * Everything in the chain carries the same [PlannedItem.chainId], which is what makes
 * FR-607 (one calendar per chain), FR-807 (undo the whole save) and FR-804 (reschedule the
 * chain, not just the item) implementable later.
 */
class RecipeExpander(private val calculator: WorkingDayCalculator = WorkingDayCalculator()) {

    /**
     * The anchor is a **date and a nullable time**, not a `LocalDateTime`, and that is the
     * whole of SRS 1.190's fix.
     *
     * A `LocalDateTime` anchor cannot say whether the writer gave a time: an all-day capture
     * arrives as midnight and so does `at 12am`. Everything downstream that needs to tell the
     * two apart — `recipeItems`' all-day flag, the desktop's when-line — was then left
     * comparing the start against midnight and getting the second case wrong. Taking the time
     * as nullable makes the fact impossible to lose on the way in, rather than something a
     * later reader has to reconstruct from a value that no longer holds it.
     */
    fun expand(
        recipe: Recipe,
        anchorDate: LocalDate,
        anchorTime: LocalTime?,
        capturedTitle: String,
        chainId: String,
    ): List<PlannedItem> = recipe.steps.map { step ->
        val shift = calculator.shift(
            from = anchorDate,
            value = step.offsetValue,
            unit = step.offsetUnit,
            direction = step.direction,
        )
        PlannedItem(
            chainId = chainId,
            type = step.itemType,
            title = step.titleTemplate.replace(TITLE_PLACEHOLDER, capturedTitle),
            // A task keeps only its date: Google Tasks discards the time (§8.1).
            start = if (step.itemType == ItemType.EVENT) {
                LocalDateTime.of(shift.date, anchorTime ?: LocalTime.MIDNIGHT)
            } else {
                null
            },
            dueDate = if (step.itemType == ItemType.TASK) shift.date else null,
            anchorHadTime = anchorTime != null,
            reminderOffsets = step.reminderOffsets,
            shift = shift,
            step = step,
        )
    }

    private companion object {
        const val TITLE_PLACEHOLDER = "{title}"
    }
}

/**
 * One item a recipe would create, before it is given an id and a destination calendar.
 * [shift] is carried through so the confirmation UI can satisfy FR-606 — the user is told
 * which days the calculation stepped over.
 */
data class PlannedItem(
    val chainId: String,
    val type: ItemType,
    val title: String,
    val start: LocalDateTime?,
    val dueDate: java.time.LocalDate?,
    /**
     * Whether the *captured* date carried a clock time (SRS 1.190).
     *
     * [start] cannot answer this and must not be asked: an anchor with no time expands to
     * midnight, so `start.toLocalTime() == MIDNIGHT` is true both for a capture that named no
     * time and for one that named 00:00. This is the same fact `ItemDrafts` tests as
     * `candidate.time == null`, carried here so the two drafting paths in `:wire` decide the
     * all-day flag from one fact rather than from two readings of a value that has lost it.
     */
    val anchorHadTime: Boolean,
    val reminderOffsets: List<Duration>,
    val shift: ShiftResult,
    val step: RecipeStep,
)
