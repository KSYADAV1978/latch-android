package com.latch.data

import com.latch.core.model.Item
import com.latch.core.model.RoutingMode
import java.time.Instant

/**
 * FR-806: writes are queued locally when offline and retried on reconnection, with the queue
 * visible to the user. NFR-302: nothing is lost to network failure, app termination or
 * restart while queued.
 *
 * Webhook deliveries (FR-1004) must never enter this queue — FR-1004b makes them a single
 * best-effort attempt at save time, and the SRS states the offline consequence explicitly:
 * an item saved offline is written to Google on reconnection and no webhook is ever sent
 * for it (AC-21).
 */
interface WriteQueue {
    /**
     * Returns the queue id, which is what FR-807 undo needs to drop the entry again.
     *
     * @param operation defaults to [WriteOperation.CREATE], which is every entry the queue
     *   held before FR-804. An `UPDATE` must carry [PendingWrite.targetRemoteId] and
     *   [PendingWrite.priorState]; without them it could neither be applied nor undone.
     */
    suspend fun enqueue(
        write: PendingWrite,
        operation: WriteOperation = WriteOperation.CREATE,
    ): String

    suspend fun pending(): List<QueuedWrite>

    /** FR-806: the queue as the user is shown it. */
    suspend fun status(): QueueStatus

    suspend fun markWritten(queueId: String, remoteId: String)

    /**
     * SRS 1.24's per-item written marker, and the cure for the limitation §7.1 records beside
     * it: **a chain that fails part-way through its inserts is left short on the next attempt.**
     *
     * The entry stays queued, as it must — dropping it would lose the capture. But FR-803 at
     * the head of the retry asks whether the *message* has been saved, finds the items that did
     * get written, and answers yes; the drain then retires the entry with the rest of the chain
     * never written. No reordering of that check fixes both cases: asked per item it loses whole
     * chains to the duplicate the chain created itself, asked once it cannot tell a
     * half-succeeded chain from one already saved.
     *
     * So the drain stops asking a question about the message on a retry and resumes at the
     * first item it has no marker for. Called after **each** insert of a chain, not after the
     * last, which is the whole point.
     */
    suspend fun markItemWritten(queueId: String, itemId: String, remoteId: String)

    /**
     * NFR-303: a failure surfaces a clear, actionable message. Silent failure is a defect.
     *
     * [permanent] separates "not yet" from "not ever". A lost network is the first and must
     * be retried until it comes back; a 403 for a scope the user has not granted is the
     * second, and retrying it forever spends battery on a request that cannot succeed while
     * leaving the entry on screen with nothing said about why it is stuck.
     */
    /**
     * @param needsSignIn FR-806a: the entry is held because Google wants the user to sign in
     *   again. Distinct from [permanent] — this entry has **not** been given up on; it is
     *   waiting on a tap the user can actually make.
     */
    /**
     * @param failureClass FR-806's immediate drain applies only to a queue held up by the
     *   transport. A 429 or a 500 is not cured by learning that the socket works, and retrying
     *   one the moment connectivity is reported would be hammering a server that has just said
     *   it is busy.
     */
    suspend fun markFailed(
        queueId: String,
        error: String,
        permanent: Boolean,
        needsSignIn: Boolean = false,
        failureClass: FailureClass = FailureClass.TRANSPORT,
    )

    /**
     * FR-806's "Retry now": puts a given-up entry back in the queue so the next drain takes it.
     *
     * FR-806's own note records that an entry given up on "stays in the queue and is shown as
     * stuck, but has no manual retry or dismissal, because the screen that would offer one is
     * Settings (FR-1000) and that is not built". The home screen offers one now. Nothing about
     * the classification changes — a 403 for a scope the user has not granted will fail again —
     * but the user has usually done something between the failure and the tap, and refusing to
     * try is worse than trying and saying so.
     */
    suspend fun reviveGivenUp(): Int

    /**
     * FR-807: an undo of a write that has not drained yet. Returns true where the entry was
     * still there to remove — false means the worker got to it first and the item is in the
     * account, so the caller has a remote delete to do instead.
     */
    suspend fun drop(queueId: String): Boolean
}

