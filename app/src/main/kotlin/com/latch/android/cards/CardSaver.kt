package com.latch.android.cards

import com.latch.google.GoogleRejected
import com.latch.core.model.CardDraft
import com.latch.google.ContactDuplicateSearch
import com.latch.google.ContactFieldChange
import com.latch.google.ContactRecord
import com.latch.google.ContactsApi
import com.latch.google.contactChanges
import com.latch.google.contactWriteFor
import com.latch.google.mergedContactUpdate
import com.latch.google.restoreContactUpdate
import com.latch.wire.CardMetadata
import com.latch.wire.cardSourceHashOf
import com.latch.wire.cardPersonKeys
import com.latch.wire.contactIdentityKeys
import com.latch.wire.toClientData
import java.time.Instant

/**
 * FR-1208 as a pure function, so what a capped scan means is decided where a test can read it.
 *
 * **A capped scan writes, and says it could not check.** SRS §5.8 forbids reading a capped scan as
 * "no duplicate", which leaves two honest answers: write anyway, or refuse. Refusing loses the
 * capture, and design principle 1 puts that above everything; writing produces at worst a
 * duplicate contact, which §5.11.6 already records as the safe direction — visible, obvious and
 * deleted in one gesture, against a card the user has to type again. So it writes, and
 * [CardWriteDecision.Create.checked] carries the fact that it did not know, for the screen to say
 * rather than for the saver to swallow.
 */
sealed interface CardWriteDecision {

    /**
     * Write it. [checked] is false where FR-1208's scan gave up — the write is going ahead
     * *without* an answer, and the user is told.
     */
    data class Create(val checked: Boolean) : CardWriteDecision

    /** FR-1208 or FR-1231 found this card already in the account. Nothing is written. */
    data class AlreadySaved(
        val resourceName: String,
        /** SRS 1.72's lesson: two rows reach one sentence, so the log says which. */
        val basis: CardWriteBasis,
    ) : CardWriteDecision

    /**
     * FR-1231: this is somebody already in the account, and the card says something different.
     *
     * **An offer, and nothing is written while it stands.** FR-804's rule on contacts, inheriting
     * its readings: no default answer, the existing values quoted from what is stored, and a
     * multi-field change shown field by field.
     */
    data class UpdateOffered(
        val stored: ContactRecord,
        val changes: List<ContactFieldChange>,
    ) : CardWriteDecision
}

/**
 * Which check answered, for the log.
 *
 * **Built before the device pass rather than after it.** SRS 1.72 records that the phone could not
 * say which query had answered a save, that this cost two device sessions to establish, and that
 * the fix was one enum. *Already saved* now arrives from two different rows and they are not
 * equally strong, so the same obstacle would be waiting.
 */
enum class CardWriteBasis {
    /** FR-1208: the same card, byte for byte through the hash. The strong answer. */
    SOURCE_HASH,

    /** FR-1231: the same person, and the card says nothing the contact does not already hold. */
    IDENTITY_NO_CHANGE,
}

/**
 * FR-1208, then FR-1231 — the order FR-803 and FR-804 already take on the date side.
 *
 * [identityMatch] is the contact FR-1209's identity found, **already read back**, or null where
 * there was none. Read back rather than remembered: FR-1231 requires the offer to quote what is
 * stored, and a contact may have been edited by hand since Latch last saw it.
 */
fun cardWriteDecision(
    search: ContactDuplicateSearch,
    draft: CardDraft = CardDraft(),
    identityMatch: ContactRecord? = null,
): CardWriteDecision = when {
    search.found -> CardWriteDecision.AlreadySaved(
        checkNotNull(search.existingResourceName),
        CardWriteBasis.SOURCE_HASH,
    )

    // FR-1231, and it is placed **above** the capped check deliberately: a scan that gave up may
    // still have found this person on the pages it did read, and that answer is real. What a cap
    // costs is the pages it did not reach, not the ones it did.
    identityMatch != null -> {
        val changes = contactChanges(identityMatch, draft)
        if (changes.isEmpty()) {
            // §7.2's row 3 on a third transport: same identity, nothing would change, therefore
            // a duplicate. The loop terminates on the *values* rather than on a stored marker,
            // which is what lets a contact Latch updated but never created settle down.
            CardWriteDecision.AlreadySaved(
                identityMatch.resourceName,
                CardWriteBasis.IDENTITY_NO_CHANGE,
            )
        } else {
            CardWriteDecision.UpdateOffered(identityMatch, changes)
        }
    }

    // Capped: the scan answered "I do not know", and that is not "no".
    search.scanCapped -> CardWriteDecision.Create(checked = false)
    else -> CardWriteDecision.Create(checked = true)
}

