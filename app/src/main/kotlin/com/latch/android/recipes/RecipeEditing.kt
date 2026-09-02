package com.latch.android.recipes

import com.latch.core.model.Direction
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import com.latch.data.RecipeStore
import com.latch.recipes.BuiltInRecipes
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * FR-603: create, edit, duplicate and delete the user's own recipes.
 *
 * **A built-in is never mutated.** FR-602's eight ship as code in `:recipes`, so editing one
 * stores a copy carrying the same id, which `recipesFor` then uses in its place. That keeps the
 * shipped set stable across upgrades — an upgraded built-in reaches everyone who has not
 * customised it — and makes "reset to the shipped version" a delete rather than a feature.
 *
 * The pure half is below; the coordinator is the thin layer that persists what it decides.
 */

/** FR-603's "create": an empty recipe with one step, so the editor opens on something. */
fun newRecipe(name: String, stepTitle: String): Recipe = Recipe(
    id = "user." + UUID.randomUUID(),
    name = name,
    builtIn = false,
    steps = listOf(
        RecipeStep(
            offsetValue = 0,
            offsetUnit = OffsetUnit.CALENDAR_DAYS,
            direction = Direction.AFTER,
            itemType = ItemType.EVENT,
            titleTemplate = stepTitle,
        )
    ),
)

/**
 * FR-603's "duplicate".
 *
 * The copy gets a **new id**, always — including when the source is a built-in. A duplicate
 * that kept the id would shadow the original rather than sit beside it, which is what *edit*
 * means here and is the opposite of what the user asked for.
 */
fun duplicateOf(recipe: Recipe, nameSuffix: String): Recipe = recipe.copy(
    id = "user." + UUID.randomUUID(),
    name = recipe.name + nameSuffix,
    builtIn = false,
)

/**
 * FR-603's "edit", where the recipe being edited is a built-in.
 *
 * The id is kept, which is what makes the stored copy shadow the shipped one. `builtIn` goes to
 * false because it now came from the user — the flag says where a recipe came from, and this
 * one came from them even though its id did not.
 */
fun editableCopyOf(recipe: Recipe): Recipe =
    if (recipe.builtIn) recipe.copy(builtIn = false) else recipe

/** Whether this recipe is the shipped version, unedited. Decides what the list says about it. */
fun isShippedUnedited(recipe: Recipe): Boolean =
    recipe.builtIn && BuiltInRecipes.byId(recipe.id) == recipe

/**
 * A step's offset, edited.
 *
 * `WorkingDayCalculator` requires a non-negative magnitude — direction is a separate field, and
 * its own `require` says so — so a negative typed into the field is clamped rather than allowed
 * to reach the calculator and throw inside a save.
 */
fun withOffset(step: RecipeStep, value: Int): RecipeStep = step.copy(offsetValue = value.coerceAtLeast(0))

/** FR-603, held by `LatchApplication` for the reason every coordinator here is: it outlives a screen. */
class RecipeCoordinator(
    private val store: RecipeStore,
    private val scope: CoroutineScope,
    /** The recipe list on the capture sheet is now stale. */
    private val onChanged: () -> Unit,
) {
    fun save(recipe: Recipe) {
        scope.launch {
            runCatching { store.save(editableCopyOf(recipe)) }
            onChanged()
        }
    }

    fun duplicate(recipe: Recipe, nameSuffix: String) {
        scope.launch {
            runCatching { store.save(duplicateOf(recipe, nameSuffix)) }
            onChanged()
        }
    }

    /**
     * FR-603's "delete".
     *
     * Deleting a **shadowed** built-in restores the shipped one rather than removing it from
     * the list, which is the only sensible reading: the eight are part of the product and a
     * user cannot delete what ships. Deleting one of their own removes it outright.
     */
    fun delete(recipeId: String) {
        scope.launch {
            runCatching { store.delete(recipeId) }
            onChanged()
        }
    }
}
