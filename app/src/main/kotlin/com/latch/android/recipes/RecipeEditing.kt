package com.latch.android.recipes

import com.latch.core.model.Recipe
import com.latch.data.RecipeStore
import com.latch.recipes.duplicateOf
import com.latch.recipes.editableCopyOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
