package com.latch.data

import com.latch.core.model.Capture
import com.latch.core.model.Item
import com.latch.core.model.RoutingMode
import java.time.Instant

/**
 * FR-701 to FR-703: the Capture Inbox holds items that are undated, incomplete, or below
 * the confidence threshold.
 *
 * Nothing here reaches Google. Inbox contents are local until the user confirms them
 * (FR-703), the store is app-private and encrypted at rest (NFR-204), and content from the
 * notification listener is never written at all (FR-210, NFR-206) — that layer holds its
 * text in memory and only a confirmed item ever arrives here.
 */
interface CaptureInbox {
    suspend fun add(capture: Capture, items: List<Item>)
    suspend fun pending(): List<Capture>
    suspend fun pendingCount(): Int
    suspend fun discard(captureId: String)

    /** NFR-205: one action revokes access and deletes everything local. */
    suspend fun deleteAll()
}

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
    /** Returns the queue id, which is what FR-807 undo needs to drop the entry again. */
    suspend fun enqueue(write: PendingWrite): String

    suspend fun pending(): List<QueuedWrite>

    /** FR-806: the queue as the user is shown it. */
    suspend fun status(): QueueStatus

    suspend fun markWritten(queueId: String, remoteId: String)

    /**
     * NFR-303: a failure surfaces a clear, actionable message. Silent failure is a defect.
     *
     * [permanent] separates "not yet" from "not ever". A lost network is the first and must
     * be retried until it comes back; a 403 for a scope the user has not granted is the
     * second, and retrying it forever spends battery on a request that cannot succeed while
     * leaving the entry on screen with nothing said about why it is stuck.
     */
    suspend fun markFailed(queueId: String, error: String, permanent: Boolean)

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
    val item: Item,
    val metadata: RemoteMetadata,
    val body: String,
    /** An IANA zone id. `Item.start` is a `LocalDateTime` and needs one to resolve. */
    val timeZone: String,
)

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
     * When this entry was queued. FR-807's undo window is open for ten seconds after a save,
     * and a queued write inside that window must not drain — see `WriteQueueWorker`, which
     * skips young entries so that an undo cannot race a drain it would then have to chase
     * into the account.
     */
    val queuedAt: Instant,
)

/**
 * FR-806's "visible to the user", as two numbers rather than one.
 *
 * A capture waiting for the network and a capture that will never be written are not the
 * same fact, and a single count would show them alike — which is the silent failure NFR-303
 * forbids, dressed up as a queue that simply never drains.
 */
data class QueueStatus(val waiting: Int, val givenUp: Int) {
    val total: Int get() = waiting + givenUp
    val isEmpty: Boolean get() = total == 0
}

enum class WriteOperation { CREATE, UPDATE, DELETE }

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
