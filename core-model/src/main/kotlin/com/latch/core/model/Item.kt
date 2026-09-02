package com.latch.core.model

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * SRS §2.4: an Item is one thing the app will create — either an Event or a Task.
 */
enum class ItemType { EVENT, TASK }

/**
 * A draft item, before it has been written to Google. [remoteId] is null until FR-801
 * has written it and the API has returned an id.
 *
 * Google Tasks records only the date portion of a due date (§8.1), which is why [dueDate]
 * is a [LocalDate] and not a [LocalDateTime]. The UI must not offer a time field on a task.
 */
data class Item(
    val id: String,
    val captureId: String,
    val chainId: String? = null,
    val type: ItemType,
    val title: String,
    val start: LocalDateTime? = null,
    val end: LocalDateTime? = null,
    val allDay: Boolean = false,
    val dueDate: LocalDate? = null,
    val location: String? = null,
    val notes: String? = null,
    val calendarId: String? = null,
    val taskListId: String? = null,
    val remoteId: String? = null,
    /**
     * FR-601/FR-602: minutes before the start, from the recipe step that produced this item.
     *
     * On the item rather than only on [RecipeStep] because the step is gone by the time the
     * write happens — an item is what a save writes, and a queued one has to survive the
     * process that drafted it. Empty means Google's own calendar defaults apply, which is what
     * every item written before recipes existed had.
     *
     * Ignored for a task: Google Tasks has no reminder of its own (§8.1 is the same limitation
     * one field over), so a recipe step that asks for one on a task gets none.
     */
    val reminderMinutes: List<Int> = emptyList(),
    val syncState: SyncState = SyncState.DRAFT,
) {
    init {
        require(type == ItemType.EVENT || start == null) {
            "A task cannot carry a start time — Google Tasks discards it (SRS §8.1)"
        }
    }
}

enum class SyncState { DRAFT, QUEUED, WRITTEN, FAILED }
