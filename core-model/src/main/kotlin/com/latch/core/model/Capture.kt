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
 *
 * [CAMERA] is FR-1201a's photograph, and it is deliberately **not** one of FR-1003's
 * switchable layers (SRS 1.119). §5.2's layers intercept content the user is looking at
 * somewhere else; a button inside Latch is not one of them, so [CAMERA] is absent from
 * `DEFAULT_LAYERS` and from the Settings list, and the capture path does not gate on it.
 * It exists so that FR-1207's `latch.card.layer` records the surface a contact came from
 * rather than borrowing the share sheet's name for a photograph.
 */
enum class CaptureLayer {
    TEXT_SELECTION,
    SHARE_SHEET,
    NOTIFICATION,
    QUICK_TILE,
    SCREENSHOT_WATCHER,
    CAMERA,
}

data class CaptureSource(
    val layer: CaptureLayer,
    /** Package name of the originating app, where the platform discloses it (FR-802). */
    val appId: String? = null,
    /**
     * FR-215: this text was recognised from an image or a PDF rather than read as text.
     *
     * Not a [CaptureLayer]. An image can arrive through the share sheet today and through
     * the screenshot watcher (FR-219) later, so how the text was *obtained* is a different
     * axis from which layer delivered it — the same reason §7.1's `Capture` carries
     * `ocr_used` beside `source` rather than folded into it.
     */
    val ocrUsed: Boolean = false,
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

    /**
     * FR-805b. An OCR-derived capture stores a **capped extract** around the matched dates,
     * never the whole recognised text.
     *
     * The third of these three properties, and they compose in the one direction that
     * matters: [storesSourceText] decides whether there is any text at all, and this decides
     * how much. A notification capture that was somehow also OCR-derived stores nothing,
     * because an extract of nothing is nothing.
     *
     * The reason is privacy rather than length. What a recogniser returns from a shared
     * screenshot is everything that was on the screen — the messages above and below the one
     * that mattered, whatever sat in the status area — and writing that into a calendar
     * description would synchronise a screen dump to every device on the account. Design
     * principle 2 confines what leaves the device to the finished entry, and a screen dump is
     * not one.
     */
    val storesWholeSourceText: Boolean get() = storesSourceText && !ocrUsed

    /**
     * FR-701, NFR-206. Whether this capture may be held in the Capture Inbox.
     *
     * The fourth of these properties and the third appearance of one rule: the Inbox is
     * persistent storage, NFR-206 forbids notification content reaching persistent storage,
     * and [storesSourceText] and [webhookEligible] already exclude the same layer from the
     * item description and from webhook delivery for the same reason.
     *
     * **The consequence is a narrowing, not a deferral.** FR-512 routes a below-threshold
     * capture to the Inbox; for this layer there is nowhere to route it, so its interim
     * reading — the confidence is shown and the save is not blocked — survives here
     * permanently. The alternative would be to refuse the save, which loses the capture, and
     * design principle 1 puts that above everything else.
     */
    val routableToInbox: Boolean get() = layer != CaptureLayer.NOTIFICATION
}
