package com.latch.desktop.ui

import com.latch.core.model.InboxCapture
import com.latch.core.model.InboxReason
import com.latch.desktop.inbox.InboxStatus
import com.latch.core.model.ItemType
import com.latch.core.model.agedCaptures
import com.latch.desktop.capture.DesktopCapture
import com.latch.wire.canOverrideTo
import com.latch.wire.candidateBlocker
import com.latch.wire.parseOf
import com.latch.wire.titleFor
import com.latch.wire.typeChangeCost
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One row of the Inbox window: what FR-702 lets the user do, and why the row is here. */
data class InboxRow(
    val id: String,
    val title: String,
    val badge: String,
    val whenLine: String,
    /** FR-512, FR-506 rows 3 and 4: why this is here rather than in the account. */
    val reason: String,
    val capturedLine: String,
    /** FR-705: surfaced for review, never deleted. Null for a row that is not old yet. */
    val agedLine: String?,
    /** FR-506 row 3: the row still needs a date before anything can be written. */
    val needsDate: Boolean,
    /** FR-702: whether Save would produce anything. A row with nothing dateable would not. */
    val canSave: Boolean,
    val canOverride: Boolean,
    val otherType: ItemType,
    /** §8.1's cost of pressing the badge, read **before** the press. */
    val overrideCost: String?,
    /** The first line of what was captured, so a row is recognisable without opening it. */
    val excerpt: String,
)

/** Everything the Inbox window draws, decided here so the window only renders. */
data class InboxModel(
    val rows: List<InboxRow>,
    /** Shown in place of the list when there is nothing waiting. FR-704: no nagging. */
    val emptyLine: String?,
    /** FR-703, said out loud: nothing in this list has reached Google. */
    val localOnlyLine: String,
    /** Rows this build could not read. Kept on disk; named here so they are not a silent gap. */
    val unreadableLine: String?,
)

/**
 * The Inbox, as a pure function of what is stored.
 *
 * The same discipline as `popupModel`: a screen that decides anything is a screen no test can
 * check. Every sentence, every badge and every enabled control below is settled here, and
 * `InboxWindow` renders the result.
 *
 * **Each row is re-parsed against its own captured instant**, through `parseOf`, which is the
 * whole reason the row stores one. Parsing against today would walk "kal" a day further every
 * time this window was opened.
 */
fun inboxModel(
    captures: List<InboxCapture>,
    now: Instant,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    unreadable: Int = 0,
): InboxModel {
    val aged = agedCaptures(captures, now).map { it.id }.toSet()
    val rows = captures.map { capture -> rowFor(capture, today, zone, capture.id in aged) }
    return InboxModel(
        rows = rows,
        emptyLine = if (rows.isEmpty()) DesktopStrings.INBOX_EMPTY else null,
        localOnlyLine = DesktopStrings.INBOX_LOCAL_ONLY,
        unreadableLine = if (unreadable == 0) null else {
            DesktopStrings.INBOX_UNREADABLE.replace("%d", unreadable.toString())
        },
    )
}

private fun rowFor(
    capture: InboxCapture,
    today: LocalDate,
    zone: ZoneId,
    isAged: Boolean,
): InboxRow {
    val parsed = parseOf(capture, today = today)
    val candidate = parsed.primary ?: parsed.candidates.firstOrNull()
    val wire = DesktopCapture(capture.rawText, capture.preferredTitle, capture.ocrUsed)
    val type = candidate?.classification?.itemType ?: ItemType.TASK
    val other = if (type == ItemType.EVENT) ItemType.TASK else ItemType.EVENT

    return InboxRow(
        id = capture.id,
        // FR-702's edited title wins, as FR-206's subject does — and it travels as FR-509b's
        // override rather than as `preferredTitle`, which would have moved §7.2's item key.
        title = capture.editedTitle?.takeIf { it.isNotBlank() }
            ?: candidate?.let { titleFor(wire, parsed, it, null) }
            ?: parsed.title.value,
        badge = if (type == ItemType.EVENT) DesktopStrings.BADGE_EVENT else DesktopStrings.BADGE_TASK,
        // A row with neither a date nor a time gets its own line rather than the popup's:
        // "No day for this time yet" names a time this capture has not got, which reads as
        // though the parser found one and lost it.
        whenLine = when {
            candidate == null -> DesktopStrings.INBOX_NO_DATE_YET
            candidate.date == null && candidate.time == null -> DesktopStrings.INBOX_NO_DATE_YET
            else -> whenLineFor(candidate)
        },
        reason = reasonText(capture.reason),
        capturedLine = DesktopStrings.INBOX_CAPTURED_ON
            .replace("%s", CAPTURED.format(capture.capturedAt.atZone(zone))),
        agedLine = if (!isAged) null else {
            DesktopStrings.INBOX_AGED.replace("%s", CAPTURED.format(capture.capturedAt.atZone(zone)))
        },
        needsDate = candidate == null || candidate.date == null,
        // A row whose every candidate is blocked would draft nothing, so Save is refused and
        // the picker beside it is what unblocks it. The same rule `draftItems` applies, asked
        // one screen earlier so the button can say so rather than fail on the press.
        canSave = parsed.candidates.any { candidateBlocker(it) == null },
        canOverride = candidate != null && canOverrideTo(candidate, other),
        otherType = other,
        overrideCost = candidate?.let { typeChangeCost(it, other) }?.let(::overrideCostText),
        excerpt = excerptOf(capture.rawText),
    )
}

/** NFR-402: `InboxReason` is a named fact in `:core-model`; the sentence is this client's. */
internal fun reasonText(reason: InboxReason): String = when (reason) {
    InboxReason.UNDATED -> DesktopStrings.REASON_UNDATED
    InboxReason.LOW_CONFIDENCE -> DesktopStrings.REASON_LOW_CONFIDENCE
    InboxReason.INCOMPLETE -> DesktopStrings.REASON_INCOMPLETE
    InboxReason.RESCHEDULE_UNRESOLVED -> DesktopStrings.REASON_RESCHEDULE
}

/**
 * FR-704: an unobtrusive count, and **nothing at all at zero**.
 *
 * Null rather than "0 waiting", which is the requirement's "shall not nag" in the one place it
 * can be got wrong for free: a permanent menu entry saying there is nothing to do is a
 * reminder about an empty list.
 */
fun inboxCountLabel(status: InboxStatus): String? = when {
    status.due == 0 -> null
    status.due == 1 -> DesktopStrings.INBOX_ONE_WAITING
    else -> DesktopStrings.INBOX_MANY_WAITING.replace("%d", status.due.toString())
}

/**
 * One line of the captured text, so a row is recognisable in a list without being opened.
 *
 * Bounded, because a capture can be an entire recognised screen (FR-805b's own case) and a
 * window that grew to fit one would be unreadable for the rest.
 */
internal fun excerptOf(text: String): String {
    val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length <= EXCERPT) line else line.take(EXCERPT).trimEnd() + "…"
}

private const val EXCERPT = 90

private val CAPTURED: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
