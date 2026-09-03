package com.latch.recipes

import com.latch.core.model.Direction
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import java.util.UUID

/**
 * FR-603: create, edit, duplicate and delete the user's own recipes.
 *
 * **A built-in is never mutated.** FR-602's eight ship as code here, so editing one stores a
 * copy carrying the same id, which [recipesFor] then uses in its place. That keeps the shipped
 * set stable across upgrades — an upgraded built-in reaches everyone who has not customised it
 * — and makes "reset to the shipped version" a delete rather than a feature.
 *
 * **These are in `:recipes` because both clients of §4.1 edit recipes**, and the shadowing rule
 * is the part that would be expensive to have twice: a client that minted a new id when editing
 * a built-in would leave the user with nine entries where the other showed eight, over one
 * account and one set of expectations. The stores stay per client — a recipe on disk is
 * storage, and storage is the thing this project deliberately does not share.
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
