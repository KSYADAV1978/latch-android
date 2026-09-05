package com.latch.android.cards

import com.latch.google.ContactsApi
import java.time.Duration
import java.time.Instant

/**
 * FR-1210: taking back a card save.
 *
 * **What undo does is chosen by what the save did**, which is `CreatedItem`'s shape on the date
 * side (SRS 1.16) and is here for the same reason: a written contact is undone by deleting it,
 * and a *queued* one by dropping the entry — nothing is in the account yet, so a delete would be
 * a request to remove something that does not exist.
 */
sealed interface CardCreated {
    /** FR-1206 wrote it. Undo deletes it. */
    data class Written(val resourceName: String) : CardCreated

    /** FR-1212 held it. Undo drops the entry, and the account never knew. */
    data class Queued(val entryId: String) : CardCreated
}

/**
 * The offer, as a pure function of the clock.
 *
 * **Ten seconds, and the window is protected from the sheet that made it** — the capture window
 * closes on a tap outside it, and `CaptureActivity` already suppresses its own dismissal while an
 * undo offer stands. The same applies here: an offer the user cannot reach is not an offer.
 *
 * CLAUDE.md records that ten seconds is tight for anyone who verifies before undoing — the window
 * was missed twice in device passes, both times while checking Google to confirm the write had
 * landed. FR-1210 says "not less than ten seconds" so nothing here is out of specification, and
 * the observation is carried forward rather than acted on unasked.
 */
fun cardUndoOffered(savedAt: Instant, now: Instant, window: Duration = CARD_UNDO_WINDOW): Boolean =
    !now.isBefore(savedAt) && Duration.between(savedAt, now) < window

fun cardUndoSecondsLeft(savedAt: Instant, now: Instant, window: Duration = CARD_UNDO_WINDOW): Long {
    if (!cardUndoOffered(savedAt, now, window)) return 0
    return window.seconds - Duration.between(savedAt, now).seconds
}

val CARD_UNDO_WINDOW: Duration = Duration.ofSeconds(10)

/** What an undo managed, so a partial failure is reported rather than swallowed. */
data class CardRemoval(val attempted: Int, val removed: Int) {
    val complete: Boolean get() = attempted == removed
}

/**
 * FR-1210's undo.
 *
 * **A delete that finds nothing is a success**, because the caller asked for the contact not to be
 * in the account and it is not — somebody may have removed it by hand in the ten seconds, and
 * reporting a failure would send them looking for something already gone. `ContactsRest` applies
 * that rule at the transport, on `alreadyGone`'s reasoning.
 */
suspend fun undoCardCreated(
    created: CardCreated,
    contacts: ContactsApi,
    /**
     * What the delete actually did, for the log.
     *
     * **Because "removed" is weaker than it looks** (SRS 1.129). `ContactsRest.deleteContact`
     * treats `alreadyGone` — a 404 or a 410 — as success, which is right when a user has removed
     * the contact by hand and is *indistinguishable from a request Google never routed*: the
     * first device run of FR-1226 proved that a wrong method binding on this API answers 404 with
     * no reason string. So an undo that deletes nothing can report that it deleted, and SRS
     * 1.125's sentence on screen would say so honestly and still be wrong.
     *
     * This does not fix that. It makes the next run readable, which is what SRS 1.72 did for save
     * decisions when the phone could not say which query had answered.
     */
    log: (String) -> Unit = {},
    /**
     * Last, so that the trailing-lambda call sites this function already had keep meaning what
     * they meant — a diagnostic added at the end would silently have rebound every one of them.
     */
    dropQueued: suspend (String) -> Boolean,
): CardRemoval = when (created) {
    is CardCreated.Written -> {
        val outcome = runCatching { contacts.deleteContact(created.resourceName) }
        val removed = outcome.isSuccess
        log(
            "card undo delete=" + if (removed) "accepted" else
                (outcome.exceptionOrNull()?.javaClass?.simpleName ?: "refused")
        )
        CardRemoval(attempted = 1, removed = if (removed) 1 else 0)
    }

    is CardCreated.Queued -> {
        // Nothing is in the account, so there is nothing to delete: the entry goes instead. A
        // delete here would ask Google to remove a contact that was never created.
        val dropped = runCatching { dropQueued(created.entryId) }.getOrDefault(false)
        CardRemoval(attempted = 1, removed = if (dropped) 1 else 0)
    }
}

/**
 * FR-1212: whether a queued card may be drained yet.
 *
 * **Not inside its undo window**, which is `drainable`'s rule on the date side and exists so that
 * an undo is never a race between dropping an entry and chasing a contact that has just been
 * written. Without it a drain landing at nine seconds turns `CardCreated.Queued` into a lie: the
 * entry is gone, the contact is in the account, and undo removes neither.
 */
fun cardDrainable(queuedAt: Instant, now: Instant, window: Duration = CARD_UNDO_WINDOW): Boolean =
    !cardUndoOffered(queuedAt, now, window)
