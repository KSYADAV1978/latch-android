package com.latch.cards

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-1222's corpus, and its shortfall.
 *
 * **This test does not fail on an empty corpus, and that is deliberate.** The requirement is unmet
 * and is *recorded* as unmet — NFR-502's precedent, which asks for 300 real strings, has stood at
 * 113 for the life of the project, and is carried in the specification rather than by a red build
 * that everybody learns to ignore. What this file does is make the shortfall visible, hold every
 * card that *is* here to its expected reading, and stop the count going backwards.
 *
 * **The corpus itself is not in this repository** (SRS 1.155). Every row is a card somebody
 * actually handed over, carrying a real person's name, direct line and work address — third
 * parties who consented to none of it, about data this product's own sheet calls theirs. So the
 * file is git-ignored and lives only on the developer's machine, and `card_corpus.tsv.example`
 * beside it shows the format with invented rows that must never be counted.
 *
 * **Absent, these tests report SKIPPED and never PASSED**, which is why `:cards` takes the junit5
 * artifact: CLAUDE.md records the rule the expensive way — *an inconclusive run looks exactly like
 * a pass in a log* — and a corpus test that quietly went green with no corpus would be the purest
 * form of that. The shortfall is carried in `docs/RELEASE.md` under **Knowingly unmet at
 * submission**, where it is read whether or not anyone runs this.
 */
class CardCorpusTest {

    private data class Case(
        val name: String,
        val lines: List<String>,
        val expectName: String?,
        val expectTitle: String?,
        val expectOrg: String?,
        val expectPhones: List<String>,
        val expectEmails: List<String>,
        val expectAddress: String?,
    )

    /**
     * Whether the developer's corpus is on this machine.
     *
     * A clone has none, and that is the ordinary case now rather than the broken one.
     */
    private val corpusPresent: Boolean
        get() = javaClass.getResourceAsStream("/cards/card_corpus.tsv") != null

    private val cases: List<Case> by lazy {
        val stream = javaClass.getResourceAsStream("/cards/card_corpus.tsv") ?: return@lazy emptyList()
        stream.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { line ->
                val f = line.split("\t")
                check(f.size == 8) { "malformed corpus row: $line" }
                Case(
                    name = f[0],
                    // Separated by a literal backslash-n, as `card_vectors.tsv` does — a pipe collided
                    // with a real card whose title was "Director | Business Development - India".
                    // The two characters backslash-n, not a real newline: a TSV row is one line.
                    lines = f[1].split(LINE_ESCAPE).map { unescape(it).trim() },
                    expectName = f[2].takeIf { it.isNotBlank() },
                    expectTitle = f[3].takeIf { it.isNotBlank() },
                    expectOrg = f[4].takeIf { it.isNotBlank() },
                    expectPhones = f[5].split(";").map { it.trim() }.filter { it.isNotBlank() },
                    expectEmails = f[6].split(";").map { it.trim() }.filter { it.isNotBlank() },
                    expectAddress = f[7].takeIf { it.isNotBlank() },
                )
            }
    }

    /**
     * Which (row, field) pairs the classifier is known not to get right yet.
     *
     * **The expectations above are never bent to match the code.** CLAUDE.md's own corollary is
     * that a test can pin behaviour exactly and pin the *wrong* behaviour, and an expectation
     * edited until it goes green is the purest form of it. So a card the classifier fails keeps
     * its true expectation and is listed here instead, which keeps the build green without
     * telling a lie - the shortfall moves from the assertion into the record, exactly as
     * FR-1222's own count already does.
     */
    private val knownMisses: Set<Pair<String, String>> by lazy {
        val stream = javaClass.getResourceAsStream("/cards/card_corpus.tsv") ?: return@lazy emptySet()
        stream.bufferedReader().readLines()
            .mapNotNull { KNOWN_MISS.matchEntire(it.trim()) }
            .map { it.groupValues[1] to it.groupValues[2] }
            .toSet()
    }

    private fun expect(case: Case, field: String, expected: Any?, actual: Any?) {
        if (case.name to field in knownMisses) return
        assertEquals(expected, actual, "$field for ${case.name}")
    }

    @Test
    fun `every card in the corpus reads as expected`() {
        assumeTrue(corpusPresent, CORPUS_ABSENT)
        cases.forEach { case ->
            val result = classifyCard(case.lines)
            expect(case, "name", case.expectName, result.draft.displayName)
            expect(case, "title", case.expectTitle, result.draft.jobTitle)
            expect(case, "org", case.expectOrg, result.draft.organisation)
            expect(case, "phones", case.expectPhones, result.draft.phones.map { it.number })
            expect(case, "emails", case.expectEmails, result.draft.emails.map { it.address })
            // **The corpus was blind to addresses until SRS 1.115**, and card nine passed
            // with its address missing entirely — the rule had been built two cards
            // earlier and nothing had ever asserted it. A corpus that omits a field
            // tests everything except the field most recently added, which is the one
            // most likely to be wrong.
            expect(case, "address", case.expectAddress, result.draft.addresses.firstOrNull())
        }
    }

