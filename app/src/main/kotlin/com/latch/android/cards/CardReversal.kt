package com.latch.android.cards

import com.latch.android.capture.UndoTally
import com.latch.android.capture.undoDecisionLine
import com.latch.google.ContactRecord
import com.latch.google.ContactsApi
import com.latch.google.restoreContactUpdate
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

    /**
     * FR-1231 changed one. Undo **restores** it, and there is no branch here that deletes.
     *
     * **The requirement's own sentence is the reason it is a separate case rather than a flag**:
     * *it shall never delete the contact — the contact was the user's before Latch touched it.*
     * A boolean beside a resource name would put that guarantee in an `if`; a type with no delete
     * in it puts the guarantee in the compiler, which is `CreatedItem`'s reasoning on the date
     * side where an undo of an update that deleted would have been data loss on an item the user
     * owned before Latch existed.
     *
     * [prior] is what the contact held before the patch, read at match time. After the patch it
     * exists nowhere else. [etag] is the one the patch returned, and [fields] is the same mask —
     * a restore with a narrower mask would leave half the change standing and a wider one would
     * overwrite a field Latch never touched.
     */
    data class Updated(
        val resourceName: String,
        val prior: ContactRecord,
        val etag: String,
        val fields: List<String>,
    ) : CardCreated
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
     *
     * **Two lines come out of here now, and they answer different questions** (SRS 1.193). The
     * transport line above says what Google did with the request; the decision line below says
     * what this undo *was* — a delete, a restore or a dropped queue entry, three acts that end
     * at one sentence on screen. SRS 1.189 could not tell a restore from anything else on the
     * phone and had to close FR-1232 on the account instead.
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
        log(undoDecisionLine("card", UndoTally(deleted = if (removed) 1 else 0, failed = if (removed) 0 else 1)))
        CardRemoval(attempted = 1, removed = if (removed) 1 else 0)
    }

    is CardCreated.Queued -> {
        // Nothing is in the account, so there is nothing to delete: the entry goes instead. A
        // delete here would ask Google to remove a contact that was never created.
        val dropped = runCatching { dropQueued(created.entryId) }.getOrDefault(false)
        // The branch that had no line at all, transport or otherwise — nothing leaves the
        // device here, so there was no request for the transport line to report on. It is
        // also the branch whose success is hardest to see from outside: a dropped entry and
        // an entry that was never made look identical on disk.
        log(undoDecisionLine("card", UndoTally(dropped = if (dropped) 1 else 0, failed = if (dropped) 0 else 1)))
        CardRemoval(attempted = 1, removed = if (dropped) 1 else 0)
    }

    // FR-1232. **A write, not a delete**, and the strongest thing to say about it is that this
    // branch has no `deleteContact` in it to get wrong.
    is CardCreated.Updated -> {
        val outcome = runCatching {
            contacts.updateContact(
                created.resourceName,
                created.etag,
                restoreContactUpdate(created.prior, created.fields),
            )
        }
        val restored = outcome.isSuccess
        // **A failure here matters more than a failed delete and is logged as such.** A delete
        // that did not run leaves a contact the user can see and remove; a restore that did not
        // run leaves somebody's employer silently replaced, and the ten seconds in which they
        // could have said so are gone.
        log(
            "card undo restore=" + if (restored) "accepted" else
                (outcome.exceptionOrNull()?.javaClass?.simpleName ?: "refused")
        )
        log(undoDecisionLine("card", UndoTally(restored = if (restored) 1 else 0, failed = if (restored) 0 else 1)))
        CardRemoval(attempted = 1, removed = if (restored) 1 else 0)
    }
}

/**
 * FR-1232: is this undo a restore rather than a removal?
 *
 * The sheet's sentence differs because the outcome does — "Nothing was kept", over a contact still
 * in the account carrying its old employer again, would be false in the direction that matters.
 */
fun cardUndoRestores(created: CardCreated): Boolean = created is CardCreated.Updated

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
