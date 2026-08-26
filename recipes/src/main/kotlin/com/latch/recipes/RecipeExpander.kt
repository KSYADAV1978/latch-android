package com.latch.recipes

import com.latch.core.model.ItemType
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import java.time.Duration
import java.time.LocalDateTime

/**
 * FR-601: expands one captured date into a chain of related items.
 *
 * Everything in the chain carries the same [PlannedItem.chainId], which is what makes
 * FR-607 (one calendar per chain), FR-807 (undo the whole save) and FR-804 (reschedule the
 * chain, not just the item) implementable later.
 */
class RecipeExpander(private val calculator: WorkingDayCalculator = WorkingDayCalculator()) {

    fun expand(
        recipe: Recipe,
        anchor: LocalDateTime,
        capturedTitle: String,
        chainId: String,
    ): List<PlannedItem> = recipe.steps.map { step ->
        val shift = calculator.shift(
            from = anchor.toLocalDate(),
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
                LocalDateTime.of(shift.date, anchor.toLocalTime())
            } else {
                null
            },
            dueDate = if (step.itemType == ItemType.TASK) shift.date else null,
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
    val reminderOffsets: List<Duration>,
    val shift: ShiftResult,
    val step: RecipeStep,
)
