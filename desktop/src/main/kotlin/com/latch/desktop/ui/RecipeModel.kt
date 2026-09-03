package com.latch.desktop.ui

import com.latch.core.model.Direction
import com.latch.core.model.ItemType
import com.latch.core.model.LatchSettings
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.recipes.IndianHolidays
import com.latch.recipes.PlannedItem
import com.latch.recipes.isShippedUnedited
import com.latch.wire.RecipeApplication
import com.latch.wire.RecipeBlocker
import com.latch.wire.SkippedDays
import com.latch.wire.expandRecipe
import com.latch.wire.recipeBlocker
import com.latch.wire.skippedDaysOf
import com.latch.parser.ParseResult
import java.time.format.DateTimeFormatter

/**
 * FR-601's chooser on the capture popup, and FR-606's and FR-608's rows beneath it.
 *
 * Everything the sheet says about a recipe is decided here, `CaptureWindow` renders it, and the
 * same `expandRecipe` runs on both clients — so a chain the desktop shows is the chain the
 * phone would show for the same message.
 */
data class RecipeChooser(
    /** FR-602's eight, with any the user has shadowed replaced and their own appended. */
    val recipes: List<Recipe>,
    /** Null where a recipe may be applied. Otherwise the reason it may not, in words. */
    val blocked: String?,
) {
    val canChoose: Boolean get() = blocked == null && recipes.isNotEmpty()
}

/** One expanded step, as it reads on the sheet. FR-608's checkbox is [checked]. */
data class RecipeStepRow(
    val index: Int,
    val badge: String,
    val whenLine: String,
    val title: String,
    val checked: Boolean,
    /** FR-606: which non-working days this step stepped over, said explicitly. */
    val skipped: String?,
)

/** The applied chain as the sheet draws it, with the recipe's own name above it. */
data class RecipeChainModel(
    val recipeName: String,
    val rows: List<RecipeStepRow>,
    val summary: String,
    val canSave: Boolean,
)

/**
 * FR-601: whether a recipe may be offered for this capture, and the reason where it may not.
 *
 * **The reason is on screen rather than expressed as a missing control**, which is the narrowing
 * SRS 1.25 already recorded for FR-804 and the finding SRS 1.65 recorded for this client's
 * badge: a chooser that simply vanished for a four-date capture would read as a feature that
 * does not exist.
 */
fun recipeChooser(result: ParseResult, recipes: List<Recipe>): RecipeChooser = RecipeChooser(
    recipes = recipes,
    blocked = recipeBlocker(result)?.let(::recipeBlockerText),
)

internal fun recipeBlockerText(blocker: RecipeBlocker): String = when (blocker) {
    RecipeBlocker.SEVERAL_DATES -> DesktopStrings.RECIPE_SEVERAL_DATES
    RecipeBlocker.NO_DATE -> DesktopStrings.RECIPE_NO_DATE
}

/** FR-601 and FR-608: the expanded chain, as rows. */
fun recipeChainModel(
    recipe: Recipe,
    planned: List<PlannedItem>,
    selected: Set<Int>,
): RecipeChainModel = RecipeChainModel(
    recipeName = recipe.name,
    rows = planned.mapIndexed { index, step ->
        RecipeStepRow(
            index = index,
            badge = if (step.type == ItemType.EVENT) DesktopStrings.BADGE_EVENT else DesktopStrings.BADGE_TASK,
            whenLine = stepWhenLine(step),
            title = step.title,
            checked = index in selected,
            skipped = skippedText(skippedDaysOf(step)),
        )
    },
    summary = DesktopStrings.RECIPE_SUMMARY.replace("%d", planned.size.toString()),
    // FR-608 taken to its limit: everything unticked writes nothing, and saying so beats a
    // save that appears to succeed and creates none.
    canSave = selected.isNotEmpty(),
)

/**
 * The chain a chosen recipe produces, or null where it produces none.
 *
 * The holiday list is composed here from the bundled Indian set plus the user's additions minus
 * their removals, which is FR-605's shape whatever data eventually fills it.
 */
fun applyRecipe(
    recipe: Recipe,
    result: ParseResult,
    settings: LatchSettings,
    chainId: String,
    today: java.time.LocalDate = java.time.LocalDate.now(),
): RecipeApplication? {
    val planned = expandRecipe(recipe, result, settings, IndianHolidays.around(today), chainId)
        ?: return null
    if (planned.isEmpty()) return null
    // FR-608: every step ticked to begin with, which is what the requirement asks a
    // deselection to be a departure from.
    return RecipeApplication(recipe.id, planned, planned.indices.toSet())
}

/**
 * FR-606, in words: "the UI shall say so explicitly".
 *
 * The facts come from `:recipes` as data — that module cannot phrase anything (NFR-402) — and
 * this is the sentence. Null where nothing was stepped over, because a line saying "0 days
 * skipped" beside every ordinary step would bury the one that matters.
 */
internal fun skippedText(skipped: SkippedDays): String? {
    if (!skipped.any) return null
    val parts = buildList {
        if (skipped.nonWorkingDays > 0) {
            add(
                if (skipped.nonWorkingDays == 1) DesktopStrings.RECIPE_SKIPPED_ONE
                else DesktopStrings.RECIPE_SKIPPED_MANY.replace("%d", skipped.nonWorkingDays.toString())
            )
        }
        // Named rather than counted: a user who put a holiday in their own list wants to see
        // which one moved their step.
        if (skipped.holidays.isNotEmpty()) add(skipped.holidays.joinToString(", "))
    }
    return DesktopStrings.RECIPE_SKIPPED.replace("%s", parts.joinToString(", "))
}