/** What the sheet shows after a save. */
sealed interface CardSaveResult {
    data object Idle : CardSaveResult
    data object Saving : CardSaveResult

    /**
     * FR-1212: held on this phone, and written when there is a network.
     *
     * A distinct state from [Saved] for the reason the date side keeps `Queued` distinct: the
     * account holds nothing yet, so saying "saved" would be a lie in the reassuring direction —
     * and FR-1210's undo drops the entry rather than deleting a contact.
     */
    data class Held(
        val entryId: String,
        /**
         * FR-1226 and FR-1211: a held card carries **no** photograph, so a user who ticked the box
         * is told the photo was dropped rather than left to notice its absence later.
         */
        val photo: CardPhotoUpload = CardPhotoUpload.NOT_REQUESTED,
    ) : CardSaveResult

    /**
      * [checked] false means FR-1208 could not complete; the item is written and the user is told.
      *
      * [photo] is FR-1226's outcome, carried **beside** the save rather than folded into it: a
      * photograph that would not attach must not read as a contact that did not save, which is
      * the requirement's own sentence.
      */
    data class Saved(
        val resourceName: String,
        val checked: Boolean,
        val photo: CardPhotoUpload = CardPhotoUpload.NOT_REQUESTED,
    ) : CardSaveResult

    data object AlreadySaved : CardSaveResult

    /**
     * FR-1231: this person is already in the account and the card differs. Nothing is written.
     *
     * **A state the sheet cannot pass through by accident.** FR-804's readings apply: no default
     * answer, and Save is withdrawn while it stands — a preselected button on a floating sheet a
     * stray tap can dismiss is how a silent change to somebody's address book would happen.
     */
    data class UpdateOffered(
        val stored: ContactRecord,
        val changes: List<ContactFieldChange>,
    ) : CardSaveResult

    /**
     * FR-1231 accepted. [prior] is what the contact held **before** the patch and exists nowhere
     * else afterwards, which is FR-1232's whole difficulty; [etag] is the one the patch returned,
     * without which the restore cannot be sent at all.
     */
    data class Updated(
        val resourceName: String,
        val prior: ContactRecord,
        val etag: String,
        val fields: List<String>,
    ) : CardSaveResult

    data class Failed(val permanent: Boolean) : CardSaveResult

    /**
     * FR-1210 and NFR-303: the undo ran, and whether it actually removed anything.
     *
     * **A state rather than a closing window** (SRS 1.125). The sheet used to discard
     * `undoCardCreated`'s answer and `finish()` regardless, so a delete that *failed* looked
     * exactly like one that succeeded — the window closed, the contact stayed in the user's
     * account, and nothing said so. NFR-303 calls a silent failure a defect in as many words, and
     * the date side has always kept an undo's outcome on screen for this reason.
     *
     * The tell was in the lint report for a slice: `card_undone` was reported as an unused
     * resource, because the state that would have shown it did not exist.
     */
    data class Undone(
        val removed: Boolean,
        /**
         * FR-1232: this undo put values back rather than taking a contact away, and the sentence
         * differs because the outcome does. "Nothing was kept" over a contact that is still there,
         * carrying its old employer again, would be false in the direction that matters.
         */
        val restored: Boolean = false,
    ) : CardSaveResult
}

/**
 * FR-1206 and FR-1208.
 *
 * **The check runs before the insert, every time, and the order is the requirement.** FR-800's
 * lesson is that an item written before duplicate detection exists becomes unmanageable; this file
 * exists in the slice after the check rather than beside it for that reason.
 *
 * **The metadata is composed once, here, and never rewritten.** FR-1207's record is provenance —
 * what was captured and when — so Phase C's update will change fields and leave it alone. That is
 * SRS 1.18's write-once reading, which this project has already watched hold on a device: after an
 * FR-804 update the event's description still showed the original capture's text.
 *
 * No `android.*` import, deliberately, exactly as `CaptureSaver` has none: `android.util.Log` is a
 * throwing stub under JVM unit tests and one import here would cost every test in this file.
 */
