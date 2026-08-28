package com.latch.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.latch.android.R
import com.latch.android.capture.CapturedText
import com.latch.android.capture.DestinationState
import com.latch.android.capture.DraftBlocker
import com.latch.android.capture.SaveBlocker
import com.latch.android.capture.SaveFailure
import com.latch.android.capture.SaveState
import com.latch.android.capture.saveBlocker
import com.latch.android.capture.candidateBlocker
import com.latch.android.capture.saveIsOffered
import com.latch.android.capture.undoOffer
import com.latch.data.AccountDefaults
import com.latch.data.ItemDates
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
@OptIn(ExperimentalLayoutApi::class)
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
    /** FR-804: the user confirmed the move. */
    onUpdateExisting: () -> Unit = {},
    /** FR-804: the user wants a separate item instead. */
    onCreateNew: () -> Unit = {},
    /** FR-213: the tile path reached the clipboard and found nothing in it. */
    fromEmptyClipboard: Boolean = false,
    /** FR-511: the candidates still ticked, by index. All of them to begin with. */
    selected: Set<Int> = emptySet(),
    onToggleCandidate: (Int) -> Unit = {},
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
                CaptureBody(captured, result, selected, onToggleCandidate)

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

            // FlowRow, not Row: with an FR-804 offer up this holds three actions on a narrow
            // floating dialog. A Row gives the labels whatever is left and they clip, which is
            // silent — "Update" became "Up…" on a Pixel 6 Pro. This wraps to a second line
            // instead, so the failure mode for a longer translation is a taller dialog rather
            // than a word the user cannot read.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.Center,
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
                // FR-804. Two answers and no default: the requirement forbids a silent
                // update, and a preselected button in a dialog that can be dismissed by a
                // stray tap is how a silent one would happen.
                if (saveState is SaveState.RescheduleOffered) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCreateNew) {
                        ActionLabel(stringResource(R.string.capture_reschedule_create_new))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onUpdateExisting) {
                        ActionLabel(stringResource(R.string.capture_reschedule_update))
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
private fun CaptureBody(
    captured: CapturedText,
    result: ParseResult,
    selected: Set<Int>,
    onToggleCandidate: (Int) -> Unit,
) {
    val candidate = result.primary

    // FR-511: everything below that describes *one* candidate belongs to the header only
    // while there is one candidate. With a list, each row carries its own badge, date and
    // notes, and repeating the primary's above them says something false as often as not —
    // an EVENT badge over a list holding tasks, or FR-510's past-date note shown twice for
    // the same row. The title is the exception: it names the capture rather than any one
    // date in it, so it stands above the list.
    val single = result.candidates.size == 1

    if (single) {
        ItemTypeBadge(candidate)
    }

    Text(
        text = captured.preferredTitle ?: result.title.value,
        style = MaterialTheme.typography.titleMedium,
    )

    if (single) {
        Text(
            text = whenLine(candidate),
            style = MaterialTheme.typography.bodyLarge,
        )

        // FR-510: a past date never becomes a dated item; the user is offered a follow-up.
        // Per row otherwise — CandidateRow shows it for whichever rows are actually past.
        if (candidate.isPast) {
            Note(stringResource(R.string.capture_past_date))
        }
    }

    // FR-504: the resolved interpretation is shown before saving, never assumed. Header-level
    // only where there is one candidate; CandidateRow carries it per row otherwise.
    val ambiguousDate = candidate.date?.value
    if (single && candidate.ambiguousOrder && ambiguousDate != null) {
        Note(stringResource(R.string.capture_ambiguous_order, ambiguousDate.format(DATE_FORMAT)))
    }

    if (single && candidate.ambiguousRelative) {
        Note(stringResource(R.string.capture_ambiguous_relative))
    }

    if (candidate.date == null && candidate.time == null) {
        Note(stringResource(R.string.capture_undated_explanation))
    }

    // FR-511: every date the capture holds, each tickable, all ticked to begin with.
    if (result.candidates.size > 1) {
        Note(pluralStringResource(R.plurals.capture_dates_found, result.candidates.size, result.candidates.size))
        result.candidates.forEachIndexed { index, each ->
            CandidateRow(
                candidate = each,
                checked = index in selected,
                onToggle = { onToggleCandidate(index) },
            )
        }
    }

}

/**
 * FR-511: one date from a multi-date capture, with its own checkbox and its own
 * classification.
 *
 * The badge is per row rather than per capture because the rows genuinely differ: a range is
 * an Event while the line under it may be a Task with a due date, and a user ticking boxes
 * has to see which is which before they save.
 *
 * A row that cannot be written — FR-506 row 3, a time with no day — says so and cannot be
 * ticked. It does not disable the save: SRS 1.23 makes such a candidate block itself and not
 * its neighbours.
 */
@Composable
private fun CandidateRow(candidate: DatedCandidate, checked: Boolean, onToggle: () -> Unit) {
    val blocker = candidateBlocker(candidate)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked && blocker == null,
            onCheckedChange = { onToggle() },
            enabled = blocker == null,
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemTypeBadge(candidate)
                Spacer(Modifier.width(8.dp))
                Text(text = whenLine(candidate), style = MaterialTheme.typography.bodyMedium)
            }
            if (blocker == DraftBlocker.NEEDS_A_DATE) {
                Note(stringResource(R.string.capture_row_needs_date))
            }
            // FR-510, per row now rather than per capture: a past date is still saved as
            // read, and the follow-up that requirement describes is not built.
            if (candidate.isPast) {
                Note(stringResource(R.string.capture_past_date))
            }
            // FR-504, likewise per row. These used to be shown for the primary alone, which
            // in a multi-date capture meant an ambiguous date that was *not* the primary got
            // no interpretation shown at all — "Invoice dated 05/09 and review on 12
            // September at 3pm" ranks the timed date first and leaves 05/09 unexplained.
            // The requirement is that the resolved reading is displayed before saving, and a
            // row the user is about to tick is exactly where it has to appear.
            val ambiguousDate = candidate.date?.value
            if (candidate.ambiguousOrder && ambiguousDate != null) {
                Note(stringResource(R.string.capture_ambiguous_order, ambiguousDate.format(DATE_FORMAT)))
            }
            if (candidate.ambiguousRelative) {
                Note(stringResource(R.string.capture_ambiguous_relative))
            }
        }
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

        // FR-804. The weekdays here are computed from the dates themselves and never taken
        // from the captured text, which may name one that contradicts the date beside it —
        // §7.2 requires such a contradiction to be absorbed, not repeated back at the user.
        is SaveState.RescheduleOffered -> Note(
            stringResource(
                R.string.capture_reschedule_offer,
                state.title,
                describeDates(state.existing),
                describeDates(state.proposed),
            )
        )

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