private val STEP_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM uuuu")
private val STEP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun stepWhenLine(step: PlannedItem): String {
    val start = step.start
    if (start != null) {
        val day = start.format(STEP_DAY)
        // An anchor with no time expands to midnight, which `recipeItems` turns into an all-day
        // event rather than a meeting at 00:00 — so the line must not offer a time either.
        return if (start.toLocalTime() == java.time.LocalTime.MIDNIGHT) day
        else day + ", " + start.format(STEP_TIME)
    }
    return step.dueDate?.format(STEP_DAY) ?: DesktopStrings.NO_DATE
}

// ---------------------------------------------------------------- FR-603's editor

/** One step, as the editor's fields. Strings, for the reason `SettingsForm` holds strings. */
data class RecipeStepForm(
    val offset: String,
    val unit: OffsetUnit,
    val direction: Direction,
    val type: ItemType,
    val titleTemplate: String,
    /** Minutes before the start, comma-separated. Blank means the calendar's own. */
    val reminders: String,
)

data class RecipeForm(val id: String, val name: String, val steps: List<RecipeStepForm>)

/** Why a recipe could not be saved. Named; phrased below (NFR-402). */
enum class RecipeProblem {
    NO_NAME,
    NO_STEPS,
    OFFSET,
    /** FR-601's template is what a step's item is called; an empty one produces a nameless item. */
    NO_TITLE,
    REMINDERS,
}

data class RecipeApplied(val recipe: Recipe?, val problems: List<RecipeProblem>)

fun formOf(recipe: Recipe): RecipeForm = RecipeForm(
    id = recipe.id,
    name = recipe.name,
    steps = recipe.steps.map { step ->
        RecipeStepForm(
            offset = step.offsetValue.toString(),
            unit = step.offsetUnit,
            direction = step.direction,
            type = step.itemType,
            titleTemplate = step.titleTemplate,
            reminders = step.reminderOffsets.joinToString(", ") { it.toMinutes().toString() },
        )
    },
)

/**
 * FR-603's edit, as a pure function: what the typed recipe means, or every reason it means
 * nothing.
 *
 * **`builtIn` is false whatever was edited**, which is what makes a stored copy shadow a
 * shipped one rather than replace it: the flag says where a recipe came from, and this one came
 * from the user even though its id did not. `editableCopyOf` states the same rule one layer up
 * and both clients compile it.
 *
 * **A negative offset is refused rather than clamped here.** `withOffset` clamps, because a
 * spinner that would not go below zero is a control the user can feel; a *typed* negative is a
 * user saying something the calculator cannot accept — its own `require` says a magnitude must
 * be non-negative, direction being a separate field — and silently turning "-3 before" into
 * "0 before" would move a step by three days without saying so.
 */
fun applyRecipeForm(form: RecipeForm): RecipeApplied {
    val problems = mutableListOf<RecipeProblem>()
    if (form.name.isBlank()) problems += RecipeProblem.NO_NAME
    if (form.steps.isEmpty()) problems += RecipeProblem.NO_STEPS

    val steps = form.steps.map { step ->
        val offset = step.offset.trim().toIntOrNull()?.takeIf { it >= 0 }
        if (offset == null) problems += RecipeProblem.OFFSET
        if (step.titleTemplate.isBlank()) problems += RecipeProblem.NO_TITLE
        val reminders = remindersOf(step.reminders)
        if (reminders == null) problems += RecipeProblem.REMINDERS
        Triple(offset, step, reminders)
    }

    if (problems.isNotEmpty()) return RecipeApplied(null, problems.distinct())

    return RecipeApplied(
        Recipe(
            id = form.id,
            name = form.name.trim(),
            builtIn = false,
            steps = steps.map { (offset, step, reminders) ->
                com.latch.core.model.RecipeStep(
                    offsetValue = offset!!,
                    offsetUnit = step.unit,
                    direction = step.direction,
                    itemType = step.type,
                    titleTemplate = step.titleTemplate.trim(),
                    reminderOffsets = reminders!!.map { java.time.Duration.ofMinutes(it.toLong()) },
                )
            },
        ),
        emptyList(),
    )
}

fun recipeProblemText(problem: RecipeProblem): String = when (problem) {
    RecipeProblem.NO_NAME -> "Give the recipe a name."
    RecipeProblem.NO_STEPS -> "A recipe needs at least one step. One with none would do nothing."
    RecipeProblem.OFFSET ->
        "Each step's offset must be a whole number of days, zero or more. Before or after is " +
            "the other control."
    RecipeProblem.NO_TITLE -> "Each step needs a title. Use {title} where the captured title should go."
    RecipeProblem.REMINDERS ->
        "Reminders must be minutes, separated by commas. Leave a step's empty to use your " +
            "calendar's own."
}

/** What the list says about each row: shipped, edited, or the user's own. */
fun recipeOrigin(recipe: Recipe, stored: List<Recipe>): String = when {
    isShippedUnedited(recipe) -> DesktopStrings.RECIPE_SHIPPED
    stored.any { it.id == recipe.id } && recipe.id.startsWith("builtin.") -> DesktopStrings.RECIPE_EDITED
    else -> DesktopStrings.RECIPE_YOURS
}

/**
 * FR-603's delete, in words, because the two cases are genuinely different actions behind one
 * button and the user has to be told which they are about to take.
 */
fun deleteLabelFor(recipe: Recipe, stored: List<Recipe>): String =
    if (recipe.id.startsWith("builtin.") && stored.any { it.id == recipe.id }) {
        DesktopStrings.RECIPE_RESTORE
    } else {
        DesktopStrings.RECIPE_DELETE
    }
