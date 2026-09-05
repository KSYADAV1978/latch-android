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
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import com.latch.android.cards.CardPersonWarning
import com.latch.android.cards.CardPhotoUpload
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

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
    /**
     * FR-1225: take another photograph into this same capture.
     *
     * **Null means the control is not offered at all**, which is every path but the camera's and,
     * within that, every card that came from a decoded grammar. A vCard payload is a complete
     * record and merging a second side's recognised lines into it would need exactly the conflict
     * policy FR-1225 exists to avoid.
     */
    onAddPhoto: (() -> Unit)? = null,
    /** FR-1225: a photograph is being recognised into this capture right now. */
    readingPhoto: Boolean = false,
    /**
     * FR-1226: the user may attach the card itself as the contact's photo.
     *
     * Null means the control is not offered — a card decoded from a QR grammar has no photograph
     * to attach. Offered, it is **off** every time: see `CardSheetState.attachPhoto`.
     */
    onAttachPhoto: ((Boolean) -> Unit)? = null,
    /** FR-1226 and FR-1212: there is no network, so a photograph would be dropped rather than sent. */
    photoWouldBeHeld: Boolean = false,
    /**
     * FR-1227: a contact carrying the same name and mobile is already in the account.
     *
     * **It never disables anything.** The user is holding the card and Latch is not; a line that
     * says what was found and leaves the decision alone is the whole of what this requirement
     * permits an inexact key to do.
     */
    personWarning: CardPersonWarning = CardPersonWarning.NONE,
) {
    val saving = saveResult is CardSaveResult.Saving
    val draft = state.edited
    val blocker = cardSaveBlocker(state, readingPhoto = readingPhoto)

    // **The sheet needs a background of its own** (SRS 1.122). `Theme.Latch.Capture` sets
    // `windowBackground` to transparent so the capture window is a floating popup over
    // whatever the user was reading; `CaptureScreen` has always drawn its own `Surface`
    // under that, and this one never did. It went unseen through the whole 4 Sep card pass
    // because every card arrived from a share sheet, and what showed through was a dimmed
    // gallery. B2 put Latch's own home screen behind it — high-contrast text at the same
    // size as the fields — and the sheet became unreadable.
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
    ) {
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

                // FR-1226. **The only control in this application that sends an image anywhere**,
                // which is why it is a tick the user sets for this card and not a setting they set
                // once — FR-216 was amended by name for it, and the image is somebody else's.
                if (onAttachPhoto != null) {
                    // **The whole row is the target, not the box.** Watched on a device: tapping
                    // the label did nothing, which is Material's default and is wrong on a
                    // floating sheet where every control is already tight — this project has
                    // already paid once for an action that was hard to hit here. `toggleable`
                    // also collapses the row into a single semantics node, which is what a screen
                    // reader should hear (NFR-401) rather than a box and a caption side by side.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = state.attachPhoto,
                                onValueChange = onAttachPhoto,
                                role = Role.Checkbox,
                            ),
                    ) {
                        // Null, so the row owns the click rather than competing with it — the
                        // Material pattern for a labelled checkbox.
                        Checkbox(checked = state.attachPhoto, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.card_attach_photo),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // Said **before** the save and not only after it. A user who ticks the box with
                    // no network would otherwise learn that the photograph was dropped by noticing
                    // its absence on the contact days later.
                    if (state.attachPhoto && photoWouldBeHeld) {
                        Note(stringResource(R.string.card_photo_not_held))
                    }
                }

                // FR-1227. Above the fields rather than beside Save, because it is something to
                // know *while reading the card back*, not a verdict on the act of saving — and
                // because a line that appears next to a button reads as a reason the button is
                // disabled, which this one must never be.
                if (personWarning == CardPersonWarning.PROBABLY_ALREADY_SAVED) {
                    Note(stringResource(R.string.card_probably_saved))
                }

                // FR-1225's report, on FR-207's pattern and for FR-207's reason: a photograph that
                // yielded nothing is invisible otherwise, and a user who adds a third side and
                // sees the sheet not change cannot tell that apart from a side with nothing on it.
                //
                // **Only when something went unread.** The cap is enforced by withdrawing the
                // control below, so a photograph is never taken and then discarded — which means
                // this line never has to say "and one of them was ignored".
                state.photos?.takeIf { it.capped }?.let { photos ->
                    Note(stringResource(R.string.card_photos_read, photos.read, photos.added))
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

            when (blocker) {
                CardSaveBlocker.NOTHING_TO_SAVE -> Note(stringResource(R.string.card_nothing_to_save))
                // FR-1225. Said rather than left as a disabled button: a Save the user cannot
                // press and cannot account for reads as the app having stopped working, and this
                // one clears itself in a second or two.
                CardSaveBlocker.READING_A_PHOTO -> Note(stringResource(R.string.card_reading_photo_note))
                null -> Unit
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
                // FR-1210 and NFR-303: an undo says what it did. The failed branch is the one
                // that matters — the contact is still in the account and this is the only place
                // the user is told.
                is CardSaveResult.Undone -> Note(
                    stringResource(
                        if (saveResult.removed) R.string.card_undone
                        else R.string.card_undo_failed
                    )
                )
                is CardSaveResult.AlreadySaved -> Note(stringResource(R.string.card_already_saved))
                is CardSaveResult.Failed -> Note(stringResource(R.string.card_save_failed))
                // FR-1212. Held is not Saved, and saying "saved" here would be a lie in the
                // reassuring direction: the account holds nothing yet.
                is CardSaveResult.Held -> {
                    Note(stringResource(R.string.card_held))
                    PhotoOutcome(saveResult.photo)
                }
                is CardSaveResult.Saved -> {
                    Note(
                        stringResource(
                            // A write made without an answer says so. SRS 5.8's rule reaching a
                            // screen: a scan that gave up must not be reported as a clean check.
                            if (saveResult.checked) R.string.card_saved else R.string.card_saved_unchecked
                        )
                    )
                    // FR-1226, **beside** the save and never instead of it. A photograph Google
                    // would not take is not a contact that failed to save, and saying so in the
                    // same breath as "Saved" is what keeps those two facts apart.
                    PhotoOutcome(saveResult.photo)
                }
                else -> Unit
            }

            // FlowRow for the reason the capture sheet uses one: a clipped action label is a silent
            // failure, and "Update" became "Up…" on a real device once already.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_dismiss)) }

                // FR-1225. Offered while the card is still the user's to change — so it goes with
                // Save rather than among the fields, and it disappears once a save has landed:
                // adding a side to a contact already written is a Phase C update, not this.
                if (onAddPhoto != null &&
                    (saveResult is CardSaveResult.Idle || saveResult is CardSaveResult.Failed)
                ) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onAddPhoto, enabled = !readingPhoto) {
                        ActionLabel(
                            stringResource(
                                if (readingPhoto) R.string.card_reading_photo
                                else R.string.card_add_photo
                            )
                        )
                    }
                }

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
}

/**
 * FR-1226's outcome, in one place so the saved and held branches cannot drift apart.
 *
 * **Silent where the user did not ask.** Three states exist and only two are worth a sentence:
 * telling somebody their card was not attached when they never asked for it to be is noise, and
 * noise on this sheet costs the lines that matter.
 */
@Composable
private fun PhotoOutcome(upload: CardPhotoUpload) {
    when (upload) {
        CardPhotoUpload.NOT_REQUESTED, CardPhotoUpload.ATTACHED -> Unit
        CardPhotoUpload.FAILED -> Note(stringResource(R.string.card_photo_failed))
        CardPhotoUpload.NOT_HELD -> Note(stringResource(R.string.card_photo_not_held))
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
    // **The sheet needs a background of its own** (SRS 1.122). `Theme.Latch.Capture` sets
    // `windowBackground` to transparent so the capture window is a floating popup over
    // whatever the user was reading; `CaptureScreen` has always drawn its own `Surface`
    // under that, and this one never did. It went unseen through the whole 4 Sep card pass
    // because every card arrived from a share sheet, and what showed through was a dimmed
    // gallery. B2 put Latch's own home screen behind it — high-contrast text at the same
    // size as the fields — and the sheet became unreadable.
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
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
