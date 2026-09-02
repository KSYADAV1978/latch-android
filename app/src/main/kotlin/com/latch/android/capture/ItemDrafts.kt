package com.latch.android.capture

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.AccountDefaults
import com.latch.parser.DatedCandidate
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import com.latch.parser.TitleExtractor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Turns what the parser read into the items a save will create.
 *
 * This lives in `:app` because it is the only module that sees both `:parser` and
 * `:core-model` — the parser deliberately depends on the domain types and not the reverse.
 * It is pure Kotlin and reads no clock, so it is tested on the JVM beside the setup reducer.
 *
 * `Classification` already carries the FR-506 type mapping; what it does not carry is how a
 * candidate's date and time become a start, an end or a due date, because §5.6's KDoc puts
 * that on the app: "the parser's job ends at saying which row applies."
 */

/** Why a capture cannot be saved, where it cannot. Named, not phrased — NFR-402. */
enum class DraftBlocker {
    /**
     * FR-506 row 3: a time with no date, and no date given for it yet.
     *
     * The requirement's answer is a date picker with suggestion chips, and the confirmation
     * sheet has one — so this is now a state the user can leave rather than a wall. It is also
     * left by FR-507's override (a task needs no day) and by the Inbox.
     */
    NEEDS_A_DATE,
}

/** Either the items a save would create, or the reason it cannot. */
sealed interface DraftResult {
    data class Ready(val items: List<Item>) : DraftResult
    data class Blocked(val reason: DraftBlocker) : DraftResult
}

/**
 * [captureId] and [chainId] are the caller's to mint, so this stays clock-free and
 * deterministic under test. Every item of one save shares the chain id — §2.4's chain is the
 * items produced by one save, which is what FR-807 undo groups by.
 *
 * [selected] is FR-511's answer: the candidates the user left ticked, by index into
 * [ParseResult.candidates]. Defaults to all of them, which is what the requirement asks the
 * checkboxes to start as, and is also what a caller with no list of its own should get.
 *
 * Blocked only where **nothing** is left to write. A candidate that cannot be completed —
 * FR-506 row 3, a time with no day — is dropped from the chain and its neighbours are still
 * saved (SRS 1.23); it is the caller's job to have said so on screen, and [candidateBlocker]
 * is what it asks.
 */
