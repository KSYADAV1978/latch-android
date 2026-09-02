package com.latch.wire

import com.latch.parser.DatedCandidate
import com.latch.parser.ParseResult
import com.latch.parser.TitleExtractor

/*
 * FR-509, FR-509a, FR-509b and §7.2 step 2, in the module both clients compile.
 *
 * These functions were in `:app` until the Windows client of §4.1 was started, and moving them
 * is the whole reason `:wire` exists. §7.2 says the `item_key` clause "is the one part of §7.2
 * that depends on parser behaviour rather than on arithmetic over the text, and it is therefore
 * where three independently written clients are most likely to drift" — and this file *is* that
 * dependence. A second implementation of `titleFromLine` that took the row below rather than the
 * row above would derive a different key from the same screenshot, FR-804 would stop matching
 * across devices, and nothing would report an error: the phone would move an item and the desktop
 * would create a second one.
 *
 * Sharing the code is a stronger answer than the conformance vectors §7.2 asks for, because
 * vectors detect drift and shared code cannot drift. The vectors still exist and still run — they
 * pin the derivation against *change*, which is a different job from pinning two clients to each
 * other.
 */

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
fun itemKeyTitle(captured: WireCapture, result: ParseResult): String {
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
    captured: WireCapture,
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
