package com.latch.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.latch.android.R
import com.latch.android.setup.Async
import com.latch.android.setup.SetupEvent
import com.latch.android.setup.SetupFailure
import com.latch.android.setup.SetupState
import com.latch.android.setup.SetupStep
import com.latch.core.model.RoutingMode
import com.latch.data.TaskList
import com.latch.data.WritableCalendar

private const val STEP_COUNT = 3

/**
 * First-run setup (FR-101 to FR-110). Three steps, no text entry beyond Google's own sign-in,
 * and no fourth screen after Finish — all of which is what FR-108's 60 seconds costs.
 *
 * Stateless: every decision belongs to the state machine in `com.latch.android.setup`, so
 * nothing here can create anything in the user's Google account (FR-105).
 */
@Composable
fun SetupFlow(state: SetupState, onEvent: (SetupEvent) -> Unit) {
    when (state.step) {
        SetupStep.SIGN_IN -> SignInStep(state, onEvent)
        SetupStep.DESTINATION -> DestinationStep(state, onEvent)
        SetupStep.TASK_LIST -> TaskListStep(state, onEvent)
    }
}

@Composable
private fun SignInStep(state: SetupState, onEvent: (SetupEvent) -> Unit) {
    val signedInAs = state.account?.email
    StepScaffold(
        stepNumber = 1,
        title = stringResource(R.string.setup_signin_title),
        onBack = null,
        primaryLabel = signedInAs
            ?.let { stringResource(R.string.setup_signin_continue_as, it) }
            ?: stringResource(R.string.setup_signin_action),
        primaryEnabled = state.signIn !is Async.Loading,
        onPrimary = { onEvent(SetupEvent.SignInRequested) },
    ) {
        Text(stringResource(R.string.setup_signin_blurb), style = MaterialTheme.typography.bodyLarge)

        // FR-102: this has to be legible on the screen itself, not folded behind a link.
        OutlinedCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.setup_signin_privacy_mail),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.setup_signin_privacy_servers),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // FR-002: the four scopes, said in the user's words rather than Google's.
        Text(stringResource(R.string.setup_signin_scopes_heading), style = MaterialTheme.typography.bodyMedium)
        Bullet(stringResource(R.string.setup_signin_scope_events))
        Bullet(stringResource(R.string.setup_signin_scope_calendars))
        Bullet(stringResource(R.string.setup_signin_scope_tasks))
        Text(
            stringResource(R.string.setup_signin_scopes_footer),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        when (val signIn = state.signIn) {
            is Async.Loading -> Working(stringResource(R.string.setup_signin_working))
            is Async.Failed -> Problem(
                message = stringResource(
                    if (signIn.failure == SetupFailure.SIGN_IN_CANCELLED) R.string.setup_signin_cancelled
                    else R.string.setup_signin_error
                ),
                onRetry = { onEvent(SetupEvent.RetryRequested) },
            )
            else -> Unit
        }
    }
}