fun draftItems(
    captured: CapturedText,
    result: ParseResult,
    context: ParseContext,
    defaults: AccountDefaults,
    captureId: String,
    chainId: String,
    selected: Set<Int> = result.candidates.indices.toSet(),
    /**
     * FR-510: how the past date is worded in the follow-up's notes, as a format string taking
     * the date. Passed in because it is user-facing text and NFR-402 keeps that in
     * `strings.xml` — the same arrangement as `CaptureSaver`'s source-link template.
     */
    pastDateNoteTemplate: String = "",
    /**
     * FR-509b: titles the user corrected, by **candidate** index — which is not the same as the
     * position in the chain, since unticked and blocked rows are dropped before drafting. The
     * two are kept apart below for exactly that reason.
     */
    titleOverrides: Map<Int, String> = emptyMap(),
): DraftResult {
    val drafted = result.candidates
        .withIndex()
        .filter { (index, _) -> index in selected }
        .filter { (_, candidate) -> candidateBlocker(candidate) == null }

    if (drafted.isEmpty()) {
        // Everything the user chose is unsaveable, or they chose nothing. The first reason a
        // candidate could not be drafted is the one worth reporting; with an empty selection
        // there is none, and NEEDS_A_DATE is still the only blocker there is.
        val reason = result.candidates
            .filterIndexed { index, _ -> index in selected }
            .firstNotNullOfOrNull(::candidateBlocker)
            ?: DraftBlocker.NEEDS_A_DATE
        return DraftResult.Blocked(reason)
    }

    val items = drafted.mapIndexed { index, indexed ->
        val (candidateIndex, candidate) = indexed
        val title = titleFor(captured, result, candidate, titleOverrides[candidateIndex])

        // FR-510, and it outranks the classification rather than sitting beside it: "where the
        // only date found is in the past, the app shall **not** create a dated item. It shall
        // offer instead to create a follow-up, with the past date recorded in the notes."
        //
        // Applied per candidate rather than per capture (SRS 1.44). The requirement's "the only
        // date found" was written when a capture produced one item; with FR-511 a capture can
        // hold a past date beside a future one, and writing the past one as a dated item because
        // it was not the *only* date would be the outcome this exists to prevent.
        //
        // The follow-up is undated, because a follow-up needs a date only if the app picks one
        // and picking one is what design principle 1 forbids. It also outranks FR-507's
        // override: an Event is a dated item, so a past row cannot be made one.
        if (candidate.isPast && candidate.date != null) {
            return@mapIndexed Item(
                id = itemId(chainId, index),
                captureId = captureId,
                chainId = chainId,
                type = ItemType.TASK,
                title = title,
                dueDate = null,
                location = result.location?.value,
                notes = pastDateNoteTemplate
                    .takeIf { it.isNotBlank() }
                    ?.format(candidate.date!!.value.format(PAST_DATE_FORMAT)),
                taskListId = defaults.taskListId,
            )
        }

        when (candidate.classification.itemType) {
            ItemType.EVENT -> eventItem(
                candidate = candidate,
                date = requireNotNull(candidate.date).value,
                title = title,
                location = result.location?.value,
                context = context,
                defaults = defaults,
                captureId = captureId,
                chainId = chainId,
                index = index,
            )

            ItemType.TASK -> Item(
                id = itemId(chainId, index),
                captureId = captureId,
                chainId = chainId,
                type = ItemType.TASK,
                title = title,
                // A task never carries a start: Google Tasks discards the time (§9.1), and
                // Item's own init rejects one.
                dueDate = candidate.date?.value,
                location = result.location?.value,
                taskListId = defaults.taskListId,
            )
        }
    }

    return DraftResult.Ready(items)
}

private fun eventItem(
    candidate: DatedCandidate,
    date: LocalDate,
    title: String,
    location: String?,
    context: ParseContext,
    defaults: AccountDefaults,
    captureId: String,
    chainId: String,
    index: Int,
): Item {
    val time = candidate.time?.value

    // An EVENT_INCOMPLETE can still carry a date: classify() tests ambiguousRelative before
    // it tests for a date and a time, so "next week at 3" lands here with both. Without a
    // time there is nothing to put on a clock, and an all-day event is the honest shape.
    val allDay = time == null

    // SRS 1.23: a range carries its closing day, inclusive as the writer wrote it. Turning
    // that into what Google wants is this step's job, not the parser's.
    val spanEnd = candidate.endDate?.value

    val start = if (allDay) date.atStartOfDay() else LocalDateTime.of(date, time)
    val end = when {
        // Google reads an all-day end date as exclusive: a single day on the 5th ends on the
        // 6th, and 12–14 September ends on the 15th. EventWrite passes end through untouched
        // by design, so the day is added here — the one place it is added, for both shapes.
        allDay -> (spanEnd ?: date).plusDays(1).atStartOfDay()
        // A second time in the text wins over the default. An end at or before the start
        // means the range crossed midnight — "from 11 pm to 1 am" is one sitting, not an
        // event that finishes two hours before it begins. Only the day rolls; the clock time
        // is what the writer wrote, so nothing is invented (design principle 1).
        candidate.endTime != null -> {
            val endTime = candidate.endTime!!.value
            // A range of days says which day it ends on; only a bare time has to infer it.
            val endDay = spanEnd ?: if (endTime > time) date else date.plusDays(1)
            LocalDateTime.of(endDay, endTime)
        }
        // A span of days carrying a start time but no end time: it ends on the closing day,
        // at the time it started. Guessing a different clock time would be inventing one.
        spanEnd != null -> LocalDateTime.of(spanEnd, time)
        // FR-1001's default duration. Declared on ParseContext and, until now, read by
        // nothing — its KDoc says "applied when a start time is found but no end", and this
        // is the downstream that was meant to apply it.
        else -> start.plus(context.defaultEventDuration)
    }

    return Item(
        id = itemId(chainId, index),
        captureId = captureId,
        chainId = chainId,
        type = ItemType.EVENT,
        title = title,
        start = start,
        end = end,
        allDay = allDay,
        location = location,
        calendarId = defaults.destinationCalendarId,
    )
}

