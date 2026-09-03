package com.latch.wire

import com.latch.core.model.Holiday
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.core.model.Recipe
import com.latch.core.model.WorkingWeek
import com.latch.core.model.LatchSettings
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import com.latch.recipes.HolidayCalendar
import com.latch.recipes.PlannedItem
import com.latch.recipes.RecipeExpander
import com.latch.recipes.WorkingDayCalculator
import java.time.LocalDateTime

/**
 * FR-601 to FR-608: a captured date expanded into a chain, and the chain turned into items.
 *
 * **This lives in `:wire` beside `ItemDrafts`, for the same reason and for one more.** It is
 * pure and clock-free because the confirmation screen and the saver must reach the same items
 * from the same choices, and because the arithmetic AC-06 pins down should be testable without
 * a device. And it is *shared* because a recipe decides bytes Google receives: two clients
 * expanding one recipe over one date must produce the same chain, or a user who applies
 * "Meeting + prep" on a phone and again on a desktop gets two different sets of items from one
 * message — with §7.2's `chain_id` and `item_key` derived over each.
 *
 * It is why `:wire` depends on `:recipes`. Both are pure Kotlin, so the portability constraint
 * that governs this module is unmoved.
 */

/**
 * Why a recipe cannot be applied to this capture. Named, not phrased (NFR-402).
 */
enum class RecipeBlocker {
    /**
     * FR-601 expands **one** captured date, and this capture holds several.
     *
     * A narrowing, and the same one SRS 1.25 already took for FR-804: an action defined over one
     * date cannot be applied to four without answering a question this specification has not
     * asked — which of them anchors the chain, or whether four chains are produced, and what
     * FR-807's undo then groups. Offering it and silently picking the primary would be the
     * multi-image share sheet failure over again (FR-205's note): accept the gesture, act on
     * one of the things, and say nothing about the rest.
     */
    SEVERAL_DATES,

    /** A recipe expands a *date*, and this capture has none to expand. */
    NO_DATE,
}

/** Whether a recipe may be offered for this capture at all, and why not where it may not. */
fun recipeBlocker(result: ParseResult): RecipeBlocker? = when {
    result.candidates.size > 1 -> RecipeBlocker.SEVERAL_DATES
    result.primary.date == null -> RecipeBlocker.NO_DATE
    else -> null
}

/**
 * FR-604 and FR-605's arithmetic, configured from the user's settings.
 *
 * The bundled Indian list is a placeholder — three gazetted holidays that fall on fixed dates —
 * and §13's fourth open decision is exactly this question. What is not a placeholder is that
 * the list is *composed here* from the bundled set plus the user's additions minus their
 * removals, which is the shape FR-605 asks for whatever data eventually fills it.
 */
fun workingDayCalculator(settings: LatchSettings, bundled: List<Holiday>): WorkingDayCalculator =
    WorkingDayCalculator(
        workingWeek = WorkingWeek(settings.workingDays),
        holidays = HolidayCalendar(settings.holidays(bundled)),
    )

/**
 * FR-601: the chain this recipe would produce from this capture, or null where it cannot.
 *
 * The anchor is the primary candidate's date, with its time where it has one and midnight where
 * it does not — an all-day capture expanded by a recipe gives its steps a start of midnight,
 * which the drafting step below turns back into all-day events. Nothing is invented: a step's
 * date is arithmetic over a date the writer gave (FR-604), which is what a recipe *is*.
 */
fun expandRecipe(
    recipe: Recipe,
    result: ParseResult,
    settings: LatchSettings,
    bundledHolidays: List<Holiday>,
    chainId: String,
): List<PlannedItem>? {
    if (recipeBlocker(result) != null) return null
    val candidate = result.primary
    val date = candidate.date?.value ?: return null
    val anchor = candidate.time?.value?.let { LocalDateTime.of(date, it) } ?: date.atStartOfDay()

    return RecipeExpander(workingDayCalculator(settings, bundledHolidays)).expand(
        recipe = recipe,
        anchor = anchor,
        capturedTitle = result.title.value,
        chainId = chainId,
    )
}