/**
 * Everything needed to perform a write later, when there was no network to perform it now.
 *
 * **Not an `Item`.** This contract used to take one, and an `Item` cannot carry a write:
 * §7.2's metadata — the source hash, the item key, the capture time, the source application
 * — is not on it, and neither is the FR-805 description nor the time zone the start resolves
 * against. A queue entry built from an `Item` alone would write an item with no metadata,
 * which §7.2 records as permanently unmanageable by FR-803, FR-804 and FR-807.
 *
 * **[body] is the composed FR-805 block, never the raw capture text**, and that is what keeps
 * NFR-206 structural rather than careful. The queue is persistent storage; `sourceBlock` has
 * already dropped the source text for the notification layer by the time anything reaches
 * here, so there is no raw capture on this type for a future call site to store by accident.
 */
data class PendingWrite(
    /**
     * The whole chain one capture produced, in the order it will be written (SRS §7.1,
     * corrected a third time at v1.23).
     *
     * Not one item. FR-803 asks its question of the **message** — §7.2 says every item of a
     * capture shares a `source_hash` and that the question is "has this message already been
     * saved" — so an entry per item would have the check asked once per item, and the second
     * entry to drain would find the first entry's item and remove itself without writing.
     * The chain would reach the account one item short, silently, losing more the longer it
     * was. One entry per chain also keeps an FR-807 undo of a queued save atomic, and stops
     * a process death between two drains leaving half a chain on disk.
     *
     * An `UPDATE` carries exactly one, because an update targets one existing item.
     */
    val items: List<Item>,
    val metadata: RemoteMetadata,
    val body: String,
    /** An IANA zone id. `Item.start` is a `LocalDateTime` and needs one to resolve. */
    val timeZone: String,
    /**
     * The item an [WriteOperation.UPDATE] is to modify (SRS §7.1, corrected at v1.16).
     *
     * A `CREATE` has no target and leaves this null. Without it an entry that outlived the
     * process would wake with the new dates and no way to say which item they belonged to —
     * surviving a restart being the entire purpose of the queue.
     */
    val targetRemoteId: String? = null,
    /**
     * What the target held before the update, so FR-807's undo can put it back.
     *
     * Bounded to exactly the fields an update may modify, which is what [ItemDates] is: no
     * previous description or notes, because an update does not touch them, and no previous
     * §7.2 metadata, because that is written once at insert and never modified — so there is
     * no prior value to restore.
     *
     * Null for a `CREATE`, which is undone by deleting what it made rather than by
     * restoring anything.
     */
    val priorState: ItemDates? = null,
) {
    init {
        require(items.isNotEmpty()) { "A queued write with no items would drain to nothing" }
    }

    /** The chain's first item, which is every item for an `UPDATE` and for a single capture. */
    val item: Item get() = items.first()
}

data class QueuedWrite(
    val id: String,
    val write: PendingWrite,
    val operation: WriteOperation,
    val attempts: Int,
    val lastError: String?,
    /**
     * Set where the failure will not come right on its own. The entry stays — dropping it
     * would lose the capture, which is the one thing NFR-302 forbids — but it is no longer
     * retried, and it is counted separately so the home screen can say that it is stuck
     * rather than showing it as merely waiting.
     */
    val givenUp: Boolean = false,
    /**
     * FR-806a. Set where the last attempt failed because Google wants the user to sign in
     * again — not a network failure and not a permanent one. The entry keeps being retried,
     * because a drain after the user has signed in will succeed; what changes is what the
     * queue is able to tell them, which is the difference between a wait they can end and a
     * wait they cannot.
     */
    val needsSignIn: Boolean = false,
    /**
     * When this entry was queued. FR-807's undo window is open for ten seconds after a save,
     * and a queued write inside that window must not drain — see `WriteQueueWorker`, which
     * skips young entries so that an undo cannot race a drain it would then have to chase
     * into the account.
     */
    val queuedAt: Instant,
    /**
     * What kind of thing stopped the last attempt, so FR-806's immediate drain can tell an
     * entry that connectivity would help from one it would not.
     *
     * [FailureClass.TRANSPORT] on a fresh entry, because a fresh entry is here for exactly that
     * reason: the queue is a fallback taken when a write could not reach Google.
     */
    val failureClass: FailureClass = FailureClass.TRANSPORT,
    /**
     * SRS 1.24: which items of this chain are already in the account, by `Item.id`.
     *
     * Empty for every entry that has never been attempted, which is every entry that has never
     * failed part-way. A retry resumes at the first item not in here rather than re-asking
     * FR-803's question about the message — which finds the chain's own first item and
     * concludes, wrongly, that there is nothing left to do.
     */
    val writtenItemIds: Set<String> = emptySet(),
)

