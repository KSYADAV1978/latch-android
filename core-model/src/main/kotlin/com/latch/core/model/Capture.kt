package com.latch.core.model

import java.time.Instant

/**
 * One user capture gesture (SRS §7.1). [rawText] is the text as captured; for OCR-derived
 * captures it is the extracted text — the source image is never retained (FR-216, FR-1004a).
 */
data class Capture(
    val id: String,
    val rawText: String,
    val source: CaptureSource,
    val sourceUri: String? = null,
    val capturedAt: Instant,
    val ocrUsed: Boolean = false,
    val confidence: Double,
    val state: CaptureState = CaptureState.INBOX,
)

enum class CaptureState { INBOX, SAVED, DISCARDED }

/**
 * Which capture layer produced this (FR-200 series). [NOTIFICATION] is load-bearing:
 * FR-210a suppresses webhook delivery for anything originating from that layer, and
 * FR-210 forbids writing its content to disk at all.
 */
enum class CaptureLayer { TEXT_SELECTION, SHARE_SHEET, NOTIFICATION, QUICK_TILE, SCREENSHOT_WATCHER }

data class CaptureSource(
    val layer: CaptureLayer,
    /** Package name of the originating app, where the platform discloses it (FR-802). */
    val appId: String? = null,
) {
    /** FR-210a. */
    val webhookEligible: Boolean get() = layer != CaptureLayer.NOTIFICATION

    /**
     * FR-805a. The source text of a notification is never stored in the created item.
     *
     * The sibling of [webhookEligible], and excluded for the same reason: a Google item is
     * persistent storage, synchronised to every device on the account, and NFR-206 forbids
     * notification content reaching it. The item's title and date still go — FR-210 sanctions
     * the confirmed item — but the message itself does not.
     */
    val storesSourceText: Boolean get() = layer != CaptureLayer.NOTIFICATION
}