@Composable
private fun DestinationStep(state: SetupState, onEvent: (SetupEvent) -> Unit) {
    StepScaffold(
        stepNumber = 2,
        title = stringResource(R.string.setup_destination_title),
        onBack = { onEvent(SetupEvent.BackRequested) },
        primaryLabel = stringResource(R.string.setup_next),
        primaryEnabled = state.canLeaveDestination,
        onPrimary = { onEvent(SetupEvent.NextRequested) },
    ) {
        // FR-103: exactly two options, Option B pre-selected and marked Recommended.
        ChoiceCard(
            selected = state.mode == RoutingMode.LATCH_CALENDAR,
            onSelect = { onEvent(SetupEvent.ModeChosen(RoutingMode.LATCH_CALENDAR)) },
            title = stringResource(R.string.setup_destination_latch_title),
            badge = stringResource(R.string.setup_destination_recommended),
        ) {
            // FR-104: one colour, one show/hide tick box, undo a batch.
            Text(
                stringResource(R.string.setup_destination_latch_benefit),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    // §4.1: another Latch client may have made the calendar already, in
                    // which case this run adopts it and creates nothing.
                    if (state.adoptableLatchCalendar != null) R.string.setup_destination_latch_exists
                    else R.string.setup_destination_latch_when
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ChoiceCard(
            selected = state.mode == RoutingMode.EXISTING_CALENDARS,
            onSelect = { onEvent(SetupEvent.ModeChosen(RoutingMode.EXISTING_CALENDARS)) },
            title = stringResource(R.string.setup_destination_existing_title),
            badge = null,
        ) {
            Text(
                stringResource(R.string.setup_destination_existing_benefit),
                style = MaterialTheme.typography.bodyMedium,
            )

            // The picker only appears under Option A. FR-906: the user must have seen the
            // calendar their captures will go to.
            if (state.mode == RoutingMode.EXISTING_CALENDARS) {
                Text(
                    stringResource(R.string.setup_destination_pick),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                when (val calendars = state.calendars) {
                    is Async.Ready -> calendars.value.forEach { calendar ->
                        CalendarRow(
                            calendar = calendar,
                            selected = calendar.id == state.chosenCalendarId,
                            onSelect = { onEvent(SetupEvent.CalendarChosen(calendar.id)) },
                        )
                    }
                    is Async.Failed -> Problem(
                        message = stringResource(R.string.setup_destination_error),
                        onRetry = { onEvent(SetupEvent.RetryRequested) },
                    )
                    else -> Working(stringResource(R.string.setup_destination_loading))
                }

                if (state.chosenCalendarIsHidden) {
                    Text(
                        stringResource(R.string.setup_destination_hidden_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // FR-903 / AC-09: the offer to turn it on. Off by default — this edits a
                    // calendar the user already owns, so it is theirs to opt into, and
                    // FR-105 means nothing happens until Finish either way.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEvent(SetupEvent.MakeVisibleChosen(!state.makeChosenVisible)) },
                    ) {
                        Checkbox(
                            checked = state.makeChosenVisible,
                            onCheckedChange = { onEvent(SetupEvent.MakeVisibleChosen(it)) },
                        )
                        Text(
                            stringResource(R.string.setup_destination_hidden_offer),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskListStep(state: SetupState, onEvent: (SetupEvent) -> Unit) {
    StepScaffold(
        stepNumber = 3,
        title = stringResource(R.string.setup_tasks_title),
        onBack = { onEvent(SetupEvent.BackRequested) },
        primaryLabel = stringResource(R.string.setup_finish),
        primaryEnabled = state.canFinish,
        onPrimary = { onEvent(SetupEvent.FinishRequested) },
    ) {
        Text(stringResource(R.string.setup_tasks_blurb), style = MaterialTheme.typography.bodyLarge)

        when (val taskLists = state.taskLists) {
            is Async.Ready -> taskLists.value.forEach { taskList ->
                TaskListRow(
                    taskList = taskList,
                    selected = taskList.id == state.chosenTaskListId,
                    onSelect = { onEvent(SetupEvent.TaskListChosen(taskList.id)) },
                )
            }
            is Async.Failed -> Problem(
                message = stringResource(R.string.setup_tasks_error),
                onRetry = { onEvent(SetupEvent.RetryRequested) },
            )
            else -> Working(stringResource(R.string.setup_tasks_loading))
        }

        // FR-107: task lists share one Tasks layer and cannot be coloured or hidden
        // individually. Said here because it is the moment the choice is being made.
        OutlinedCard {
            Text(
                stringResource(R.string.setup_tasks_disclosure),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }

        when (val commit = state.commit) {
            is Async.Loading -> Working(stringResource(R.string.setup_commit_working))
            is Async.Failed -> Problem(
                // NFR-303: which step failed, and what it means for what was saved.
                message = stringResource(
                    if (commit.failure == SetupFailure.CALENDAR_NOT_CREATED) R.string.setup_commit_error_calendar
                    else R.string.setup_commit_error_save
                ),
                onRetry = null,
            )
            else -> Unit
        }
    }
}

@Composable
private fun StepScaffold(
    stepNumber: Int,
    title: String,
    onBack: (() -> Unit)?,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            stringResource(R.string.setup_step_counter, stepNumber, STEP_COUNT),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()

            // FR-109: nothing chosen here is final, and the screens have to say so.
            if (stepNumber > 1) {
                Text(
                    stringResource(R.string.setup_changeable_later),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
            } else {
                Box(Modifier.width(1.dp))
            }
            Button(onClick = onPrimary, enabled = primaryEnabled) { Text(primaryLabel) }
        }
    }
}

@Composable
private fun ChoiceCard(
    selected: Boolean,
    onSelect: () -> Unit,
    title: String,
    badge: String?,
    body: @Composable () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (badge != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                body()
            }
        }
    }
}

@Composable
private fun CalendarRow(calendar: WritableCalendar, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        // FR-902: the calendar's own colour, so it looks the same here as in Google.
        Box(
            modifier = Modifier
                .padding(start = 4.dp, end = 12.dp)
                .size(12.dp)
                .background(calendar.backgroundColor.toComposeColour(), CircleShape)
        )
        Text(calendar.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // FR-903: an unticked calendar makes a successful write look like a failed one.
        if (!calendar.visible) {
            Text(
                stringResource(R.string.setup_destination_hidden_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TaskListRow(taskList: TaskList, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            taskList.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun Working(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Problem(message: String, onRetry: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.setup_retry)) }
        }
    }
}

/**
 * Google gives calendar colours as "#rrggbb". A colour that will not parse is cosmetic
 * only, so it falls back rather than failing the screen.
 */
private fun String.toComposeColour(): Color =
    runCatching { Color(toColorInt()) }.getOrDefault(Color.Gray)
