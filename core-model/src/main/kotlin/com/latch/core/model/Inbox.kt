package com.latch.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Why a capture is in the Inbox rather than in the user's Google account.
 *
 * NFR-402: named, not phrased. Each client supplies the sentence, the same division
 * `SaveFailure` and `OcrFailure` already keep — and here it matters more than usual, because
 * the reason is what tells the user what they have to *do* with the row.
 *
 * **This is in `:core-model` rather than in a client's storage layer**, because two clients now
 * route captures and §7.1's `Capture` carries `state (inbox / saved / discarded)` as a domain
 * fact rather than as an Android one. What is *not* here is any store: FR-701 says the Inbox is
 * local, and each client keeps it the way its platform keeps things — SQLite on the phone, a
 * DPAPI-encrypted file on Windows. The shape and the rules are shared; the disk is not.
 */
enum class InboxReason {
    /** FR-506 row 4, AC-03: no date and no time were found at all. Design principle 1 forbids inventing one. */
    UNDATED,

    /** FR-512: the parse came in below the configured confidence threshold. */
    LOW_CONFIDENCE,

    /** FR-506 row 3: a time with no day. It needs a date before it can be anything. */
    INCOMPLETE,

    /**
     * FR-804, offline. The local index says an item with this `latch.item_key` already stands
     * at a different date, so this capture looks like a reschedule — and FR-804 forbids
     * applying one without asking. There was no network to ask against, and a drain has no
     * user to ask, so the question waits here.
     *
     * This is the cure FR-804's own note names: "an offline reschedule therefore becomes a
     * second item, and the recourse is to remove one by hand… **The Inbox is the cure**."
     */
    RESCHEDULE_UNRESOLVED,
}

/**
 * One capture held locally (FR-701 to FR-705). Nothing here has reached Google.
 *
 * **It stores the text and not the parse.** The parse is redone when the row is opened — which
 * is what kept `:data` free of `:parser` when this type lived there, and is now what lets two
 * clients hold the same row and read it the same way.
 *
 * **And it stores the instant and zone the capture was made in**, which is the load-bearing
 * half of that. Re-parsing against today's clock would resolve "kal" one day further every
 * time the user looked at the list, and "next Monday" would walk forward a week at a time.
 * FR-515 makes a parse a pure function of the text and its context; carrying the context is
 * what makes re-parsing reproduce the reading the user was shown, rather than a new one. It is
 * design principle 1's failure inverted — not inventing a date, but quietly moving one.
 */
data class InboxCapture(
    val id: String,
    /**
     * The captured text.
     *
     * **Never a notification's.** NFR-206 forbids notification content reaching persistent
     * storage and the Inbox is exactly that, so a capture from that layer is not routed here
     * at all — see [CaptureSource.routableToInbox], which is the structural form of the rule.
     */
    val rawText: String,
    val layer: CaptureLayer,
    val appId: String? = null,
    /** FR-206's subject line, where the sending app gave one. */
    val preferredTitle: String? = null,
    val ocrUsed: Boolean = false,
    val capturedAt: Instant,
    /** The parser's `now` at capture. See this class's note on why it is stored. */
    val capturedLocal: LocalDateTime,
    /** An IANA zone id — the parser's `zone` at capture, stored for the same reason. */
    val zone: String,
    /** FR-505's overall confidence, so FR-512's reason can be shown rather than asserted. */
    val confidence: Double,
    val reason: InboxReason,
    /**
     * FR-702: a date the user assigned during triage. Applied over the parse when the row is
     * saved, so an undated capture becomes a dated item without the parser having invented
     * anything.
     */
    val assignedDate: LocalDate? = null,
    /** FR-702: edited title, where the user gave one. Wins over FR-509 and FR-509a. */
    val editedTitle: String? = null,
    /**
     * FR-507: the Event/Task override, where the user took one here.
     *
     * FR-507 says "before saving", and an Inbox row is saved from the Inbox — so the control
     * has to exist on both surfaces. Stored rather than applied, for the reason the assigned
     * date is: the row holds the text and the parse is redone from it, so an edit that was
     * applied to the parse would not survive the next read.
     */
    val typeOverride: ItemType? = null,
    /** FR-702's snooze: out of the count and the list until this instant. */
    val snoozedUntil: Instant? = null,
    val state: CaptureState = CaptureState.INBOX,
) {
    /** FR-704's count and FR-702's list both exclude a snoozed row. */
    fun isDue(now: Instant): Boolean = snoozedUntil == null || !snoozedUntil.isAfter(now)
}

/**
 * FR-705: rows old enough to be worth putting in front of the user again.
 *
 * **Surfaced, never deleted**, which is the requirement's own word and the whole of its point:
 * a capture the user has not dealt with is still a capture, and an app that tidied it away
 * would be losing what it was built to keep (design principle 1). Pure, so the period is a
 * parameter and the rule is testable without a clock.
 */
fun agedCaptures(
    captures: List<InboxCapture>,
    now: Instant,
    after: Duration = INBOX_REVIEW_AFTER,
): List<InboxCapture> = captures.filter { capture ->
    capture.isDue(now) && !capture.capturedAt.plus(after).isAfter(now)
}

/** FR-705's configurable period. A fortnight is long enough not to nag (FR-704) and short enough to matter. */
val INBOX_REVIEW_AFTER: Duration = Duration.ofDays(14)
