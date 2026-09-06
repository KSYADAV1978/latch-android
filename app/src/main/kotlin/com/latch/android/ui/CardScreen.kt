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
import com.latch.android.cards.CardReading
import com.latch.google.ContactField
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import com.latch.android.cards.CardPreview
import androidx.compose.ui.layout.ContentScale

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
     * FR-1229: every photograph as the recogniser saw it, turned as the reader turned it.
     *
     * **It verifies rather than reassures**, which is the whole reason these are the read images
     * and not the files. A camera's own confirm screen shows what the camera captured; this shows
     * what Latch read, so a card that arrived upside down, cropped short or further away than it
     * was framed says so *before* the fields below are trusted.
     *
     * **One per side, in the order taken** (SRS 1.145). Showing only the first was worse than
     * showing none: a second photograph left the thumbnail unchanged, so the control that exists
     * to say what was read reported on a picture that was no longer the one just taken.
     */
    previews: List<CardPreview> = emptyList(),
    /**
     * FR-1227: a contact carrying the same name and mobile is already in the account.
     *
     * **It never disables anything.** The user is holding the card and Latch is not; a line that
     * says what was found and leaves the decision alone is the whole of what this requirement
     * permits an inexact key to do.
     */
    personWarning: CardPersonWarning = CardPersonWarning.NONE,
    /**
     * FR-1231: accept the offer and change the contact the scan found.
     *
     * **There is no default and Save is withdrawn while the offer stands**, which is FR-804's
     * reading inherited rather than re-decided: this sheet is a floating window a stray tap can
     * dismiss, and a preselected button on it is how a silent change to somebody's address book
     * would happen.
     */
    onUpdateContact: () -> Unit = {},
    /** FR-1231 declined: this is somebody else, or a second record the user wants. */
    onCreateAnyway: () -> Unit = {},
    /**
     * FR-1205 on the update path (SRS 1.159): which rows are ticked, and what they now say.
     *
     * Held by the caller rather than by this composable for `SheetEdits`' reason: the sheet and
     * the write must read one value, or a screen that decided for itself would eventually show
     * one thing and send another.
     */
    offerAccepted: Set<Int> = emptySet(),
    offerValues: Map<Int, String> = emptyMap(),
    onOfferTick: (Int, Boolean) -> Unit = { _, _ -> },
    onOfferEdit: (Int, String) -> Unit = { _, _ -> },
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
                // FR-1229. **First, above everything**, because it answers a question that comes
                // before every field: is this even the picture I took? A preview below the fields
                // would be checked after they had already been believed.
                if (previews.isNotEmpty()) {
                    // **The block has a budget, not the thumbnail** (SRS 1.146). FR-1225 allows
                    // four photographs, and four at a comfortable height is more than a phone
                    // sheet has: the fields the preview exists to be checked *against* would sit
                    // entirely below the fold, and a preview nobody scrolls past is the control
                    // switched off. Dividing a fixed budget keeps one and two — the sizes that
                    // were watched on a device — exactly as they were, and pays for the third and
                    // fourth out of their own share rather than out of the sheet.
                    val previewHeight = (PREVIEW_BUDGET / previews.size).coerceAtMost(160.dp)
                    previews.forEach { shot ->
                        // **Numbered only where there is something to tell apart.** On a
                        // one-sided card a label saying "Side 1" invents a distinction the user
                        // has not made; on a two-sided one its absence is the confusion this
                        // whole change is answering.
                        if (previews.size > 1) {
                            Note(stringResource(R.string.card_preview_side, shot.side))
                        }
                        Image(
                            bitmap = shot.image.asImageBitmap(),
                            contentDescription = stringResource(
                                R.string.card_preview_description, shot.side,
                            ),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = previewHeight)
                                .clip(MaterialTheme.shapes.medium),
                        )
                    }
                    Note(stringResource(R.string.card_preview_caption))
                }

                // FR-1224, and it belongs **before the first box** rather than among them. It was
                // landing mid-list — between the phone boxes and the address — where it reads as a
                // caption for whichever field happens to sit above it (SRS 1.113).
                // FR-1230 shares the warning and not the sentence: a text selection is still
                // open in the application it came from, so this one says where to check rather
                // than that there is nowhere left to.
                when (state.readBy) {
                    CardReading.PHOTO -> Note(stringResource(R.string.card_from_photo))
                    CardReading.TEXT -> Note(stringResource(R.string.card_from_text))
                    // FR-1204's grammar is right or wrong. Nothing was guessed, so nothing is
                    // asked of the user beyond FR-1205's ordinary check.
                    CardReading.GRAMMAR -> Unit
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
                            // **It names the photograph once there is more than one** (SRS 1.146).
                            // "this photo" was unambiguous while the sheet showed one thumbnail
                            // and is not now: the user is looking at two or four, and the answer
                            // — the first one Latch could read — is not something they can work
                            // out from the control.
                            text = previews.firstOrNull()
                                ?.takeIf { previews.size > 1 }
                                ?.let { stringResource(R.string.card_attach_photo_numbered, it.side) }
                                ?: stringResource(R.string.card_attach_photo),
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
            val counting = savedAt != null && (
                saveResult is CardSaveResult.Saved ||
                    saveResult is CardSaveResult.Held ||
                    // FR-1232 is undoable for the same ten seconds, and it is the undo that
                    // matters most: after the patch the previous values exist nowhere else.
                    saveResult is CardSaveResult.Updated
                )
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
                        when {
                            // FR-1232. **A different sentence, because a different thing
                            // happened**: "Nothing was kept", over a contact still in the account
                            // carrying its old employer again, would be false in the direction
                            // that matters most on this screen.
                            saveResult.removed && saveResult.restored -> R.string.card_update_undone
                            saveResult.removed -> R.string.card_undone
                            saveResult.restored -> R.string.card_update_undo_failed
                            else -> R.string.card_undo_failed
                        }
                    )
                )
                is CardSaveResult.AlreadySaved -> Note(stringResource(R.string.card_already_saved))

                // FR-1231, FR-1233. **Field by field, with what the contact holds now beside
                // each**, which is the requirement's own wording and not a summary of it: "3
                // fields would change" is a sentence nobody can answer, and the value that would
                // be replaced is the one thing the user cannot look up while holding a card.
                //
                // FR-1233 needs nothing of its own here. Employer and job title are visible
                // because every field is, and the move-note analogue the requirement forbids is
                // not merely absent - nothing on this path composes prose to put in a contact.
                is CardSaveResult.UpdateOffered -> {
                    Note(stringResource(R.string.card_update_offered))
                    // FR-1205 and SRS 1.159: a tick and an editable value per line. FR-511's
                    // per-date checkbox and FR-608's per-step tick, a third time — the developer
                    // must be able to take the job title, decline the address, and correct the
                    // website the recogniser got wrong, all without leaving the offer.
                    saveResult.changes.forEachIndexed { index, change ->
                        OfferRow(
                            label = stringResource(fieldLabel(change.field)),
                            stored = change.stored,
                            existing = change.existing,
                            value = offerValues[index] ?: change.captured,
                            ticked = index in offerAccepted,
                            onTick = { on -> onOfferTick(index, on) },
                            onEdit = { text -> onOfferEdit(index, text) },
                        )
                    }
                    Note(stringResource(R.string.card_update_edit_hint))
                    // Said out loud because it is the promise the requirement makes: an offer
                    // that has already written something is not an offer.
                    Note(stringResource(R.string.card_update_nothing_written))
                    if (offerAccepted.isEmpty()) {
                        Note(stringResource(R.string.card_update_nothing_ticked))
                    }
                }

                is CardSaveResult.Updated -> Note(stringResource(R.string.card_updated))
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

                // FR-1231. **Two answers, neither of them the default, and Save is gone.** The
                // requirement inherits FR-804's readings, and this is the one that has to be
                // visible in the layout rather than argued for in a comment: there is no
                // `Button` here that a stray tap could take, because the branch that draws Save
                // is not reached while the offer stands.
                if (saveResult is CardSaveResult.UpdateOffered) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCreateAnyway) {
                        ActionLabel(stringResource(R.string.card_update_create_new))
                    }
                    Spacer(Modifier.width(8.dp))
                    // **Disabled rather than hidden when nothing is ticked.** A control that
                    // vanishes leaves the user wondering what they did; one that is visible and
                    // off, beside a line saying why, is the same information without the puzzle.
                    TextButton(onClick = onUpdateContact, enabled = offerAccepted.isNotEmpty()) {
                        ActionLabel(stringResource(R.string.card_update_accept))
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

/**
 * One line of FR-1231's offer: a tick, what the contact holds now, and what would be written.
 *
 * **Only the new value is a box.** The stored value is what the user is deciding *against* and
 * editing it would write a value nobody read off anything; the captured value is a guess the
 * recogniser made and is exactly what FR-1205 requires be correctable.
 */
@Composable
private fun OfferRow(
    label: String,
    stored: String?,
    existing: List<String>,
    value: String,
    ticked: Boolean,
    onTick: (Boolean) -> Unit,
    onEdit: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(checked = ticked, onCheckedChange = onTick)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                // **Three sentences, because there are three situations** (SRS 1.160), and the
                // one in the middle is the one that was missing: a field that already holds
                // something the card does not match. Saying "not on the contact yet" there is
                // false, and false in the direction that hides what accepting would do.
                text = when {
                    stored != null -> stringResource(R.string.card_update_now, stored)
                    existing.isNotEmpty() -> stringResource(
                        R.string.card_update_adds_to,
                        existing.joinToString("; "),
                    )
                    else -> stringResource(R.string.card_update_will_add)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onEdit,
                label = { Text(label) },
                enabled = ticked,
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
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

/** FR-1229: how much of the sheet the previews may take between them, however many there are. */
private val PREVIEW_BUDGET = 320.dp

/**
 * FR-1231: the user's word for a field, which is not the API's and not the enum's.
 *
 * NFR-402 - the pure modules return data and never display text, so `ContactField` crosses from
 * `:google` as an enum and is named here.
 */
private fun fieldLabel(field: ContactField): Int = when (field) {
    ContactField.NAME -> R.string.card_field_name
    ContactField.ORGANISATION -> R.string.card_field_organisation
    ContactField.JOB_TITLE -> R.string.card_field_title
    ContactField.PHONE -> R.string.card_field_phone
    ContactField.EMAIL -> R.string.card_field_email
    ContactField.ADDRESS -> R.string.card_field_address
    ContactField.URL -> R.string.card_field_url
}
