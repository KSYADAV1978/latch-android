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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.latch.android.capture.DateSuggestion
import com.latch.android.capture.SaveRoute
import com.latch.android.capture.SaveState
import com.latch.android.capture.TypeChangeCost
import com.latch.android.capture.canOverrideTo
import com.latch.android.capture.dateFrom
import com.latch.android.capture.typeChangeCost
import com.latch.android.capture.saveBlocker
import com.latch.android.capture.candidateBlocker
import com.latch.android.capture.titleFor
import com.latch.android.capture.saveIsOffered
import com.latch.android.capture.undoOffer
import com.latch.data.AccountDefaults
import com.latch.data.InboxReason
import com.latch.data.ItemDates
import com.latch.core.model.ItemType
import com.latch.ocr.OcrFailure
import com.latch.ocr.PageProgress
import com.latch.parser.DatedCandidate
import com.latch.parser.ParseResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
    /** FR-512: shown, so a route to the Inbox is explained rather than merely happening. */
    lowConfidence: Boolean = false,
    /**
     * FR-512, FR-506 rows 3 and 4: where this capture will go.
     *
     * The button reads **Add to Inbox** rather than Save when it is not going to Google, which
     * is the difference between a routing decision the user made and a save that quietly went
     * somewhere else. FR-703 is the promise behind it: nothing reaches the account until it is
     * confirmed.
     */
    route: SaveRoute = SaveRoute.Google,
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
    /**
     * FR-506 row 3: the user gave this row a day. The index is into `result.candidates`.
     */
    onAssignDate: (Int, LocalDate) -> Unit = { _, _ -> },
    /** FR-507: the user tapped the badge. The index is into `result.candidates`. */
    onOverrideType: (Int, ItemType) -> Unit = { _, _ -> },
    /**
     * Today, for FR-506 row 3's suggestion chips. Passed in rather than read here so the sheet
     * stays a function of its inputs and the chips are testable — the same reason
     * `ParseContext` takes `now` rather than reading a clock (FR-515).
     */
    today: LocalDate = LocalDate.now(),
    /** NFR-102: an image or PDF is still being recognised, and this screen draws anyway. */
    extracting: Boolean = false,
    /**
     * FR-207: how far through a document the reader has got, where this capture is one.
     *
     * Null for an image and for the moment before the first page starts. NFR-101a puts a
     * ten-page document at about five seconds, which is long enough that a spinner saying
     * only that something is happening stops being enough to say.
     */
    extractingPages: PageProgress? = null,
    /** FR-215: recognition finished and produced nothing usable. */
    ocrFailure: OcrFailure? = null,
) {
    val blocker =
        if (captured == null) SaveBlocker.NEEDS_A_DATE
        else saveBlocker(destination, result, saveState, route)

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
            if (extracting) {
                // NFR-102: the screen is up and says what it is doing, rather than the user
                // looking at nothing for up to NFR-101's 2.5 seconds and wondering whether
                // the share worked at all.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = extractingPages?.let {
                            stringResource(R.string.capture_extracting_page, it.page, it.total)
                        } ?: stringResource(R.string.capture_extracting),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else if (captured == null || result == null) {
                Text(
                    text = when {
                        // FR-215's three outcomes are told apart on screen, because they ask
                        // different things of the user: share it again, share something with
                        // text in it, or nothing at all.
                        ocrFailure == OcrFailure.UNREADABLE_SOURCE ->
                            stringResource(R.string.capture_ocr_unreadable)
                        ocrFailure == OcrFailure.NO_TEXT_FOUND ->
                            stringResource(R.string.capture_ocr_no_text)
                        ocrFailure == OcrFailure.RECOGNITION_FAILED ->
                            stringResource(R.string.capture_ocr_failed)
                        fromEmptyClipboard -> stringResource(R.string.capture_clipboard_empty)
                        else -> stringResource(R.string.capture_nothing_shared)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                CaptureBody(captured, result, selected, onToggleCandidate, onAssignDate, onOverrideType, today)

                // FR-207: the cap is reported, never applied silently. Only where it bit —
                // "first 10 of 10 pages read" is a message about nothing, and a user who
                // reads it will reasonably think a page was dropped.
                captured.pages?.takeIf { it.capped }?.let { pages ->
                    Note(stringResource(R.string.capture_pdf_capped, pages.read, pages.total))
                }

                // FR-904: the destination is on screen before the user confirms, which is
                // also what FR-906 means by never routing somewhere they have not seen. Not
                // shown for an Inbox route: nothing is going to a calendar, and naming one
                // would say the opposite of what is about to happen.
                if (destination is DestinationState.Ready && route is SaveRoute.Google) {
                    DestinationChip(destination.defaults)
                }

                if (lowConfidence && route is SaveRoute.Google) {
                    Note(stringResource(R.string.capture_low_confidence))
                }

                // FR-512, FR-506 rows 3 and 4, AC-03. Says why this is not being written, on
                // the screen where the user is deciding — rather than leaving them to discover
                // that a Save button put something somewhere else.
                if (route is SaveRoute.Inbox) {
                    Note(stringResource(inboxReasonText(route.reason)))
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
                        ActionLabel(
                            stringResource(
                                when {
                                    saveState is SaveState.Saving -> R.string.capture_saving
                                    route is SaveRoute.Inbox -> R.string.capture_add_to_inbox
                                    else -> R.string.capture_save
                                }
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
    onAssignDate: (Int, LocalDate) -> Unit,
    onOverrideType: (Int, ItemType) -> Unit,
    today: LocalDate,
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
        // FR-507: the badge is the control. FR-508 already requires it to be visible at all
        // times, and putting the override on it satisfies "a single control" literally — the
        // classification and the way to change it are the same thing on screen.
        ItemTypeBadge(candidate, onOverride = { onOverrideType(0, it) })
    }

    // FR-509a moved the title of an OCR capture onto the row carrying each date, so the
    // header can no longer speak for the whole capture: showing one title here while writing
    // a different one per item would be a confirmation screen that does not confirm what is
    // saved. The same function decides both, so the screen and the write cannot disagree.
    val perRowTitles = captured.ocrUsed && captured.preferredTitle.isNullOrBlank()
    if (!perRowTitles || single) {
        Text(
            text = titleFor(captured, result, candidate),
            style = MaterialTheme.typography.titleMedium,
        )
    }

    if (single) {
        Text(
            text = whenLine(candidate),
            style = MaterialTheme.typography.bodyLarge,
        )

        // FR-510: a past date never becomes a dated item; a follow-up is written instead.
        // Per row otherwise — CandidateRow shows it for whichever rows are actually past.
        PastDateNote(candidate)

        // FR-507's disclosure, and FR-506 row 3's picker, for the single-candidate case.
        OverrideCost(candidate)
        DayPicker(candidate, today) { onAssignDate(0, it) }
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
                title = if (perRowTitles) titleFor(captured, result, each) else null,
                checked = index in selected,
                onToggle = { onToggleCandidate(index) },
                onAssignDate = { onAssignDate(index, it) },
                onOverrideType = { onOverrideType(index, it) },
                today = today,
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
private fun CandidateRow(
    candidate: DatedCandidate,
    /** FR-509a: this row's own title, where an OCR capture gives each date its own. */
    title: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    onAssignDate: (LocalDate) -> Unit,
    onOverrideType: (ItemType) -> Unit,
    today: LocalDate,
) {
    val blocker = candidateBlocker(candidate)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked && blocker == null,
            onCheckedChange = { onToggle() },
            enabled = blocker == null,
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // FR-507, per row. FR-508 requires a badge on every item in a chain, so the
                // override follows it there rather than being a separate control somewhere else.
                ItemTypeBadge(candidate, onOverride = onOverrideType)
                Spacer(Modifier.width(8.dp))
                Text(text = whenLine(candidate), style = MaterialTheme.typography.bodyMedium)
            }
            // FR-509a. Shown here rather than above the list because for an OCR capture the
            // title belongs to the date's own row, and this is what will be written.
            title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (blocker == DraftBlocker.NEEDS_A_DATE) {
                Note(stringResource(R.string.capture_row_needs_date))
            }
            // FR-506 row 3: the picker, on the row that needs it, already unfolded.
            DayPicker(candidate, today, onAssignDate)
            // FR-510, per row: a past date is written as an undated follow-up, never as a
            // dated item, and the note says which.
            PastDateNote(candidate)
            // FR-507's cost, said before the tap rather than after it.
            OverrideCost(candidate)
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

        // FR-703: local only. Deliberately not phrased as a save, for the reason Queued is
        // not — the item is on this phone and not in the user's account.
        is SaveState.SentToInbox -> Note(stringResource(R.string.capture_sent_to_inbox))

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
private fun ItemTypeBadge(
    candidate: DatedCandidate,
    /**
     * FR-507: the badge is the override. Null where there is nothing to change — a past row,
     * which FR-510 forbids being an event.
     */
    onOverride: ((ItemType) -> Unit)? = null,
) {
    val current = candidate.classification.itemType
    val other = if (current == ItemType.EVENT) ItemType.TASK else ItemType.EVENT
    val changeable = onOverride != null && canOverrideTo(candidate, other)

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
            .let { if (changeable) it.clickable { onOverride?.invoke(other) } else it }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * FR-510, on the row it applies to.
 *
 * It says what **will** happen rather than warning about what will not: the row still saves, as
 * an undated follow-up carrying the past date in its notes, so a sentence beginning "cannot"
 * would be simply false. It also says why the badge on this row is fixed.
 */
@Composable
private fun PastDateNote(candidate: DatedCandidate) {
    val date = candidate.date?.value ?: return
    if (!candidate.isPast) return
    Note(stringResource(R.string.capture_past_date, date.format(DATE_FORMAT)))
    if (candidate.classification.itemType == ItemType.TASK) {
        Note(stringResource(R.string.capture_past_no_event))
    }
}

/**
 * FR-507's cost, disclosed **before** the tap.
 *
 * §8.1 is the reason there is one: the Tasks API records only a date, so an event with a time
 * loses it and a range loses its closing day. This is the one place in the app where a user
 * action deliberately discards something they wrote, and doing that quietly would be the
 * silent behaviour NFR-303 forbids of a failure, applied to a choice.
 */
@Composable
private fun OverrideCost(candidate: DatedCandidate) {
    if (candidate.classification.itemType != ItemType.EVENT) return
    if (candidate.isPast) return
    val cost = typeChangeCost(candidate, ItemType.TASK) ?: return
    Note(
        stringResource(
            when (cost) {
                TypeChangeCost.LOSES_TIME -> R.string.capture_override_loses_time
                TypeChangeCost.LOSES_END_DATE -> R.string.capture_override_loses_end
                TypeChangeCost.LOSES_TIME_AND_END_DATE -> R.string.capture_override_loses_time_and_end
            }
        )
    )
}

/**
 * FR-506 row 3: "Date picker opens automatically with suggestion chips".
 *
 * **Inline and already unfolded, not a modal.** The chips are on screen the moment the row is —
 * no tap to reach them, which is what "opens automatically" asks for — and the full calendar is
 * one tap behind `Other date…`. A modal calendar appearing unbidden over a floating capture
 * sheet would cover the very text the user is being asked to confirm, which is the opposite of
 * what a confirmation screen is for.
 *
 * Nothing is pre-selected. Design principle 1 forbids the *app* choosing a date; a chip the
 * user taps is the user choosing one, and the row stays unticked until they do.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(
    candidate: DatedCandidate,
    today: LocalDate,
    onAssignDate: (LocalDate) -> Unit,
) {
    if (candidate.date != null) return
    if (candidate.classification.itemType != ItemType.EVENT) return

    var pickingDate by remember { mutableStateOf(false) }

    Note(stringResource(R.string.capture_pick_a_day))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateSuggestion.entries.forEach { suggestion ->
            AssistChip(
                onClick = { onAssignDate(suggestion.dateFrom(today)) },
                label = {
                    Text(
                        stringResource(
                            when (suggestion) {
                                DateSuggestion.TODAY -> R.string.capture_chip_today
                                DateSuggestion.TOMORROW -> R.string.capture_chip_tomorrow
                                DateSuggestion.IN_A_WEEK -> R.string.capture_chip_in_a_week
                            }
                        )
                    )
                },
            )
        }
        AssistChip(
            onClick = { pickingDate = true },
            label = { Text(stringResource(R.string.capture_chip_other)) },
        )
    }

    if (pickingDate) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    // Disabled rather than defaulting to today: a confirm button that quietly
                    // meant "today" would be the app choosing a date with a tap in front of it.
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let {
                            pickingDate = false
                            // The component's millis are UTC midnight by contract, not a zone
                            // the user is in. Converting through the device zone here is the
                            // off-by-one-day bug this comment exists to prevent.
                            onAssignDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                    },
                ) { Text(stringResource(R.string.capture_date_picker_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) {
                    Text(stringResource(R.string.capture_date_picker_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
internal fun Note(text: String) {
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

/**
 * FR-512 and FR-506's rows, as the sentence the user reads before they choose.
 *
 * NFR-402 keeps the wording here rather than in `:data`, which is why [InboxReason] is an enum
 * of named facts and not of phrases — the same division `SaveFailure`, `OcrFailure` and
 * `ShiftResult` all keep.
 */
internal fun inboxReasonText(reason: InboxReason): Int = when (reason) {
    InboxReason.UNDATED -> R.string.capture_route_undated
    InboxReason.LOW_CONFIDENCE -> R.string.capture_route_low_confidence
    InboxReason.INCOMPLETE -> R.string.capture_route_incomplete
    InboxReason.RESCHEDULE_UNRESOLVED -> R.string.capture_route_reschedule_offline
}

private val OFFER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val OFFER_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
