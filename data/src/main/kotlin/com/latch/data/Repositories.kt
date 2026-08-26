package com.latch.data

import com.latch.core.model.Capture
import com.latch.core.model.Item

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
    suspend fun enqueue(item: Item)
    suspend fun pending(): List<QueuedWrite>
    suspend fun markWritten(queueId: String, remoteId: String)

    /** NFR-303: a failure surfaces a clear, actionable message. Silent failure is a defect. */
    suspend fun markFailed(queueId: String, error: String)
}

data class QueuedWrite(
    val id: String,
    val item: Item,
    val operation: WriteOperation,
    val attempts: Int,
    val lastError: String?,
)

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
