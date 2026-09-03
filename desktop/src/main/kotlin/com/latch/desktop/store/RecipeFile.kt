package com.latch.desktop.store

import com.latch.core.model.Direction
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import com.latch.google.json.JSONArray
import com.latch.google.json.JSONObject
import java.io.File
import java.time.Duration

/**
 * FR-603 on Windows: the user's own recipes, on disk, under DPAPI.
 *
 * **Only the user's own are stored.** FR-602's eight ship as code in `:recipes`, and a stored
 * recipe carrying a built-in's id **shadows** it — which is how "edit a built-in" is expressed
 * without either mutating shipped data or inventing a second concept, and is why deleting an
 * edit restores the shipped version. `recipesFor` is that rule and both clients compile it.
 *
 * **Encrypted like everything else here**, because a recipe name and its title templates are
 * text the user wrote.
 *
 * **An unreadable record is dropped**, and this is the one store on this client where that is
 * right: unlike a queue entry or an Inbox row, a recipe holds no capture. A recipe that cannot
 * be read is one the user can neither apply nor edit — a row they would see and could do
 * nothing with — and the shipped eight are unaffected because they are not in this file.
 *
 * **Order is the order they were first written**, so editing one does not move it under the
 * user's hands. The file is a list and the position in it is that order.
 */
class RecipeFile(
    private val file: File,
    private val secrets: WindowsSecrets = WindowsSecrets(),
) {
    /** The user's own, oldest first. */
    @Synchronized
    fun all(): List<Recipe> = read()

    @Synchronized
    fun save(recipe: Recipe) {
        val stored = read()
        val existing = stored.indexOfFirst { it.id == recipe.id }
        write(
            if (existing >= 0) stored.toMutableList().also { it[existing] = recipe }
            else stored + recipe,
        )
    }

    /**
     * FR-603's "delete".
     *
     * Deleting a **shadowed** built-in removes the copy, which restores the shipped one —
     * `recipesFor` puts it back the moment this row is gone. Deleting one of the user's own
     * removes it outright. Both are this one line, which is what makes them the same gesture.
     */
    @Synchronized
    fun delete(recipeId: String) {
        write(read().filterNot { it.id == recipeId })
    }

    /** NFR-205. */
    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun read(): List<Recipe> {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != RECIPE_FILE_VERSION) return emptyList()
        val ciphertexts = lines.drop(1).filter { it.isNotBlank() }.map { it.trim() }
        // One crossing of the DPAPI bridge for the whole file: this is read to build the
        // chooser on a capture popup, and NFR-101 budgets that path 800 ms.
        return secrets.unprotectAll(ciphertexts).mapNotNull { it?.let(::decodeRecipe) }
    }

    private fun write(recipes: List<Recipe>) {
        if (recipes.isEmpty()) {
            file.delete()
            return
        }
        val ciphertexts = secrets.protectAll(recipes.map(::encodeRecipe))
        val body = buildList {
            add(RECIPE_FILE_VERSION)
            ciphertexts.forEach {
                add(it ?: throw IllegalStateException("Windows would not encrypt a recipe"))
            }
        }
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, file.name + ".new")
        staging.writeText(body.joinToString("\n"))
        if (!staging.renameTo(file)) {
            file.delete()
            check(staging.renameTo(file)) { "could not replace " + file }
        }
    }
}

const val RECIPE_FILE_VERSION: String = "latch-recipes/1"

const val RECIPE_RECORD_VERSION: Int = 1

/** The field names are the Android record's, for the reason the Inbox record's are. */
fun encodeRecipe(recipe: Recipe): String {
    val steps = JSONArray()
    recipe.steps.forEach { step ->
        steps.put(
            JSONObject()
                .put("offset", step.offsetValue)
                .put("unit", step.offsetUnit.name)
                .put("direction", step.direction.name)
                .put("type", step.itemType.name)
                .put("title", step.titleTemplate)
                .put(
                    "reminders",
                    JSONArray().also { array ->
                        step.reminderOffsets.forEach { array.put(it.toMinutes()) }
                    },
                )
        )
    }
    return JSONObject()
        .put("v", RECIPE_RECORD_VERSION)
        .put("id", recipe.id)
        .put("name", recipe.name)
        // Always false for a stored recipe: `builtIn` says where a recipe came from, and one in
        // this file came from the user — including a copy of a shipped one, which is a copy and
        // not the original.
        .put("built_in", false)
        .put("target_calendar_id", recipe.targetCalendarId)
        .put("steps", steps)
        .toString()
}

fun decodeRecipe(record: String): Recipe? {
    val json = runCatching { JSONObject(record) }.getOrNull() ?: return null
    if (json.optInt("v", -1) != RECIPE_RECORD_VERSION) return null
    val id = json.optString("id").ifBlank { return null }
    val stepsJson = json.optJSONArray("steps") ?: return null

    val steps = (0 until stepsJson.length()).map { index ->
        val step = stepsJson.optJSONObject(index) ?: return null
        // Not `valueOf` anywhere: a unit, direction or type a later version introduced decodes
        // to null and takes only its own recipe with it, never the list.
        val unit = OffsetUnit.entries.firstOrNull { it.name == step.optString("unit") } ?: return null
        val direction = Direction.entries.firstOrNull { it.name == step.optString("direction") } ?: return null
        val type = ItemType.entries.firstOrNull { it.name == step.optString("type") } ?: return null
        val reminders = step.optJSONArray("reminders")
        RecipeStep(
            offsetValue = step.optInt("offset", 0),
            offsetUnit = unit,
            direction = direction,
            itemType = type,
            titleTemplate = step.optString("title"),
            reminderOffsets = (0 until (reminders?.length() ?: 0)).mapNotNull { at ->
                reminders?.optString(at)?.toLongOrNull()?.let(Duration::ofMinutes)
            },
        )
    }
    // A recipe with no steps expands to nothing, which is a row the user could apply and watch
    // do nothing at all.
    if (steps.isEmpty()) return null

    return Recipe(
        id = id,
        name = json.optString("name"),
        builtIn = false,
        targetCalendarId = json.optString("target_calendar_id").takeIf { it.isNotBlank() },
        steps = steps,
    )
}
