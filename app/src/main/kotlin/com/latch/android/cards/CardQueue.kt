package com.latch.android.cards

import com.latch.core.model.CardDraft
import com.latch.data.CardQueue
import com.latch.wire.cardSourceHashOf
import com.latch.wire.contactIdentityKeys
import com.latch.google.ContactsApi
import com.latch.google.contactWriteFor
import com.latch.wire.CardMetadata
import com.latch.wire.toClientData
import java.time.Instant

/**
 * FR-1212: a card save made with no network.
 *
 * **It uses FR-806's machinery and not FR-806's record**, and that distinction is a reading
 * (SRS 1.99). `PendingWrite` carries `RemoteMetadata` and a list of dated `Item`s; a contact has
 * neither, so carrying one there would mean nullable fields throughout *and* a record version
 * bump — which re-encodes every entry already on disk, each of which holds a capture that exists
 * nowhere else. SRS 1.24 refused exactly that trade for exactly that reason when the write queue
 * was asked to move into SQLite. So the entries live apart and the worker, the backoff, the
 * connectivity trigger and Retry now are shared.
 */
data class QueuedCard(
    val id: String,
    val draft: CardDraft,
    /** FR-1208's hash comes from the payload, so the payload is what is held. */
    val payload: String,
    val layer: String,
    val queuedAt: Instant,
    /** FR-806's classifier, so an entry that can never drain is not retried for ever. */
    val attempts: Int = 0,
)

/** What a drain did with one entry, so the caller can report rather than guess. */
enum class CardDrainOutcome {
    /** Written to the account. The entry goes. */
    WRITTEN,

    /** FR-1208 found it already there. The entry goes, and nothing was written. */
    RETIRED,

    /** Not yet — still inside its FR-1210 window, or the network is still down. Entry stays. */
    KEPT,
}

/**
 * Drain one queued card.
 *
 * **FR-1208 runs again here, per entry, immediately before the insert.** Without it two offline
 * captures of one card become two contacts, which is AC-07's failure inside AC-10 on a third
 * transport — and SRS 1.37 records what it cost to find that the first time, when the same check
 * was missing from the calendar drain and was unreachable from any JVM test. This function takes
 * its dependencies as parameters so that a test can call it, which is the whole of why the
 * calendar's version was wrong for a slice.
 */
suspend fun drainCard(
    entry: QueuedCard,
    now: Instant,
    contacts: ContactsApi,
    sourceHashOf: (String) -> String,
    identityKeysOf: (CardDraft) -> List<String>,
): CardDrainOutcome {
    // FR-1210 first: an entry inside its undo window must not be written, or the undo becomes a
    // race between dropping the entry and chasing a contact that has just appeared.
    if (!cardDrainable(entry.queuedAt, now)) return CardDrainOutcome.KEPT

    val hash = sourceHashOf(entry.payload)
    val search = try {
        contacts.findContactBySourceHash(hash)
    } catch (failure: Exception) {
        // A check that threw is not a check that found nothing — the same rule the saver
        // applies, and the reason the entry stays rather than being written blind.
        return CardDrainOutcome.KEPT
    }

    return when (cardWriteDecision(search)) {
        is CardWriteDecision.AlreadySaved -> CardDrainOutcome.RETIRED

        is CardWriteDecision.Create -> {
            val metadata = CardMetadata(
                sourceHash = hash,
                identityKeys = identityKeysOf(entry.draft),
                // The instant the capture was made, not the instant it drained. FR-1207's record
                // is provenance, and "when did I get this card" is the question it answers.
                capturedAt = entry.queuedAt,
                captureLayer = entry.layer,
            )
            try {
                contacts.createContact(contactWriteFor(entry.draft, metadata.toClientData()))
                CardDrainOutcome.WRITTEN
            } catch (failure: Exception) {
                CardDrainOutcome.KEPT
            }
        }
    }
}

/**
 * FR-1212: drain every held card that is ready.
 *
 * **Oldest first, and one failure does not stop the rest.** A card the network refuses stays and
 * is tried again; the next entry is still attempted, because a queue that stopped at the first
 * failure would let one bad entry hold everything behind it — which is the shape SRS 1.24's
 * per-item marker exists to prevent on the date side.
 *
 * **The attempt limit is here rather than at the door.** `CardSaver` holds a card whatever the
 * failure was, because refusing at save time loses a capture; an entry that can never drain is
 * given up on *here*, where it has demonstrated that rather than been predicted.
 */
suspend fun drainHeldCards(
    queue: CardQueue,
    contacts: ContactsApi,
    now: Instant = Instant.now(),
    attemptLimit: Int = CARD_ATTEMPT_LIMIT,
): CardDrainReport {
    var written = 0
    var retired = 0
    var kept = 0
    for (held in queue.pending()) {
        if (held.attempts >= attemptLimit) {
            // Kept, not deleted: the entry holds a capture that exists nowhere else, and the
            // write queue's own rule is that a given-up entry stays and can be revived.
            kept++
            continue
        }
        val entry = QueuedCard(
            id = held.id,
            draft = held.draft,
            payload = held.payload,
            layer = held.layer,
            queuedAt = held.queuedAt,
            attempts = held.attempts,
        )
        when (drainCard(entry, now, contacts, ::cardSourceHashOf, ::contactIdentityKeys)) {
            CardDrainOutcome.WRITTEN -> { queue.retire(held.id); written++ }
            CardDrainOutcome.RETIRED -> { queue.retire(held.id); retired++ }
            CardDrainOutcome.KEPT -> { queue.markAttempted(held.id); kept++ }
        }
    }
    return CardDrainReport(written, retired, kept)
}

data class CardDrainReport(val written: Int, val retired: Int, val kept: Int)

/** Eight attempts, as FR-806's queue gives its own entries before it stops. */
const val CARD_ATTEMPT_LIMIT = 8
