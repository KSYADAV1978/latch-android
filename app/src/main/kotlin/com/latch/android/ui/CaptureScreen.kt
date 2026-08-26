package com.latch.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.latch.android.R
import com.latch.android.capture.CapturedText
import com.latch.core.model.ItemType
import com.latch.parser.DatedCandidate
import com.latch.parser.ParseResult
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * The confirmation UI, as far as it goes today: what was captured, what the parser made of
 * it, and which way FR-506 classified it.
 *
 * Still to come, and deliberately absent rather than faked: the destination calendar chip
 * (FR-904), the Event/Task override (FR-507), per-date checkboxes for multiple dates
 * (FR-511) and the save itself (FR-801).
 */
@Composable
fun CaptureScreen(
    captured: CapturedText?,
    result: ParseResult?,
    onDismiss: () -> Unit,
    /** FR-213: the tile path reached the clipboard and found nothing in it. */
    fromEmptyClipboard: Boolean = false,
) {
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
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.capture_dismiss))
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

    Note(stringResource(R.string.capture_save_unavailable))
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

