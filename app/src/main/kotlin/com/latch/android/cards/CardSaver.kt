package com.latch.android.cards

import com.latch.core.model.CardDraft
import com.latch.google.ContactDuplicateSearch
import com.latch.google.ContactsApi
import com.latch.google.contactWriteFor
import com.latch.wire.CardMetadata
import com.latch.wire.cardSourceHashOf
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

    /** FR-1208 found this exact card. Nothing is written. */
    data class AlreadySaved(val resourceName: String) : CardWriteDecision
}

fun cardWriteDecision(search: ContactDuplicateSearch): CardWriteDecision = when {
    search.found -> CardWriteDecision.AlreadySaved(checkNotNull(search.existingResourceName))
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
    data class Held(val entryId: String) : CardSaveResult

    /** [checked] false means FR-1208 could not complete; the item is written and the user is told. */
    data class Saved(val resourceName: String, val checked: Boolean) : CardSaveResult

    data object AlreadySaved : CardSaveResult
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
    data class Undone(val removed: Boolean) : CardSaveResult
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
) {

    suspend fun save(draft: CardDraft, payload: String, layer: String): CardSaveResult {
        val sourceHash = cardSourceHashOf(payload)

        val search = try {
            contacts.findContactBySourceHash(sourceHash)
        } catch (failure: Exception) {
            // A check that threw is not a check that found nothing. Writing here would be
            // FR-1208 skipped on the first flaky network, which is how the calendar's duplicates
            // happened — so nothing is written, and the capture is **held** rather than lost.
            // The check runs again at drain, which is where the question can be answered.
            return held(draft, payload, layer)
        }

        return when (val decision = cardWriteDecision(search)) {
            is CardWriteDecision.AlreadySaved -> CardSaveResult.AlreadySaved

            is CardWriteDecision.Create -> {
                val metadata = CardMetadata(
                    sourceHash = sourceHash,
                    // FR-1209: taken from the draft as the user confirmed it, so an address they
                    // corrected is the identity, and one they cleared is no identity at all.
                    identityKeys = contactIdentityKeys(draft),
                    capturedAt = now(),
                    captureLayer = layer,
                )
                try {
                    val name = contacts.createContact(
                        contactWriteFor(draft, metadata.toClientData()),
                    )
                    CardSaveResult.Saved(name, checked = decision.checked)
                } catch (failure: Exception) {
                    held(draft, payload, layer)
                }
            }
        }
    }

    /**
     * FR-1212, or an honest failure where there is no queue.
     *
     * FR-806's classifier is deliberately not consulted here: every failure that reaches this
     * point is one the duplicate check will be asked again about at drain, and an entry that can
     * never drain is dropped by the drain's own attempt limit rather than refused at the door —
     * where refusing means losing a capture that exists nowhere else.
     */
    private suspend fun held(draft: CardDraft, payload: String, layer: String): CardSaveResult {
        val queue = hold ?: return CardSaveResult.Failed(permanent = false)
        return runCatching { CardSaveResult.Held(queue(draft, payload, layer, now())) }
            .getOrElse { CardSaveResult.Failed(permanent = false) }
    }
}
