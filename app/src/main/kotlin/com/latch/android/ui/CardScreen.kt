package com.latch.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import java.time.Instant
import com.latch.android.cards.cardUndoSecondsLeft
import com.latch.android.cards.cardUndoOffered
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.latch.android.R
import com.latch.android.cards.CardEdits
import com.latch.android.cards.CardSaveBlocker
import com.latch.android.cards.CardSaveResult
import com.latch.android.cards.CardSheetState
import com.latch.android.cards.cardSaveBlocker

/**
 * FR-1205 and FR-1213: the card preview.
 *
 * **It draws and decides nothing.** What may be edited, what an edit means and whether a save is
 * allowed are all `CardSheet.kt`'s, which a JVM test can reach — this file cannot be tested at all
 * and the project has five defects on record that were exactly this boundary being crossed.
 *
 * **Every field is a text box, including the ones that parsed cleanly.** FR-1205 says every field
 * shall be editable, and a preview that makes the user tap an "edit" affordance first is a preview
 * that will be confirmed unread. A card is other people's data typed by a printer; the useful
 * default is that all of it is in reach.
 */
@Composable
fun CardScreen(
    state: CardSheetState,
    /** FR-1213: the account this will be created in, or null while it is still being read. */
    accountLabel: String?,
    onEdit: (CardEdits) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** FR-1206 and FR-1208: what the last save did, or Idle. */
    saveResult: CardSaveResult = CardSaveResult.Idle,
    /** FR-1210: when the save landed, so the ten seconds can be counted from it. */
    savedAt: Instant? = null,
    onUndo: () -> Unit = {},
    undoing: Boolean = false,
) {
    val saving = saveResult is CardSaveResult.Saving
    val draft = state.edited
    val blocker = cardSaveBlocker(state)

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_title),
            style = MaterialTheme.typography.titleMedium,
        )

        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // FR-1224, and it belongs **before the first box** rather than among them. It was
            // landing mid-list — between the phone boxes and the address — where it reads as a
            // caption for whichever field happens to sit above it (SRS 1.113).
            if (state.fromPhoto) {
                Note(stringResource(R.string.card_from_photo))
            }

            Field(R.string.card_field_name, draft.displayName) {
                onEdit(state.edits.copy(displayName = it))
            }
            Field(R.string.card_field_organisation, draft.organisation) {
                onEdit(state.edits.copy(organisation = it))
            }
            Field(R.string.card_field_title, draft.jobTitle) {
                onEdit(state.edits.copy(jobTitle = it))
            }

            // Indexed against the parsed list, so an edit survives another edit and a blanked
            // entry is a removal rather than an empty row on somebody's contact.
            state.parsed.phones.forEachIndexed { index, phone ->
                Field(R.string.card_field_phone, state.edits.phones[index] ?: phone.number) {
                    onEdit(state.edits.copy(phones = state.edits.phones + (index to it)))
                }
            }
            state.parsed.emails.forEachIndexed { index, email ->
                Field(R.string.card_field_email, state.edits.emails[index] ?: email.address) {
                    onEdit(state.edits.copy(emails = state.edits.emails + (index to it)))
                }
            }

            // FR-1205: an editable box, not a label. The classifier joins an address out of
            // several recognised lines and gets the joining wrong as readily as the reading, so
            // this is the field most likely to need correcting — and it was the one field with
            // nothing to correct it in (SRS 1.110).
            state.parsed.addresses.forEachIndexed { index, address ->
                Field(R.string.card_field_address, state.edits.addresses[index] ?: address) {
                    onEdit(state.edits.copy(addresses = state.edits.addresses + (index to it)))
                }
            }

            // FR-1223, and more than it asks (SRS 1.113). The requirement is that unplaced text
            // is *shown* and never silently discarded; showing it as a read-only list meant the
            // user had to retype anything worth keeping, and everything else was dropped at save.
            // The People API has a notes field, so it is carried there instead — shown, editable,
            // and kept unless the user clears it.
            Field(R.string.card_field_notes, draft.note) {
                onEdit(state.edits.copy(note = it))
            }
            if (state.unplaced.isNotEmpty()) {
                Note(stringResource(R.string.card_unplaced))
            }

            // FR-1213. Design principle 4 has no calendar to show here, so the account becomes
            // the destination — and it is stated before the save rather than after it.
            Note(
                accountLabel?.let { stringResource(R.string.card_destination, it) }
                    ?: stringResource(R.string.card_destination_loading)
            )

            // Said plainly, because the consent screen asks for permission to delete contacts and
            // this is the only place the user learns that Latch does not (SRS 1.88, FR-1102).
            Note(stringResource(R.string.card_never_deletes))
        }

        if (blocker == CardSaveBlocker.NOTHING_TO_SAVE) {
            Note(stringResource(R.string.card_nothing_to_save))
        }

        // FR-1210's clock, ticking only while there is something to count — the date sheet's own
        // shape. A timer left running behind a finished sheet is a wakeup a second for nothing.
        val counting = savedAt != null &&
            (saveResult is CardSaveResult.Saved || saveResult is CardSaveResult.Held)
        val now by produceState(Instant.now(), counting) {
            while (counting) {
                value = Instant.now()
                delay(250)
            }
        }
        val undoOffered = counting && cardUndoOffered(savedAt!!, now)

        // FR-1208's answer, said in the words the date side already uses for the same fact, so a
        // user who has seen "Already saved" on a capture reads this the same way.
        when (saveResult) {
            is CardSaveResult.AlreadySaved -> Note(stringResource(R.string.card_already_saved))
            is CardSaveResult.Failed -> Note(stringResource(R.string.card_save_failed))
            // FR-1212. Held is not Saved, and saying "saved" here would be a lie in the
            // reassuring direction: the account holds nothing yet.
            is CardSaveResult.Held -> Note(stringResource(R.string.card_held))
            is CardSaveResult.Saved ->
                Note(
                    stringResource(
                        // A write made without an answer says so. SRS 5.8's rule reaching a
                        // screen: a scan that gave up must not be reported as a clean check.
                        if (saveResult.checked) R.string.card_saved else R.string.card_saved_unchecked
                    )
                )
            else -> Unit
        }

        // FlowRow for the reason the capture sheet uses one: a clipped action label is a silent
        // failure, and "Update" became "Up…" on a real device once already.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_dismiss)) }

            // FR-1210. Counting down, because a ten-second offer with no number on it is one the
            // user cannot judge whether to reach for — and this project has already recorded the
            // window being missed twice while somebody checked Google first.
            if (undoOffered) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onUndo, enabled = !undoing) {
                    ActionLabel(
                        stringResource(
                            R.string.card_undo,
                            cardUndoSecondsLeft(savedAt!!, now),
                        )
                    )
                }
            }

            if (saveResult is CardSaveResult.Idle || saveResult is CardSaveResult.Saving ||
                saveResult is CardSaveResult.Failed
            ) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave, enabled = blocker == null && !saving && accountLabel != null) {
                    ActionLabel(
                        stringResource(if (saving) R.string.card_saving else R.string.card_save)
                    )
                }
            }
        }
    }
}

/**
 * FR-1203: an image carrying more than one code.
 *
 * **The app does not choose**, which is the requirement rather than caution — a poster with two
 * cards on it, or a card beside a Wi-Fi code, is a question only the person holding the phone can
 * answer. `soleContactPayload` returns null here and this is what that null looks like.
 */
@Composable
fun CardChooser(
    payloads: List<String>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.card_choose_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Note(stringResource(R.string.card_choose_body))
        payloads.forEachIndexed { index, payload ->
            TextButton(onClick = { onChoose(payload) }) {
                // Numbered rather than previewed: the payloads are somebody else's contact
                // details and putting two of them on screen to be told apart is a poor trade
                // for a choice between two codes.
                Text(stringResource(R.string.card_choose_option, index + 1))
            }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_dismiss)) }
    }
}

@Composable
private fun Field(labelRes: Int, value: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        // Not single-line: an address wraps, and a box that hid most of it would be a
        // field the user cannot check — which is the whole of what FR-1224 asks of them.
        singleLine = false,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
}