/**
 * FR-510's date, written the way a person reads one.
 *
 * ISO would be unambiguous and is the wrong choice here: this string goes into a note the user
 * reads in Google Tasks, not into a wire format, and §4.1's other clients never parse it back.
 */
private val PAST_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

/** Derived from the chain id so a redraft of the same save produces the same ids. */
private fun itemId(chainId: String, index: Int) = "$chainId#$index"

/**
 * The item's title with every date and time the parser matched cut out of it — the input to
 * §7.2's `latch.item_key`.
 *
 * **FR-509b's edit does not reach here, and must not.** §7.2 writes metadata once at insert and
 * forbids recomputing a key; if a corrected title moved it, fixing an OCR error would make the
 * item permanently unmatchable by FR-804 — a later reschedule would create a second item — and
 * two users correcting one garbled capture differently would derive different identities for the
 * same message. The key is a function of what was captured; the title is what is displayed.
 *
 * FR-804 detects a reschedule by "matching title and identifiers, different date", so the
 * identity it matches on has to survive the date changing. Hashing the title as displayed
 * does not: `TitleExtractor` implements FR-509, "use the selection verbatim if under 60
 * characters", so for a short capture the title **is** the whole text, dates included, and
 * `item_key` comes out byte-identical to `source_hash`. Both then change together on a
 * reschedule and FR-804 has nothing to match. That is what this removes.
 *
 * The spans are blanked rather than deleted. `Normalizer` is length-preserving exactly so
 * that a rule's reported span still indexes into the original text, and blanking keeps every
 * later span valid where deleting would shift them all — it also stops "sync 31 August at"
 * collapsing into "syncat". The hash normalisation collapses the leftover whitespace.
 *
 * A subject line (FR-206) is hashed as it stands: the spans index into the body, so they do
 * not apply to it. A subject carrying its own date is the residual case, and it is rare
 * enough to accept — a mail subject is normally the standing name of the thing.
 */
fun itemKeyTitle(captured: CapturedText, result: ParseResult): String {
    captured.preferredTitle?.takeIf { it.isNotBlank() }?.let { return it }

    return TitleExtractor.extract(captured.text.blankOut(dateSpans(result))).value
}

/**
 * Every span the parser matched as a date, a time or an end of either, in document order.
 *
 * Two callers, and they want it for opposite reasons: [itemKeyTitle] blanks these out to
 * derive §7.2's `latch.item_key`, and FR-805b's extract keeps the text *around* them. Shared
 * so the two cannot drift — a span one of them counted as a date and the other did not would
 * put a date into an identity that is supposed to be date-free, or excerpt around a date that
 * is not there.
 *
 * §7.2 step 2 blanks **every** date, time and end-time span, not merely the primary's. This
 * covered only the primary's until SRS 1.23, which was invisible while a capture produced one
 * item and wrong the moment it held two dates: a neighbour's date left standing in the title
 * moves the key whenever any date in the message changes, which is the failure `item_key`
 * exists to prevent. One capture, one key — so every item of a chain shares it, and FR-804
 * identifies what the message is about rather than which occurrence of it.
 */
fun dateSpans(result: ParseResult): List<IntRange> =
    result.candidates.flatMap { candidate ->
        listOfNotNull(
            candidate.date?.span,
            candidate.time?.span,
            candidate.endTime?.span,
            candidate.endDate?.span,
        )
    }