    /**
     * A known miss that has started passing must be struck off, and the build says so.
     *
     * **This is the ratchet, and without it the list is a place defects go to be forgotten.** A
     * rule that fixes one of these turns the build red until the line is removed, so a fix cannot
     * land without being counted - and the count is the only honest measure of whether the
     * classifier is getting better. It also catches the opposite mistake: a line left behind for
     * a card that was always passing, which would quietly excuse a field from being tested at all.
     */
    @Test
    fun `a known miss that now passes is struck from the list`() {
        assumeTrue(corpusPresent, CORPUS_ABSENT)
        val byName = cases.associateBy { it.name }
        val fixed = knownMisses.filter { (row, field) ->
            val case = byName[row] ?: return@filter false
            val d = classifyCard(case.lines).draft
            when (field) {
                "name" -> case.expectName == d.displayName
                "title" -> case.expectTitle == d.jobTitle
                "org" -> case.expectOrg == d.organisation
                "phones" -> case.expectPhones == d.phones.map { it.number }
                "emails" -> case.expectEmails == d.emails.map { it.address }
                "address" -> case.expectAddress == d.addresses.firstOrNull()
                else -> false
            }
        }
        assertTrue(
            fixed.isEmpty(),
            "these known misses now pass and must be removed from card_corpus.tsv: $fixed",
        )
    }

    @Test
    fun `the known-miss list only shrinks`() {
        assumeTrue(corpusPresent, CORPUS_ABSENT)
        assertTrue(
            knownMisses.size <= KNOWN_MISS_CEILING,
            "the classifier now fails ${knownMisses.size} fields, above the recorded " +
                "ceiling of $KNOWN_MISS_CEILING. A rule has regressed, or a new card found a " +
                "new defect - either way it is a decision and not a number to raise quietly.",
        )
        println("FR-1204: ${knownMisses.size} known field misses across ${cases.size} recognitions.")
    }

    /**
     * `\uXXXX` becomes the character it names.
     *
     * **The corpus is kept in ASCII on purpose** — the same reason `hash_vectors.tsv` is — so that
     * an editor, a diff tool or a copy-paste cannot normalise a codepoint away. That matters most
     * for the one row whose entire subject is two invisible characters: a `U+0902` combining mark
     * inside a Latin word, which is the recogniser bleed SRS 1.126 repairs. Written literally it
     * would be unreviewable in a diff and one careless save from vanishing.
     */
    private fun unescape(raw: String): String = UNICODE_ESCAPE.replace(raw) { match ->
        match.groupValues[1].toInt(16).toChar().toString()
    }

    @Test
    fun `the corpus does not shrink`() {
        assumeTrue(corpusPresent, CORPUS_ABSENT)
        // The floor rises as real cards arrive. It exists so that a card cannot be quietly removed
        // when it starts failing, which is the cheapest way to make a corpus lie.
        assertTrue(
            cases.size >= CORPUS_FLOOR,
            "the corpus has ${cases.size} cards, below the recorded floor of $CORPUS_FLOOR",
        )
    }

    @Test
    fun `the shortfall against FR-1222 is stated rather than hidden`() {
        assumeTrue(corpusPresent, CORPUS_ABSENT)
        // Not an assertion about the code: an assertion about the record. If this ever reads
        // "met", the requirement is met and this test should be deleted along with the note in
        // the SRS.
        //
        // **Counted in cards and not in rows** (SRS 1.126). A row is one *recognition*, and one
        // card can supply two: SRS 1.123 recorded the same card photographed twice reading
        // differently, and the second reading earns a row because it exercises the classifier
        // against text it will genuinely see. It does not earn a card. Letting it would be the
        // corpus flattering itself, which is the one thing FR-1222's own wording forbids —
        // "cards invented for the corpus shall not count", and a duplicate reading is nearer to
        // an invented card than to a real one for counting purposes.
        val required = 120
        val short = required - distinctCards
        assertTrue(short >= 0)
        println(
            "FR-1222: $distinctCards of $required real cards " +
                "(${cases.size} recognitions). $short still owed by the developer."
        )
    }

    /** Rows sharing a name before `#` are readings of one card. See the note above. */
    private val distinctCards: Int get() = cases.map { it.name.substringBefore('#') }.distinct().size

    private companion object {
        /** Raise this as rows are added; never lower it. It guards the corpus against shrinking. */
        const val CORPUS_FLOOR = 52

        /**
         * How many (row, field) pairs the classifier is allowed to get wrong.
         *
         * Lower it whenever a rule fixes one; never raise it to make a build pass. Raising it is
         * the same act as editing an expectation, one level up.
         */
        const val KNOWN_MISS_CEILING = 10

        val KNOWN_MISS = Regex("""# known-miss (\S+) (\w+)""")

        private val UNICODE_ESCAPE = Regex("""\\u([0-9A-Fa-f]{4})""")

        /**
         * The separator between a card's lines: a backslash followed by `n`, **not** a newline.
         *
         * A pipe was tried first and collided with a real card whose job title is
         * "Director | Business Development - India" — the separator appearing inside the content
         * it separated, which split one field into two and failed the row for a reason that had
         * nothing to do with the classifier.
         */
        val LINE_ESCAPE = "" + '\\' + 'n'
    }
}

/**
 * Why a skipped corpus test is skipped, said in the report rather than left to be guessed.
 *
 * It names the file and the reason it is not here, because the first person to see three SKIPPED
 * rows in this class will otherwise read them as a broken build.
 */
private const val CORPUS_ABSENT: String =
    "cards/src/test/resources/cards/card_corpus.tsv is not on this machine. It holds real people's " +
        "names, direct lines and work addresses off cards they handed over, so it is git-ignored " +
        "and never published (SRS 1.155). See card_corpus.tsv.example for the format."
