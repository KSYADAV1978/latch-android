package com.latch.parser

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A relative word and the explicit date that glosses it are one commitment.
 *
 * **These assert the number of candidates, and that is the point of the file.** `CorpusTest`
 * checks only `result.primary` — its classification, date and time — so a rule that merged two
 * genuine commitments into one would usually leave the primary unchanged and pass the whole
 * corpus in silence. The corpus is no protection against over-merging, and this is.
 *
 * The rule mirrors the corroborating-weekday rule that was already here, with one asymmetry
 * recorded in `DateParser`: a weekday can also be accepted on *agreement* with the date's own
 * day of the week, and a relative word cannot — agreement is the case where the answer does
 * not matter, and disagreement is the case the rule exists for. Structure carries it alone.
 */
class RelativeAndExplicitTest {

    // A Tuesday, matching the corpus clock.
    private val context = ParseContext(now = LocalDateTime.of(2026, 8, 25, 9, 0))

    private fun candidates(text: String) = DateParser.parse(text, context).candidates

    // ---- the case this was built for ---------------------------------------------------------

    @Test
    fun `a parenthetical date glossing a relative word is one commitment`() {
        val found = candidates("meeting today (2 September, 2026) at 5.30 PM")
        assertEquals(1, found.size, found.map { it.date?.value }.toString())
        // The explicit date wins. The relative word is the stale half — the author wrote it on
        // the day, the reader opened it later — and the writer committed to the date.
        assertEquals("2026-09-02", found.single().date?.value.toString())
        assertEquals("17:30", found.single().time?.value.toString())
    }

    @Test
    fun `the relative word is absorbed into the date's span, brackets and all`() {
        // Two things turn on the span. FR-504 shows it back to the user, and §7.2's
        // `latch.item_key` is the title with it blanked — so a span that stopped short would
        // leave "today" in the identity, and the item would stop matching itself the moment
        // the meeting moved. Which is the one thing FR-804 needs it not to do.
        val text = "meeting today (2 September, 2026) at 5.30 PM"
        val span = candidates(text).single().date!!.span!!
        assertEquals("today (2 September, 2026)", text.substring(span.first, span.last + 1))
    }

    @Test
    fun `an adjacent date with no brackets merges too`() {
        val found = candidates("meeting today 2 September 2026")
        assertEquals(1, found.size, found.map { it.date?.value }.toString())
        assertEquals("2026-09-02", found.single().date?.value.toString())
    }

    @Test
    fun `the explicit date wins even when the relative word disagrees by months`() {
        // Disagreement is not a reason to doubt the merge; it is the reason the merge matters.
        val found = candidates("submit today (14 December 2026)")
        assertEquals(1, found.size)
        assertEquals("2026-12-14", found.single().date?.value.toString())
    }

    @Test
    fun `Hinglish relative words are absorbed on the same terms`() {
        // NFR-404. "kal" is a relative word like any other and the rule is not written in
        // English words, so this needs no separate case in the parser — only a test saying so.
        val found = candidates("meeting kal (2 September 2026) 4 baje")
        assertEquals(1, found.size, found.map { it.date?.value }.toString())
        assertEquals("2026-09-02", found.single().date?.value.toString())
    }

    // ---- where it must NOT merge -------------------------------------------------------------

    @Test
    fun `words between them mean two commitments`() {
        val found = candidates("meeting today and again on 12 September 2026")
        assertEquals(2, found.size, found.map { it.date?.value }.toString())
    }

    @Test
    fun `a comma is too weak to merge on, and that is deliberate`() {
        // "Call me today, 5 September deadline" is two commitments. A comma admits it, so a
        // comma is not a separator for relative words — see RELATIVE_SEPARATORS. The cost is
        // that "today, 2 September" also stays two rows; an extra row is visible and FR-511
        // removes it, where a swallowed date is silent.
        assertEquals(2, candidates("Call me today, 5 September deadline").size)
        assertEquals(2, candidates("meeting today, 2 September 2026").size)
    }

    @Test
    fun `a date far from the relative word is a second commitment`() {
        val found = candidates("pay today, and the inspection is on 30 September 2026")
        assertEquals(2, found.size, found.map { it.date?.value }.toString())
    }

    @Test
    fun `a relative word alone is untouched`() {
        assertEquals(1, candidates("meeting today").size)
        assertEquals("2026-08-25", candidates("meeting today").single().date?.value.toString())
    }

    @Test
    fun `two explicit dates are never merged by this rule`() {
        val found = candidates("PTM on 14 September 2026. Fees due 20 September 2027.")
        assertEquals(2, found.size)
    }

    // ---- the weekday rule is unchanged --------------------------------------------------------

    @Test
    fun `a corroborating weekday still merges, including across a comma`() {
        // The asymmetry, asserted rather than described: a comma is a separator for weekdays
        // and not for relative words, and "Monday, 31 August" is why.
        assertEquals(1, candidates("PTM on Friday 12 September 2026").size)
        assertEquals(1, candidates("sync Monday, 31 August 2026").size)
    }

    @Test
    fun `a weekday that is a second commitment still stays two`() {
        assertEquals(2, candidates("gym Friday, and the review on 12 September 2026").size)
    }

    // ---- guard rows --------------------------------------------------------------------------

    @Test
    fun `a telephone number beside a relative word is still not a date`() {
        // The sender's extension in the capture that prompted all this sits two words from a
        // real date. Nothing claims it today; this says so out loud.
        val found = candidates("NITI Aayog, 23096829 today")
        assertEquals(1, found.size)
        assertEquals("2026-08-25", found.single().date?.value.toString())
    }

    @Test
    fun `the whole captured email yields one item`() {
        val text = "Dear Sir/Ma'am, In continuation of the trail mail, please find attached the " +
            "updated presentation for virtual meeting to be chaired by CEO, NITI Aayog today " +
            "(2 September, 2026) at 5.30 PM. Regards, Gitanjali Gupta, Additional Secretary, " +
            "NITI Aayog, 23096829"
        val found = candidates(text)
        assertEquals(1, found.size, found.map { it.date?.value }.toString())
        assertEquals("2026-09-02", found.single().date?.value.toString())
        assertEquals("17:30", found.single().time?.value.toString())
        assertTrue(found.single().classification.itemType == com.latch.core.model.ItemType.EVENT)
    }
}