/**
 * FR-601, FR-607, FR-608: the chain as items a save will write.
 *
 * **FR-607 holds structurally.** Every event in the chain takes [destination]'s calendar and
 * every task its task list, in this one place, so "all items in a chain shall be
 * written to the same calendar" is not something a call site can forget. `Recipe.targetCalendarId`
 * is deliberately not read here yet: honouring it would route a save to a calendar the user has
 * not seen on the confirmation screen, which FR-906 forbids outright, and the picker that would
 * fix that arrives with FR-905's routing rules in Settings.
 *
 * **FR-608 is [selected]**: the user may deselect individual items before saving, so an index
 * left out simply is not drafted. As with FR-511's checkboxes, an empty selection produces no
 * items and the caller reports it rather than writing an empty chain.
 *
 * [defaultReminderMinutes] is FR-1001's default lead time, applied only where a step names none
 * of its own — a step's reminders are a property of the recipe and outrank a global default.
 */
fun recipeItems(
    planned: List<PlannedItem>,
    selected: Set<Int>,
    captureId: String,
    chainId: String,
    destination: WireDestination,
    context: ParseContext,
    defaultReminderMinutes: List<Int> = emptyList(),
): List<Item> = planned
    .withIndex()
    .filter { (index, _) -> index in selected }
    .map { (index, step) ->
        val reminders = step.reminderOffsets
            .map { it.toMinutes().toInt() }
            .ifEmpty { defaultReminderMinutes }

        when (step.type) {
            ItemType.EVENT -> {
                val start = requireNotNull(step.start) { "A recipe event step has no start" }
                // An anchor with no time expands to midnight, which is an all-day event rather
                // than a meeting at 00:00 — the same reading `ItemDrafts` takes for a dated
                // capture with no time, and taken here too so the two cannot differ.
                val allDay = start.toLocalTime() == java.time.LocalTime.MIDNIGHT
                Item(
                    id = "$chainId#$index",
                    captureId = captureId,
                    chainId = chainId,
                    type = ItemType.EVENT,
                    title = step.title,
                    start = start,
                    end = if (allDay) {
                        // Google reads an all-day end date as exclusive.
                        start.toLocalDate().plusDays(1).atStartOfDay()
                    } else {
                        start.plus(context.defaultEventDuration)
                    },
                    allDay = allDay,
                    calendarId = destination.calendarId,
                    reminderMinutes = reminders,
                )
            }

            ItemType.TASK -> Item(
                id = "$chainId#$index",
                captureId = captureId,
                chainId = chainId,
                type = ItemType.TASK,
                title = step.title,
                // A task never carries a start: Google Tasks discards the time (§8.1).
                dueDate = step.dueDate,
                taskListId = destination.taskListId,
                // Google Tasks has no reminder of its own, so a step asking for one on a task
                // gets none. Recorded rather than silently dropped at the wire.
                reminderMinutes = emptyList(),
            )
        }
    }

/**
 * FR-606: which non-working days a step stepped over, so the UI can say "weekend skipped".
 *
 * The facts come from `:recipes` as data — that module has no access to Android resources and
 * NFR-402 requires every user-facing string to be externalised — so this is the shape the app
 * phrases, and nothing more. `ShiftResult.skippedAnything` is the test; these two counts are
 * what the sentence needs.
 */
data class SkippedDays(val nonWorkingDays: Int, val holidays: List<String>) {
    val any: Boolean get() = nonWorkingDays > 0 || holidays.isNotEmpty()
}

fun skippedDaysOf(step: PlannedItem): SkippedDays = SkippedDays(
    nonWorkingDays = step.shift.skippedNonWorkingDays.size,
    holidays = step.shift.skippedHolidays.map { it.name },
)

/**
 * What the confirmation screen and the saver both need to know about an applied recipe.
 *
 * Carried as one value for the reason `PendingOffer` is: the screen shows a chain and the saver
 * writes one, and two ways of arriving at it would eventually differ by a step.
 */
data class RecipeApplication(
    val recipeId: String,
    val planned: List<PlannedItem>,
    /** FR-608: the steps still ticked, by index. All of them to begin with. */
    val selected: Set<Int>,
)