/**
 * FR-806's "visible to the user", as two numbers rather than one.
 *
 * A capture waiting for the network and a capture that will never be written are not the
 * same fact, and a single count would show them alike — which is the silent failure NFR-303
 * forbids, dressed up as a queue that simply never drains.
 */
data class QueueStatus(
    val waiting: Int,
    val givenUp: Int,
    /**
     * FR-806a: at least one waiting entry is held because Google wants the user to sign in
     * again, not because the network is down.
     *
     * A separate fact from [waiting] rather than a third count, because it does not partition
     * the queue — it says why the queue is not moving. And separate from [givenUp] because the
     * entry has **not** been given up on: a sign-in fixes it, which is the one thing the user
     * can do about a stuck queue, and telling them "no connection" instead would send them to
     * check a network that is working.
     */
    val needsSignIn: Boolean = false,
) {
    val total: Int get() = waiting + givenUp
    val isEmpty: Boolean get() = total == 0
}

enum class WriteOperation { CREATE, UPDATE, DELETE }

/**
 * What kind of thing stopped a write, as far as FR-806 needs to care.
 *
 * The distinction exists for one decision: whether learning that the network is back is
 * *evidence* that this entry might now succeed. For [TRANSPORT] it is; for the others it is
 * not, and treating them alike would turn a restored connection into a burst of requests
 * against a server that has already said it is overloaded.
 */
enum class FailureClass {
    /** No network, DNS, TLS, timeout — [GoogleUnreachable]. Connectivity returning is real news. */
    TRANSPORT,

    /** A 429 or a 5xx. Waiting is the cure, and the wait is the server's to set, not ours. */
    SERVER,

    /** FR-806a: Google wants a sign-in. A tap fixes it; a network does not. */
    SIGN_IN,

    /** A 400, a 403 for a scope not granted, or a bug in our own mapping. Retrying repeats it. */
    PERMANENT,
}

/**
 * NFR-203: OAuth tokens go in the platform secure store, never in plain preferences. The
 * FR-1004 webhook endpoint is stored the same way and treated as a secret, because such
 * URLs commonly embed a bearer token, and it is masked in Settings once saved.
 */
interface SecretStore {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun clear()
}

/**
 * What first-run setup produces (FR-101 to FR-110), and what Settings later edits (FR-1001).
 *
 * Keyed by account because FR-110 stores calendar and task list defaults per account. That
 * costs nothing now and stops the account switcher being a refactor later.
 *
 * [destinationCalendarId] is the Latch calendar under Option B and the user's chosen
 * calendar under Option A; FR-1002 is why switching mode later must not clear the other
 * mode's choice, and FR-908 is why a stored id must be re-validated on launch.
 */
data class AccountDefaults(
    val accountId: String,
    val email: String,
    val routingMode: RoutingMode,
    val destinationCalendarId: String,
    /**
     * The destination's name and colour as Google gives them, stored rather than fetched.
     *
     * FR-904 wants the destination on screen as a chip in its own colour before the user
     * confirms a capture, and NFR-101 gives the whole path from gesture to confirmation UI
     * 800 ms. A `calendarList` round-trip per capture would spend most of that budget on a
     * value that changes about never. FR-908's launch-time refresh is where staleness is
     * meant to be corrected.
     */
    val destinationCalendarName: String,
    /** `#rrggbb`, or empty where Google gave none — the UI renders an unparseable colour grey. */
    val destinationCalendarColour: String,
    val taskListId: String,
)

interface AccountDefaultsStore {
    suspend fun defaultsFor(accountId: String): AccountDefaults?

    /** FR-110: more than one account may be set up. */
    suspend fun allAccounts(): List<AccountDefaults>

    /**
     * Written once, at the end of setup. Until this succeeds the app has no configured
     * account and first launch runs setup again (FR-101) — which, with FR-105, is what
     * makes an abandoned setup leave nothing behind either locally or in Google (AC-16).
     */
    suspend fun save(defaults: AccountDefaults)

    suspend fun remove(accountId: String)
}
