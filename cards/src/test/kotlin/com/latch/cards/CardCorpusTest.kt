package com.latch.cards

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

    private val cases: List<Case> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/cards/card_corpus.tsv")) {
            "card_corpus.tsv is missing; the shortfall must stay visible rather than vanish"
        }
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
                    lines = f[1].split(LINE_ESCAPE).map { it.trim() },
                    expectName = f[2].takeIf { it.isNotBlank() },
                    expectTitle = f[3].takeIf { it.isNotBlank() },
                    expectOrg = f[4].takeIf { it.isNotBlank() },
                    expectPhones = f[5].split(";").map { it.trim() }.filter { it.isNotBlank() },
                    expectEmails = f[6].split(";").map { it.trim() }.filter { it.isNotBlank() },
                    expectAddress = f[7].takeIf { it.isNotBlank() },
                )
            }
    }

    @Test
    fun `every card in the corpus reads as expected`() {
        cases.forEach { case ->
            val result = classifyCard(case.lines)
            assertEquals(case.expectName, result.draft.displayName, "name for ${case.name}")
            assertEquals(case.expectTitle, result.draft.jobTitle, "title for ${case.name}")
            assertEquals(case.expectOrg, result.draft.organisation, "org for ${case.name}")
            assertEquals(case.expectPhones, result.draft.phones.map { it.number }, "phones for ${case.name}")
            assertEquals(case.expectEmails, result.draft.emails.map { it.address }, "emails for ${case.name}")
            // **The corpus was blind to addresses until SRS 1.115**, and card nine passed
            // with its address missing entirely — the rule had been built two cards
            // earlier and nothing had ever asserted it. A corpus that omits a field
            // tests everything except the field most recently added, which is the one
            // most likely to be wrong.
            assertEquals(
                case.expectAddress,
                result.draft.addresses.firstOrNull(),
                "address for ${case.name}",
            )
        }
    }

    @Test
    fun `the corpus does not shrink`() {
        // The floor rises as real cards arrive. It exists so that a card cannot be quietly removed
        // when it starts failing, which is the cheapest way to make a corpus lie.
        assertTrue(
            cases.size >= CORPUS_FLOOR,
            "the corpus has ${cases.size} cards, below the recorded floor of $CORPUS_FLOOR",
        )
    }

    @Test
    fun `the shortfall against FR-1222 is stated rather than hidden`() {
        // Not an assertion about the code: an assertion about the record. If this ever reads
        // "met", the requirement is met and this test should be deleted along with the note in
        // the SRS.
        val required = 120
        val short = required - cases.size
        assertTrue(short >= 0)
        println("FR-1222: ${cases.size} of $required real cards. $short still owed by the developer.")
    }

    private companion object {
        /** Raise this as real cards are added; never lower it. */
        const val CORPUS_FLOOR = 10

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