class CardSaver(
    private val contacts: ContactsApi,
    private val now: () -> Instant = Instant::now,
    /**
     * FR-1212. Null where there is nowhere to hold a card, in which case a failure is reported —
     * **accepting a card and never writing it is worse than declining it**, and a saver with no
     * queue must not pretend to have one.
     */
    private val hold: (suspend (CardDraft, String, String, Instant) -> String)? = null,
    /**
     * FR-1226: why a photograph would not attach, for the log.
     *
     * **A sink rather than a call to `Log`**, exactly as `CaptureSaver`'s decision line is: one
     * `android.util.Log` import here would break every test in this file, that class being a
     * throwing stub under JVM unit tests. `LatchApplication` supplies the logcat one.
     *
     * It exists because the first device run of this path failed and the phone said only that it
     * had — the same obstacle SRS 1.72 records for save decisions, met the same way.
     */
    private val logPhoto: (String) -> Unit = {},
    /**
     * Which row of the card decision table answered, for the log (SRS 1.72's lesson).
     *
     * A sink rather than a call to `Log`, for [logPhoto]'s reason. It carries an enum name and a
     * count and no card content — the same structural guarantee `SaveDecisionLog` makes on the
     * date side, where the obvious spelling would have leaked a title from the user's own item.
     */
    private val logDecision: (String) -> Unit = {},
) {

    /**
     * @param photoJpeg FR-1226's square JPEG, or null where the user did not ask for one.
     *   Passed as **bytes already encoded**, so this file keeps its promise of no `android.*`
     *   import and the decision of what the image should look like stays in `CardImage.kt`.
     */
    suspend fun save(
        draft: CardDraft,
        payload: String,
        layer: String,
        photoJpeg: ByteArray? = null,
    ): CardSaveResult {
        val sourceHash = cardSourceHashOf(payload)

        val identityKeys = contactIdentityKeys(draft)
        val search = try {
            val found = contacts.findContactBySourceHash(
                sourceHash = sourceHash,
                // FR-1231. Asked in the same pass as FR-1208's question, because the account this
                // was built against holds 3,157 contacts and a second scan would be a second page
                // loop the save path waits on.
                identityKeys = identityKeys,
            )
            // Read back only where there is something to read and something to decide. An
            // identity match beside an exact one changes nothing — the exact answer is stronger
            // and the offer would be about a card already saved.
            val stored = if (!found.found && found.identityResourceName != null) {
                contacts.getContact(found.identityResourceName!!)
            } else null
            found to stored
        } catch (failure: Exception) {
            // A check that threw is not a check that found nothing. Writing here would be
            // FR-1208 skipped on the first flaky network, which is how the calendar's duplicates
            // happened — so nothing is written, and the capture is **held** rather than lost.
            // The check runs again at drain, which is where the question can be answered.
            return held(draft, payload, layer, photoJpeg)
        }

        return when (val decision = cardWriteDecision(search.first, draft, search.second)) {
            is CardWriteDecision.AlreadySaved -> {
                logDecision("card decision=AlreadySaved basis=${decision.basis.name}")
                CardSaveResult.AlreadySaved
            }

            // FR-1231: nothing is written. The user answers on the sheet, and `update` or
            // `createAnyway` is what they answer with.
            is CardWriteDecision.UpdateOffered -> {
                logDecision("card decision=UpdateOffered changes=${decision.changes.size}")
                CardSaveResult.UpdateOffered(decision.stored, decision.changes)
            }

            is CardWriteDecision.Create -> {
                logDecision("card decision=Create checked=${decision.checked}")
                val metadata = CardMetadata(
                    sourceHash = sourceHash,
                    // FR-1209: taken from the draft as the user confirmed it, so an address they
                    // corrected is the identity, and one they cleared is no identity at all.
                    identityKeys = contactIdentityKeys(draft),
                    capturedAt = now(),
                    captureLayer = layer,
                    // FR-1227. Written for the *next* capture's benefit, never read by this save:
                    // the question it answers is asked before a save, not during one.
                    personKeys = cardPersonKeys(draft),
                )
                try {
                    val name = contacts.createContact(
                        contactWriteFor(draft, metadata.toClientData()),
                    )
                    // **After the contact, and its failure is not the contact's** (FR-1226). The
                    // photograph is a second request; a contact with no photo is the whole of what
                    // the user came for, so this is caught here rather than allowed to unwind a
                    // save that has already succeeded — which would report a loss that did not
                    // happen and, worse, tempt a retry that would write a second contact.
                    CardSaveResult.Saved(name, checked = decision.checked, photo = attach(name, photoJpeg))
                } catch (failure: Exception) {
                    held(draft, payload, layer, photoJpeg)
                }
            }
        }
    }

    /**
     * FR-1231 accepted: patch the contact the offer named.
     *
     * **The stored record is carried into the result, not re-read afterwards.** After the patch
     * the previous values exist nowhere else — FR-1232's own sentence — so re-reading would
     * return what Latch has just written and the undo would restore the change it was undoing.
     * That is `CreatedItem.Updated`'s reasoning on the date side, one API over.
     *
     * **FR-1207's record is untouched.** The mask never names `clientData`, so a contact Latch
     * created keeps the provenance of the card that created it, and a contact it did not create
     * gains none — which is what lets the same card captured again settle to "already saved" on
     * the values rather than on a marker this path would have had to write.
     */
    suspend fun update(
        stored: ContactRecord,
        draft: CardDraft,
        changes: List<ContactFieldChange>,
    ): CardSaveResult {
        val patch = mergedContactUpdate(stored, draft, changes)
        return try {
            val etag = contacts.updateContact(stored.resourceName, stored.etag, patch)
            logDecision("card decision=Updated fields=${patch.fields.size}")
            CardSaveResult.Updated(
                resourceName = stored.resourceName,
                prior = stored,
                // **The etag the patch returned**, because the next update — the undo — needs a
                // current one and the stored record's is now stale. An undo that could not name
                // an etag would be refused with a 400 that names nothing.
                etag = etag.ifBlank { stored.etag },
                fields = patch.fields,
            )
        } catch (failure: Exception) {
            // **Not held.** FR-1212's queue drains as a *create*, and holding an update there
            // would turn a refused patch into a second contact minutes later — silently, which
            // is the one outcome FR-1231 exists to prevent. A failure here is reported.
            CardSaveResult.Failed(permanent = failure is GoogleRejected && !isTransient(failure))
        }
    }

    /**
     * FR-1231 declined: the user says this is somebody else, or a second record they want.
     *
     * **The identity check is not run again.** It has just answered, the user has read the answer
     * and rejected it, and asking twice would either produce the same offer or — worse — a
     * different one from a scan that raced an edit.
     */
    suspend fun createAnyway(
        draft: CardDraft,
        payload: String,
        layer: String,
        photoJpeg: ByteArray? = null,
    ): CardSaveResult {
        val metadata = CardMetadata(
            sourceHash = cardSourceHashOf(payload),
            identityKeys = contactIdentityKeys(draft),
            capturedAt = now(),
            captureLayer = layer,
            personKeys = cardPersonKeys(draft),
        )
        return try {
            val name = contacts.createContact(contactWriteFor(draft, metadata.toClientData()))
            logDecision("card decision=CreateAnyway")
            CardSaveResult.Saved(name, checked = true, photo = attach(name, photoJpeg))
        } catch (failure: Exception) {
            held(draft, payload, layer, photoJpeg)
        }
    }

    /**
     * A rejection that will never succeed on a retry, so the sheet can say so.
     *
     * A 409 is the etag one — somebody else changed the contact between the read and the patch —
     * and it is not permanent: capturing again re-reads and re-offers against what is there now.
     */
    private fun isTransient(rejected: GoogleRejected): Boolean =
        rejected.status == 409 || rejected.status == 429 || rejected.status >= 500

    /**
     * FR-1212, or an honest failure where there is no queue.
     *
     * FR-806's classifier is deliberately not consulted here: every failure that reaches this
     * point is one the duplicate check will be asked again about at drain, and an entry that can
     * never drain is dropped by the drain's own attempt limit rather than refused at the door —
     * where refusing means losing a capture that exists nowhere else.
     */
    private suspend fun held(
        draft: CardDraft,
        payload: String,
        layer: String,
        photoJpeg: ByteArray?,
    ): CardSaveResult {
        val queue = hold ?: return CardSaveResult.Failed(permanent = false)
        return runCatching {
            CardSaveResult.Held(
                entryId = queue(draft, payload, layer, now()),
                // FR-1226 and FR-1211. The queue is persistent storage and a third party's
                // photograph must not reach it, so the bytes are dropped here — deliberately, and
                // said on the sheet rather than left to be noticed.
                photo = if (photoJpeg == null) CardPhotoUpload.NOT_REQUESTED
                else CardPhotoUpload.NOT_HELD,
            )
        }.getOrElse { CardSaveResult.Failed(permanent = false) }
    }

    /**
     * FR-1226: attach the card, and report rather than throw.
     *
     * The contact exists by the time this runs. Anything that goes wrong from here is a photograph
     * the user does not get, which the requirement is explicit must not be reported as a failed
     * save — so every failure becomes [CardPhotoUpload.FAILED] and the sheet says which.
     */
    private suspend fun attach(resourceName: String, photoJpeg: ByteArray?): CardPhotoUpload {
        if (photoJpeg == null) return CardPhotoUpload.NOT_REQUESTED
        return try {
            contacts.updateContactPhoto(resourceName, photoJpeg)
            logPhoto("card photo attached, ${photoJpeg.size} bytes")
            CardPhotoUpload.ATTACHED
        } catch (failure: Exception) {
            // **The status and the reason, and nothing else.** Google's machine-readable reason
            // is what separates a scope problem from a malformed body; neither it nor the byte
            // count is card content, and NFR-202's instinct applies to a log as much as to an
            // analytics SDK.
            val detail = (failure as? GoogleRejected)
                ?.let { "status=${it.status} reason=${it.reason}" }
                ?: failure.javaClass.simpleName
            logPhoto("card photo refused, ${photoJpeg.size} bytes, $detail")
            CardPhotoUpload.FAILED
        }
    }
}
