package com.latch.data

import android.content.Context
import com.latch.core.model.Direction
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * FR-603: users shall be able to create, edit, duplicate and delete their own recipes.
 *
 * **Only the user's own are stored.** FR-602's eight built-ins live in `:recipes` as code, and
 * a user who edits one gets a copy — `BuiltInRecipes` is not writable, which is what keeps the
 * shipped set stable across upgrades and what makes "duplicate" the natural way to start. A
 * stored recipe with a built-in's id therefore **shadows** it, which is how "edit a built-in"
 * is expressed without either mutating shipped data or inventing a second concept.
 *
 * The payload is encrypted like every other record here: a recipe name and its title templates
 * are text the user wrote.
 */
interface RecipeStore {
    /** The user's own, by id. */
    suspend fun all(): List<Recipe>

    suspend fun save(recipe: Recipe)

    suspend fun delete(recipeId: String)

    /** NFR-205. */
    suspend fun deleteAll()
}

/** [RecipeStore] over [LatchDatabase]. */
class SqliteRecipeStore(context: Context) : RecipeStore {

    private val database = LatchDatabase(context)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    override suspend fun all(): List<Recipe> = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            LatchDatabase.TABLE_RECIPES,
            arrayOf("id", "payload"),
            null,
            null,
            null,
            null,
            "created_at ASC",
        ).consume { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val recipe = cipher.decrypt(cursor.getString(1))?.let(::decodeRecipe)
                    if (recipe == null) {
                        // Unreadable now is unreadable for ever, and a recipe that cannot be
                        // read is one the user can neither apply nor edit — a row they would
                        // see and could do nothing with.
                        database.writableDatabase.delete(
                            LatchDatabase.TABLE_RECIPES,
                            "id = ?",
                            arrayOf(cursor.getString(0)),
                        )
                    } else {
                        add(recipe)
                    }
                }
            }
        }
    }

    override suspend fun save(recipe: Recipe) {
        withContext(Dispatchers.IO) {
            database.writableDatabase.replaceOrThrow(
                LatchDatabase.TABLE_RECIPES,
                null,
                contentValuesOf(
                    "id" to recipe.id,
                    // Kept on the first write, so editing a recipe does not move it to the end
                    // of the user's own list under their hands.
                    "created_at" to createdAt(recipe.id),
                    "payload" to cipher.encrypt(encodeRecipe(recipe)),
                ),
            )
        }
    }

    private fun createdAt(recipeId: String): Long =
        database.readableDatabase.query(
            LatchDatabase.TABLE_RECIPES,
            arrayOf("created_at"),
            "id = ?",
            arrayOf(recipeId),
            null,
            null,
            null,
        ).consume { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else System.currentTimeMillis()
        }

    override suspend fun delete(recipeId: String) {
        withContext(Dispatchers.IO) {
            database.writableDatabase
                .delete(LatchDatabase.TABLE_RECIPES, "id = ?", arrayOf(recipeId))
        }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            database.writableDatabase.delete(LatchDatabase.TABLE_RECIPES, null, null)
        }
    }

    private companion object {
        const val KEY_ALIAS = "latch.recipes.v1"
    }
}

// ---------------------------------------------------------------------------------------
// The record format. Internal and pure, so it is JVM-tested; the SQL is not.
// ---------------------------------------------------------------------------------------

internal const val RECIPE_RECORD_VERSION = 1

internal fun encodeRecipe(recipe: Recipe): String {
    val steps = JSONArray()
    recipe.steps.forEach { step ->
        steps.put(
            JSONObject()
                .put("offset", step.offsetValue)
                .put("unit", step.offsetUnit.name)
                .put("direction", step.direction.name)
                .put("type", step.itemType.name)
                .put("title", step.titleTemplate)
                .put("reminders", JSONArray(step.reminderOffsets.map { it.toMinutes() }))
        )
    }
    return JSONObject()
        .put("v", RECIPE_RECORD_VERSION)
        .put("id", recipe.id)
        .put("name", recipe.name)
        // Always false for a stored recipe: `builtIn` says where a recipe came from, and one in
        // this table came from the user — including a copy of a shipped one, which is a copy and
        // not the original.
        .put("built_in", false)
        .putOpt("target_calendar_id", recipe.targetCalendarId)
        .put("steps", steps)
        .toString()
}

internal fun decodeRecipe(record: String): Recipe? = try {
    val json = JSONObject(record)
    val stepsJson = json.getJSONArray("steps")
    val steps = (0 until stepsJson.length()).map { index ->
        val step = stepsJson.getJSONObject(index)
        // Not valueOf anywhere: a unit, direction or type a later version introduced decodes to
        // null and takes only its own recipe with it, never the list.
        val unit = OffsetUnit.entries.firstOrNull { it.name == step.getString("unit") }
        val direction = Direction.entries.firstOrNull { it.name == step.getString("direction") }
        val type = ItemType.entries.firstOrNull { it.name == step.getString("type") }
        if (unit == null || direction == null || type == null) {
            null
        } else {
            RecipeStep(
                offsetValue = step.getInt("offset"),
                offsetUnit = unit,
                direction = direction,
                itemType = type,
                titleTemplate = step.getString("title"),
                reminderOffsets = step.optJSONArray("reminders")
                    ?.let { array -> (0 until array.length()).map { Duration.ofMinutes(array.getLong(it)) } }
                    .orEmpty(),
            )
        }
    }
    when {
        json.optInt("v") != RECIPE_RECORD_VERSION -> null
        // A recipe with no steps expands to nothing, which is a row the user could apply and
        // watch do nothing at all.
        steps.isEmpty() || steps.any { it == null } -> null
        else -> Recipe(
            id = json.getString("id"),
            name = json.getString("name"),
            builtIn = false,
            targetCalendarId = json.optString("target_calendar_id").takeIf { it.isNotBlank() },
            steps = steps.filterNotNull(),
        )
    }
} catch (malformed: JSONException) {
    null
}