/**
 * A button label that stays on one line.
 *
 * This row can hold four actions at once when an FR-804 offer is up — Close, Create new,
 * Update — and the capture sheet is a narrow floating dialog. Compose hands a `Text` whatever
 * width is left and lets it wrap, which on a Pixel 6 Pro broke "Update it" one syllable per
 * line rather than shrinking anything. `softWrap = false` makes the label ask for its full
 * width instead, so the row runs out of space before the word does.
 */
@Composable
private fun ActionLabel(text: String) {
    // softWrap = false stops the label breaking one syllable per line; the FlowRow around it
    // is what stops it being clipped instead. Ellipsis rather than Clip so that if a
    // translation ever does overflow both, it reads as truncated rather than as a shorter
    // word that happens to be wrong.
    Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

/**
 * An item's dates as a sentence fragment, for FR-804's offer.
 *
 * The weekday is **derived from the date**, which is the whole point: a capture may say
 * "Friday 12 September" of a Saturday, and §7.2 absorbs that contradiction into the date
 * rather than acting on it. Repeating the writer's weekday back here would show the user a
 * day name that does not match the day the item is actually on.
 */
@Composable
private fun describeDates(dates: ItemDates): String = when (dates) {
    is ItemDates.Event ->
        if (dates.allDay) dates.start.toLocalDate().format(OFFER_DATE)
        else dates.start.format(OFFER_DATE_TIME)

    is ItemDates.Task ->
        dates.due?.format(OFFER_DATE) ?: stringResource(R.string.capture_reschedule_no_date)
}

private val OFFER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val OFFER_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