private fun String.blankOut(spans: List<IntRange>): String {
    if (spans.isEmpty()) return this
    val characters = toCharArray()
    spans.forEach { span ->
        val from = span.first.coerceAtLeast(0)
        val to = span.last.coerceAtMost(characters.lastIndex)
        for (index in from..to) characters[index] = ' '
    }
    return String(characters)
}

/**
 * Whether this capture can be saved at all, and why not where it cannot.
 *
 * Exposed so the confirmation screen can disable Save for the same reason the draft would
 * have refused it. One source of truth: a screen that decided separately would eventually
 * disagree with the mapping.
 */
fun draftBlocker(result: ParseResult): DraftBlocker? =
    // The capture as a whole is blocked only where every candidate is. One unsaveable date
    // among several stops itself and not its neighbours (SRS 1.23), and a Save button
    // disabled because of one row would be the all-or-nothing behaviour that reading ends.
    result.candidates.map(::candidateBlocker).let { blockers ->
        if (blockers.all { it != null }) blockers.firstOrNull() else null
    }

/**
 * Why this one candidate cannot be written, or null where it can.
 *
 * Per candidate rather than per capture since SRS 1.23: such a row is shown with its reason and
 * left untickable while the rest of the capture saves. Since v1.44 the sheet also offers the
 * date picker FR-506 row 3 describes, so the row can be completed in place rather than only
 * explained.
 */
fun candidateBlocker(candidate: DatedCandidate): DraftBlocker? {
    // FR-510: a past row is written as an undated follow-up whatever its classification says,
    // so it is never waiting on a date and must not be reported as blocked.
    if (candidate.isPast && candidate.date != null) return null
    val needsDate = candidate.classification.itemType == ItemType.EVENT && candidate.date == null
    return if (needsDate) DraftBlocker.NEEDS_A_DATE else null
}

/**
 * FR-509a: an OCR-derived item's title, taken from **the row carrying its own date**.
 *
 * FR-509 reaches for the writer's first words, and on typed text the opening of the capture is
 * exactly that. On a screenshot the opening is whatever sat at the top of the image — the app's
 * header, the contact's name, the first message in view — so the observed title was
 * `Sharma Ji online Paid the uniform bill, Rs 12,500 in total.`
 *
 * That is not merely ugly. FR-805b had already excluded the sender's name and the amount paid
 * from the **description** on the same capture, and this put both back into the **title**,
 * which is the field a list shows, a notification quotes and every device on the account syncs.
 * A rule that removes material from one field while another copies it into a more visible one
 * has protected nothing.
 *
 * The row is available because §7.2 requires OCR text to be assembled in geometric reading
 * order, one image row per line — the same property FR-805b's window rests on.
 *
 * **The fallback chain is part of the rule, because the interesting cases are all fallbacks.**
 * A date row is often only a date: `Fees due 20/09/2027` blanked of its span still says
 * `Fees due`, but `20/09/2027` alone says nothing. The row above is then the better answer —
 * in a chat it is who was speaking and about what. Both failing, FR-509's own derivation
 * stands, which is worse than either but never empty.
 *
 * **This does not touch `latch.item_key`.** §7.2's derivation still blanks every span in the
 * whole capture, so one capture yields one key and a chain shares it (v1.23). Only what the
 * user sees is per-item. Deriving a key per row was considered and rejected in SRS 1.41: it
 * would turn FR-804's tie-break from what a message is about into which occurrence of it, for
 * a gain that is entirely cosmetic.
 */
fun ocrTitleFor(
    text: String,
    candidate: DatedCandidate,
    allSpans: List<IntRange>,
    fallback: String,
): String {
    val anchor = candidate.date?.span ?: candidate.time?.span ?: return fallback
    val lines = lineRanges(text)
    val index = lines.indexOfFirst { anchor.first <= it.last && anchor.last >= it.first }
    if (index < 0) return fallback

    // The date's own row, with every span the parser matched inside it blanked out.
    titleFromLine(text, lines[index], allSpans)?.let { return it }
    // Then the row above: in a chat, who was speaking and what about.
    if (index > 0) titleFromLine(text, lines[index - 1], allSpans)?.let { return it }
    return fallback
}

