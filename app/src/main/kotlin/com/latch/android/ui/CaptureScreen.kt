package com.latch.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.latch.android.R
import com.latch.android.capture.CapturedText
import com.latch.android.capture.DestinationState
import com.latch.android.capture.SaveBlocker
import com.latch.android.capture.SaveFailure
import com.latch.android.capture.SaveState
import com.latch.android.capture.saveBlocker
import com.latch.android.capture.saveIsOffered
import com.latch.android.capture.undoOffer
import com.latch.data.AccountDefaults
import com.latch.core.model.ItemType
import com.latch.parser.DatedCandidate
import com.latch.parser.ParseResult
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * How often FR-807's countdown re-reads the clock. Under a second so the number never
 * appears to skip one; the offer itself is timed by the saver, not by this.
 */
private const val UNDO_TICK_MS = 250L

/**
 * The confirmation UI: what was captured, what the parser made of it, which way FR-506
 * classified it, where it will go (FR-904) and the save itself (FR-801).
 *
 * Still to come, and deliberately absent rather than faked: the Event/Task override
 * (FR-507), per-date checkboxes for multiple dates (FR-511) and FR-506 row 3's date picker.
 */
@Composable
fun CaptureScreen(
    captured: CapturedText?,
    result: ParseResult?,
    onDismiss: () -> Unit,
    /** FR-904: where this will go, and whether that is known yet. */
    destination: DestinationState = DestinationState.Loading,
    saveState: SaveState = SaveState.Idle,
    /** FR-512, interim: shown rather than blocking the save until the Inbox exists. */
    lowConfidence: Boolean = false,
    onSave: () -> Unit = {},
    /** FR-807: take back everything this save wrote. */
    onUndo: () -> Unit = {},
    /** FR-213: the tile path reached the clipboard and found nothing in it. */
    fromEmptyClipboard: Boolean = false,
) {
    val blocker = if (captured == null) SaveBlocker.NEEDS_A_DATE else saveBlocker(destination, result, saveState)

    // FR-807's countdown. The saver decides when the offer actually ends — this only reads
    // the clock often enough for the number beside Undo to look like it is running out, and
    // stops entirely when there is no window, so an idle screen costs nothing.
    val hasWindow = when (saveState) {
        is SaveState.Saved -> saveState.undo != null
        is SaveState.Queued -> saveState.undo != null
        else -> false
    }
    val now by produceState(Instant.now(), hasWindow) {
        while (hasWindow) {
            value = Instant.now()
            delay(UNDO_TICK_MS)
        }
    }
    val undo = undoOffer(saveState, now)

    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (captured == null || result == null) {
                Text(
                    text = if (fromEmptyClipboard) {
                        stringResource(R.string.capture_clipboard_empty)
                    } else {
                        stringResource(R.string.capture_nothing_shared)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                CaptureBody(captured, result)

                // FR-904: the destination is on screen before the user confirms, which is
                // also what FR-906 means by never routing somewhere they have not seen.
                if (destination is DestinationState.Ready) {
                    DestinationChip(destination.defaults)
                }

                if (lowConfidence) {
                    Note(stringResource(R.string.capture_low_confidence))
                }

                // Every reason Save is unavailable says so. A disabled button with nothing
                // beside it is the one outcome this screen must never produce.
                when (blocker) {
                    SaveBlocker.NO_DESTINATION -> Note(stringResource(R.string.capture_save_no_destination))
                    SaveBlocker.NEEDS_A_DATE -> Note(stringResource(R.string.capture_save_needs_date))
                    // Momentary, or already reported by SaveOutcome below.
                    SaveBlocker.READING_DESTINATION, SaveBlocker.NOT_IDLE, null -> Unit
                }
            }

            SaveOutcome(saveState, destination)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.capture_dismiss))
                }
                if (saveIsOffered(saveState)) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSave, enabled = blocker == null) {
                        Text(
                            stringResource(
                                if (saveState is SaveState.Saving) R.string.capture_saving
                                else R.string.capture_save
                            )
                        )
                    }
                }
                // FR-807. Takes the Save button's place rather than sitting beside it: the
                // save has happened, and the only action left on this capture is undoing it.
                if (undo != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onUndo) {
                        Text(stringResource(R.string.capture_undo, undo.secondsRemaining(now)))
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureBody(captured: CapturedText, result: ParseResult) {
    val candidate = result.primary

    ItemTypeBadge(candidate)

    Text(
        text = captured.preferredTitle ?: result.title.value,
        style = MaterialTheme.typography.titleMedium,
    )

    Text(
        text = whenLine(candidate),
        style = MaterialTheme.typography.bodyLarge,
    )

    // FR-510: a past date never becomes a dated item; the user is offered a follow-up.
    if (candidate.isPast) {
        Note(stringResource(R.string.capture_past_date))
    }

    // FR-504: the resolved interpretation is shown before saving, never assumed.
    val ambiguousDate = candidate.date?.value
    if (candidate.ambiguousOrder && ambiguousDate != null) {
        Note(stringResource(R.string.capture_ambiguous_order, ambiguousDate.format(DATE_FORMAT)))
    }

    if (candidate.ambiguousRelative) {
        Note(stringResource(R.string.capture_ambiguous_relative))
    }

    if (candidate.date == null && candidate.time == null) {
        Note(stringResource(R.string.capture_undated_explanation))
    }

    // FR-511: the remaining dates become a multi-select list once that UI exists.
    val extraDates = result.candidates.size - 1
    if (extraDates > 0) {
        Note(pluralStringResource(R.plurals.capture_extra_dates, extraDates, extraDates))
    }

}

/**
 * FR-904: the destination as a chip carrying the calendar's own colour. Drawn from stored
 * defaults, never a lookup — NFR-101 gives the whole capture path 800 ms and this must be on
 * screen before the user can confirm.
 */
@Composable
private fun DestinationChip(destination: AccountDefaults) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CalendarSwatch(destination.destinationCalendarColour)
        Text(
            text = destination.destinationCalendarName,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** NFR-303: every outcome says what happened, and a failure says what was not saved. */
@Composable
private fun SaveOutcome(state: SaveState, destination: DestinationState) {
    when (state) {
        SaveState.Idle, SaveState.Saving -> Unit

        is SaveState.Saved -> Note(
            if (state.searchWasCapped) {
                stringResource(R.string.capture_saved_unchecked)
            } else {
                stringResource(
                    R.string.capture_saved,
                    (destination as? DestinationState.Ready)?.defaults?.destinationCalendarName.orEmpty(),
                )
            }
        )

        // FR-806. Not phrased as a save: the item is on this phone and not in the account.
        is SaveState.Queued -> Note(
            stringResource(
                R.string.capture_queued,
                (destination as? DestinationState.Ready)?.defaults?.destinationCalendarName.orEmpty(),
            )
        )

        SaveState.AlreadySaved -> Note(stringResource(R.string.capture_already_saved))

        is SaveState.Failed -> Note(
            stringResource(
                when (state.reason) {
                    SaveFailure.NO_DESTINATION -> R.string.capture_save_no_destination
                    SaveFailure.NEEDS_A_DATE -> R.string.capture_save_needs_date
                    SaveFailure.WRITE_FAILED -> R.string.capture_save_error
                }
            )
        )

        SaveState.Undoing -> Note(stringResource(R.string.capture_undoing))

        SaveState.Undone -> Note(stringResource(R.string.capture_undone))

        // A chain that went part way is a different sentence from one that did not move at
        // all: the first tells the user how many items they still have to go and remove.
        is SaveState.UndoFailed -> Note(
            if (state.removed == 0) {
                stringResource(R.string.capture_undo_failed)
            } else {
                stringResource(R.string.capture_undo_failed_partial, state.removed, state.total)
            }
        )
    }
}

/**
 * FR-508: the item type is visible as a badge at all times in the confirmation UI, so the
 * user always knows whether this is going to Calendar or to Tasks.
 */
@Composable
private fun ItemTypeBadge(candidate: DatedCandidate) {
    val label = when (candidate.classification.itemType) {
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun whenLine(candidate: DatedCandidate): String {
    val date = candidate.date?.value?.format(DATE_FORMAT)
    val time = candidate.time?.value?.format(TIME_FORMAT)
    return when {
        date != null && time != null -> "$date, $time"
        date != null -> date
        time != null -> time
        else -> stringResource(R.string.capture_no_date)
    }
}

