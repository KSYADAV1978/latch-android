package com.latch.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.latch.android.R
import com.latch.android.capture.SaveFailure
import com.latch.android.capture.canOverrideTo
import com.latch.android.capture.SaveState
import com.latch.android.inbox.parseOf
import com.latch.data.InboxCapture
import com.latch.data.INBOX_REVIEW_AFTER
import com.latch.core.model.ItemType
import com.latch.parser.ParseResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val ROW_DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val ROW_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * FR-701 to FR-705: the Capture Inbox.
 *
 * **Everything on this screen is local** (FR-703) and the screen says so, once, at the top. It
 * is the difference between a list of things Latch has done and a list of things it is waiting
 * to be told to do, and a user who mistook the first for the second would think their calendar
 * had entries it does not.
 *
 * FR-702's five actions are on every row, in the requirement's own order. Nothing here nags
 * (FR-704): no badge, no colour, no notification — a count on the home screen and this list.
 */
@Composable
fun InboxScreen(
    rows: List<InboxCapture>,
    onBack: () -> Unit,
    onAssignDate: (String, LocalDate) -> Unit,
    onEditTitle: (String, String) -> Unit,
    /** FR-507: the override is available wherever a save is, and one is available here. */
    onOverrideType: (String, ItemType) -> Unit,
    onSave: (String) -> Unit,
    onSnooze: (String) -> Unit,
    onDiscard: (String) -> Unit,
    /**
     * NFR-303: a save started here reaches Google and its outcome has to be said out loud. The
     * saver is shared with the capture sheet, so this is the same state that screen renders.
     */
    saveState: SaveState = SaveState.Idle,
    now: Instant = Instant.now(),
) {
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
            Text(
                text = stringResource(R.string.inbox_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.inbox_back)) }
        }

        // FR-703, said plainly and only once. A per-row reassurance would be nagging.
        Note(stringResource(R.string.inbox_local_only))

        // NFR-303. A row that failed to save is still here, which is the reassuring half and
        // the reason the message says so rather than only naming the failure.
        when (saveState) {
            is SaveState.Saving -> Note(stringResource(R.string.inbox_saving))
            is SaveState.Failed ->
                if (saveState.reason == SaveFailure.WRITE_FAILED) {
                    Note(stringResource(R.string.inbox_save_failed))
                } else Unit
            else -> Unit
        }

        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.inbox_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(rows, key = { it.id }) { capture ->
                InboxRow(
                    capture = capture,
                    now = now,
                    onAssignDate = onAssignDate,
                    onEditTitle = onEditTitle,
                    onOverrideType = onOverrideType,
                    onSave = onSave,
                    onSnooze = onSnooze,
                    onDiscard = onDiscard,
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxRow(
    capture: InboxCapture,
    now: Instant,
    onAssignDate: (String, LocalDate) -> Unit,
    onEditTitle: (String, String) -> Unit,
    onOverrideType: (String, ItemType) -> Unit,
    onSave: (String) -> Unit,
    onSnooze: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    // Re-parsed against the capture's own instant and zone, never today's — see
    // `parseContextOf`. Remembered on the row so the FR-807 countdown elsewhere on screen does
    // not re-parse the whole list every 250 ms.
    val parsed = remember(capture.id, capture.assignedDate, capture.typeOverride) { parseOf(capture) }
    var editing by remember(capture.id) { mutableStateOf(capture.editedTitle ?: "") }
    var pickingDate by remember(capture.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(parsed) { onOverrideType(capture.id, it) }
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = whenLineOf(parsed, capture.assignedDate),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = capture.editedTitle?.takeIf { it.isNotBlank() } ?: parsed.title.value,
            style = MaterialTheme.typography.titleSmall,
        )

        // FR-512: why this is here rather than in the account.
        Note(stringResource(inboxReasonText(capture.reason)))

        // FR-705: surfaced for review, never deleted. The wording asks rather than warns —
        // an old capture is not a problem, it is a question the user has not answered.
        if (!capture.capturedAt.plus(INBOX_REVIEW_AFTER).isAfter(now)) {
            Note(stringResource(R.string.inbox_aged, capture.capturedAt.formatLocalDate()))
        }

        // FR-702's edit. A text field rather than a dialog, because the title is the one field
        // a user is likely to want to change on nearly every row.
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = editing,
            onValueChange = {
                editing = it
                onEditTitle(capture.id, it)
            },
            label = { Text(stringResource(R.string.inbox_edit_title)) },
            singleLine = true,
        )

        // FlowRow, not Row: five actions on a phone width wrap rather than clip, which is the
        // same reason the capture sheet's action row is one.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { pickingDate = true }) {
                Text(stringResource(R.string.inbox_set_date))
            }
            TextButton(onClick = { onSnooze(capture.id) }) {
                Text(stringResource(R.string.inbox_snooze))
            }
            TextButton(onClick = { onDiscard(capture.id) }) {
                Text(stringResource(R.string.inbox_discard))
            }
            Button(onClick = { onSave(capture.id) }) {
                Text(stringResource(R.string.inbox_save))
            }
        }
    }

    if (pickingDate) {
        AssignDateDialog(
            initial = parsed.primary.date?.value ?: capture.assignedDate,
            onDismiss = { pickingDate = false },
            onPicked = { date ->
                pickingDate = false
                onAssignDate(capture.id, date)
            },
        )
    }
}

/**
 * FR-702's "assign a date", and the picker FR-506 row 3 will also need.
 *
 * Material 3's own, so it costs no dependency (NFR-501) and inherits the system's locale, first
 * day of week and accessibility behaviour rather than reimplementing them badly.
 *
 * **Its millis are UTC midnight**, which is the component's documented contract and not a
 * timezone the user is in. Converting through `ZoneOffset.UTC` is therefore correct and using
 * the device zone would be the off-by-one-day bug this comment exists to prevent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignDateDialog(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                // Disabled rather than defaulting to today: design principle 1 forbids the app
                // choosing a date, and a confirm button that quietly meant "today" would be
                // exactly that with a tap in front of it.
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let {
                        onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) {
                Text(stringResource(R.string.inbox_date_picker_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.inbox_date_picker_cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}

/**
 * FR-508: the badge is visible at all times, here as much as on the confirmation sheet — and
 * FR-507: it is the control, for the same reason it is there.
 */
@Composable
private fun TypeBadge(parsed: ParseResult, onOverride: (ItemType) -> Unit) {
    val current = parsed.primary.classification.itemType
    val other = if (current == ItemType.EVENT) ItemType.TASK else ItemType.EVENT
    val changeable = canOverrideTo(parsed.primary, other)
    val label = when (current) {
        ItemType.EVENT -> R.string.badge_event
        ItemType.TASK -> R.string.badge_task
    }
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            )
            .let { if (changeable) it.clickable { onOverride(other) } else it }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun whenLineOf(parsed: ParseResult, assigned: LocalDate?): String {
    val candidate = parsed.primary
    val date = (candidate.date?.value ?: assigned)?.format(ROW_DATE)
    val time = candidate.time?.value?.format(ROW_TIME)
    return when {
        date != null && time != null -> "$date, $time"
        date != null -> date
        time != null -> time
        else -> stringResource(R.string.inbox_no_date_yet)
    }
}

private fun Instant.formatLocalDate(): String =
    atZone(ZoneId.systemDefault()).toLocalDate().format(ROW_DATE)
