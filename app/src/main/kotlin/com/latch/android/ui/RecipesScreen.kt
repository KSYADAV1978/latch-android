package com.latch.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.latch.android.R
import com.latch.recipes.isShippedUnedited
import com.latch.recipes.withOffset
import com.latch.core.model.Direction
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep

/**
 * FR-602 and FR-603: the eight shipped recipes, and the user's own.
 *
 * **A built-in is never mutated** — editing one stores a copy carrying its id, which shadows the
 * shipped version. The list says which rows are shipped and which are edited copies, because
 * the difference decides what Delete does: on a shadowed built-in it restores the shipped one,
 * on the user's own it removes it.
 */
@Composable
fun RecipesScreen(
    recipes: List<Recipe>,
    onBack: () -> Unit,
    onSave: (Recipe) -> Unit,
    onDuplicate: (Recipe) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: () -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.recipes_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.recipes_back)) }
        }

        Note(stringResource(R.string.recipes_blurb))

        Button(onClick = onCreate) { Text(stringResource(R.string.recipes_new)) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(recipes, key = { it.id }) { recipe ->
                RecipeCard(
                    recipe = recipe,
                    expanded = editing == recipe.id,
                    onToggleExpanded = { editing = if (editing == recipe.id) null else recipe.id },
                    onSave = onSave,
                    onDuplicate = { onDuplicate(recipe) },
                    onDelete = { onDelete(recipe.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeCard(
    recipe: Recipe,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSave: (Recipe) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    // The whole recipe is edited locally and written on Done, rather than a write per keystroke.
    // A recipe is a small object and the alternative is a store call for every character typed
    // into a title template.
    var draft by remember(recipe, expanded) { mutableStateOf(recipe) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(recipe.name, style = MaterialTheme.typography.titleSmall)
                Note(
                    when {
                        isShippedUnedited(recipe) -> stringResource(R.string.recipes_built_in)
                        // Its id is a built-in's but the shipped version is not what is stored,
                        // so this row is the user's copy standing in for it.
                        !recipe.id.startsWith("user.") -> stringResource(R.string.recipes_edited_built_in)
                        else -> ""
                    }
                )
            }
            TextButton(onClick = onToggleExpanded) {
                Text(
                    stringResource(
                        if (expanded) R.string.recipes_done else R.string.recipes_edit
                    )
                )
            }
        }

        if (!expanded) {
            // Collapsed, a recipe is its steps in one line each — enough to recognise it by,
            // which is what a list is for.
            recipe.steps.forEach { step -> Note(describe(step)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDuplicate) { Text(stringResource(R.string.recipes_duplicate)) }
                if (!isShippedUnedited(recipe)) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.recipes_delete)) }
                }
            }
            return@Column
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text(stringResource(R.string.recipes_name)) },
            singleLine = true,
        )

        Note(stringResource(R.string.recipes_template_hint))

        draft.steps.forEachIndexed { index, step ->
            StepEditor(
                step = step,
                onChange = { changed ->
                    draft = draft.copy(
                        steps = draft.steps.toMutableList().also { it[index] = changed },
                    )
                },
                onRemove = {
                    // A recipe with no steps expands to nothing, so the last one cannot go —
                    // `decodeRecipe` refuses such a record for the same reason.
                    if (draft.steps.size > 1) {
                        draft = draft.copy(steps = draft.steps.filterIndexed { at, _ -> at != index })
                    }
                },
            )
            HorizontalDivider()
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    draft = draft.copy(steps = draft.steps + draft.steps.last().copy(offsetValue = 1))
                },
            ) { Text(stringResource(R.string.recipes_add_step)) }
            Button(
                onClick = {
                    onSave(draft)
                    onToggleExpanded()
                },
            ) { Text(stringResource(R.string.recipes_done)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepEditor(
    step: RecipeStep,
    onChange: (RecipeStep) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = step.titleTemplate,
            onValueChange = { onChange(step.copy(titleTemplate = it)) },
            label = { Text(stringResource(R.string.recipes_step_title)) },
            singleLine = true,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                modifier = Modifier.width(110.dp),
                value = step.offsetValue.toString(),
                // Clamped rather than validated on submit: `WorkingDayCalculator` refuses a
                // negative magnitude outright — direction is a separate field — and a number
                // that reached it would throw inside a save rather than here.
                onValueChange = { typed ->
                    onChange(withOffset(step, typed.toIntOrNull() ?: 0))
                },
                label = { Text(stringResource(R.string.recipes_step_offset)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // FR-604: calendar days or working days, per step.
            FilterChip(
                selected = step.offsetUnit == OffsetUnit.CALENDAR_DAYS,
                onClick = { onChange(step.copy(offsetUnit = OffsetUnit.CALENDAR_DAYS)) },
                label = { Text(stringResource(R.string.recipes_unit_calendar)) },
            )
            FilterChip(
                selected = step.offsetUnit == OffsetUnit.WORKING_DAYS,
                onClick = { onChange(step.copy(offsetUnit = OffsetUnit.WORKING_DAYS)) },
                label = { Text(stringResource(R.string.recipes_unit_working)) },
            )
            FilterChip(
                selected = step.direction == Direction.BEFORE,
                onClick = { onChange(step.copy(direction = Direction.BEFORE)) },
                label = { Text(stringResource(R.string.recipes_direction_before)) },
            )
            FilterChip(
                selected = step.direction == Direction.AFTER,
                onClick = { onChange(step.copy(direction = Direction.AFTER)) },
                label = { Text(stringResource(R.string.recipes_direction_after)) },
            )
            FilterChip(
                selected = step.itemType == ItemType.EVENT,
                onClick = { onChange(step.copy(itemType = ItemType.EVENT)) },
                label = { Text(stringResource(R.string.badge_event)) },
            )
            FilterChip(
                selected = step.itemType == ItemType.TASK,
                onClick = { onChange(step.copy(itemType = ItemType.TASK)) },
                label = { Text(stringResource(R.string.badge_task)) },
            )
            TextButton(onClick = onRemove) { Text(stringResource(R.string.recipes_remove_step)) }
        }
    }
}

/** A step in a line, for the collapsed list. Assembled from resources, so NFR-402 holds. */
@Composable
private fun describe(step: RecipeStep): String {
    val unit = stringResource(
        if (step.offsetUnit == OffsetUnit.WORKING_DAYS) R.string.recipes_unit_working
        else R.string.recipes_unit_calendar
    )
    val direction = stringResource(
        if (step.direction == Direction.BEFORE) R.string.recipes_direction_before
        else R.string.recipes_direction_after
    )
    val type = stringResource(
        if (step.itemType == ItemType.EVENT) R.string.badge_event else R.string.badge_task
    )
    return if (step.offsetValue == 0) {
        "$type · ${step.titleTemplate}"
    } else {
        "$type · ${step.offsetValue} $unit $direction · ${step.titleTemplate}"
    }
}