/** A line's title, or null where blanking its dates leaves nothing a person could read. */
private fun titleFromLine(text: String, line: IntRange, allSpans: List<IntRange>): String? {
    val within = allSpans.filter { it.first <= line.last && it.last >= line.first }
    // A line's range runs to the newline that ends it, which for the final line is one past
    // the text. Kept that way so a span landing on the separator still matches its own row.
    val end = line.last.coerceAtMost(text.lastIndex)
    if (end < line.first) return null
    val blanked = text.substring(line.first, end + 1).let { row ->
        row.blankOut(within.map { (it.first - line.first)..(it.last - line.first) })
    }
    val title = tidyBlanked(TitleExtractor.extract(blanked).value)
    // A row that was only a date leaves punctuation and spaces. Requiring a letter is what
    // separates "Fees due" from "." — and a title of punctuation is worse than the fallback.
    return title.takeIf { it.any(Char::isLetter) }
}

/**
 * Repairs what blanking a date span leaves behind.
 *
 * `Yes. PTM on Monday 14 September 2026.` becomes `Yes. PTM on  .` once its span is blanked,
 * and that stray full stop reached a real title on a device. This closes the gap the date left
 * and drops the punctuation it was holding up.
 *
 * It is deliberately about repairing the blank and nothing more. A **dangling connective** —
 * `Trip from`, `Yes. PTM on` — is left standing, because removing one means a list of English
 * words, and NFR-404 has this app reading Hinglish while NFR-403 has the UI translated. That
 * residue is the same cosmetic limit `CLAUDE.md` already records for FR-509's truncation, and
 * it reads as an abbreviation rather than as a defect.
 */
private fun tidyBlanked(text: String): String = text
    // Punctuation the date was standing in front of, now orphaned by a run of spaces.
    .replace(Regex("""\s+([.,;:!?)\]])"""), "$1")
    .replace(Regex("""([(\[])\s+"""), "$1")
    .replace(Regex("""\s+"""), " ")
    .trim()
    .trim('.', ',', ';', ':', '-', '–', '—')
    .trim()

/** Where each line of [text] begins and ends, so a span can be mapped to its row. */
private fun lineRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = 0
    text.lines().forEach { line ->
        ranges += start..(start + line.length)
        start += line.length + 1
    }
    return ranges
}

/**
 * The title one candidate will be written under — **the single place that decides it**, so the
 * confirmation screen and the write cannot disagree.
 *
 * That is the whole reason this exists rather than the logic sitting inside `draftItems`.
 * FR-509a gives an OCR capture a title per row, and the screen was still drawing FR-509's
 * one-title-for-the-capture above the list: a user would have confirmed one thing and Google
 * would have received another, which is the kind of divergence a confirmation screen exists to
 * make impossible.
 */
fun titleFor(
    captured: CapturedText,
    result: ParseResult,
    candidate: DatedCandidate,
    /**
     * FR-509b: the title the user corrected, where they did.
     *
     * **It wins over everything, including FR-206's subject.** A user looking at the sheet has
     * seen what the app derived and has replaced it; nothing the app inferred outranks that.
     * Blank is treated as no override rather than as an empty title — a cleared field is a user
     * mid-edit, not a request for an item with no name.
     *
     * It reaches the item's **summary** and never `latch.item_key`: `itemKeyTitle` does not
     * consult this, deliberately, and §7.2's derivation is unchanged. See FR-509b.
     */
    editedTitle: String? = null,
): String {
    editedTitle?.takeIf { it.isNotBlank() }?.let { return it.trim() }

    // FR-206: a mail client's subject names the thing better than any row of a page, and a
    // shared PDF carries one. It wins over both FR-509 and FR-509a.
    val fallback = captured.preferredTitle?.takeIf { it.isNotBlank() } ?: result.title.value
    if (!captured.ocrUsed || !captured.preferredTitle.isNullOrBlank()) return fallback
    return ocrTitleFor(captured.text, candidate, dateSpans(result), fallback)
}
