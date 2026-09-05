package com.latch.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Which fields survive being photographed twice (SRS 1.131).
 *
 * **The evidence behind a decision about FR-1208's key, kept where it can be re-run.** The
 * developer asked whether the duplicate check could key on the name and the mobile number instead
 * of the recognised text, on the reasonable observation that those two seem to come back right.
 * They largely do — and the interesting part is which fields do *not*, because that is what
 * decides the answer.
 *
 * **Every pair here is two real photographs of one real card**, taken minutes apart on 5 September
 * 2026. This is not a corpus of cards and does not count toward FR-1222; it is a corpus of
 * *repeat readings*, which is a different measurement and the only one that can answer this.
 *
 * **Three pairs is a small sample and the decision says so.** What it can establish is that a
 * field is *unstable* — one counterexample does that — and it does establish exactly that for the
 * email and for the phone list. It cannot establish that a field is reliable.
 */
class CardFieldStabilityTest {

    private fun digits(s: String) = s.filter { it.isDigit() }

    /** JSW, card eleven, clean. */
    private val jswA = listOf(
        "Ramesh Kumar Bhagat",
        "Executive Vice President - Corporate Affairs",
        "SW Steel Limited",
        "Email: rameshkumar.bhagat@jsw. test Phone : + 91 11 4000 8600",
    )

    /** JSW again, card twelve — the reading with the Devanagari bleed in it (SRS 1.126). */
    private val jswB = listOf(
        "Ramesh Kumar Bhagaंt",
        "Executive Vice President -",
        "Corporate Affairs",
        "SWsteel Limited",
        "Email: rameshkumar.bhagat@jsvw.test Phone : + 91 1। 4000 8600",
    )

    /** Dalmia, first reading. */
    private val dalmiaA = listOf(
        "Arvind Bhandari", "Senior", "General Manager", "Corporate Affairs",
        "Bharat Limited", "Dalmia", "m +91 7000900582",
        "e bhandari.arvind @dalmiabharat.test",
    )

    /** Dalmia, second reading four minutes later. */
    private val dalmiaB = listOf(
        "Arvind Bhandari", "Dalmia", "Senior General Manager", "Corporate Affairs",
        "Bharat Limited", "m 91 7000900582", "e", "bhandari.arvind @dalmiabharat.test",
    )

    /**
     * Dalmia again, as a two-sided capture — so the back's landline and address join the set.
     *
     * Written out rather than derived from [dalmiaB], because the first attempt built it by
     * dropping two lines and appending, and left the mobile in twice. A fixture assembled by
     * arithmetic is a fixture nobody has read.
     */
    private val dalmiaBothSides = listOf(
        "Arvind Bhandari", "Dalmia", "Senior General Manager", "Corporate Affairs",
        "Bharat Limited", "m +91 7000900582", "e bhandari.arvind@dalmiabharat.test",
        "011 20000100",
        "11th & 12th Floor Hansalaya Building, 15,",
        "Barakhamba Road, New Delhi - 110001, Delhi.",
    )

    // ---- what is stable -----------------------------------------------------------------------

    @Test
    fun `the name survived every repeat reading`() {
        // Three for three, and it is the field the developer's question rested on. It is not proof
        // that the name is reliable — SRS 1.126 is a counterexample where a single stray codepoint
        // put "Corporate Affairs" in the name field — but it is why the question was worth asking.
        assertEquals(
            classifyCard(jswA).draft.displayName,
            classifyCard(jswB).draft.displayName,
        )
        assertEquals(
            classifyCard(dalmiaA).draft.displayName,
            classifyCard(dalmiaB).draft.displayName,
        )
        assertEquals(
            classifyCard(dalmiaB).draft.displayName,
            classifyCard(dalmiaBothSides).draft.displayName,
        )
    }

    @Test
    fun `a labelled mobile survived, compared on its digits`() {
        // `+91 7000900582` against `91 7000900582`: the `+` was lost, so this holds only when the
        // comparison is on digits. A raw-string comparison of the mobile would have failed here,
        // which is worth knowing before anyone keys anything on one.
        fun mobile(lines: List<String>) =
            classifyCard(lines).draft.phones.filter { it.type == "MOBILE" }.map { digits(it.number) }

        assertEquals(listOf("917000900582"), mobile(dalmiaA))
        assertEquals(listOf("917000900582"), mobile(dalmiaB))
        assertEquals(listOf("917000900582"), mobile(dalmiaBothSides))
    }

    // ---- what is not, which is what decides the question ----------------------------------------

    @Test
    fun `a quarter of real cards carry no labelled mobile at all`() {
        // **The finding that rules out keying on the mobile.** `phoneTypeNear` requires a printed
        // label — `m`, `mobile`, `cell` — and never guesses, which is right. The consequence is
        // that a card whose only number is a switchboard has no mobile to key on, and this one
        // does not: JSW's single number is typed WORK in both readings.
        assertTrue(classifyCard(jswA).draft.phones.none { it.type == "MOBILE" })
        assertTrue(classifyCard(jswB).draft.phones.none { it.type == "MOBILE" })
        assertTrue(
            classifyCard(jswA).draft.phones.isNotEmpty(),
            "the fixture has no phone at all; it cannot show a phone that is not a mobile",
        )
    }

    @Test
    fun `the phone list changes when a second side is photographed`() {
        // So a key over *all* the numbers is unstable by construction under FR-1225: the back of
        // the card brings its own landline, and the same card captured one-sided and two-sided
        // would produce two different keys.
        fun all(lines: List<String>) = classifyCard(lines).draft.phones.map { digits(it.number) }.toSet()
        assertNotEquals(all(dalmiaB), all(dalmiaBothSides))
    }

    @Test
    fun `the email did not survive one of the three readings`() {
        // `jsw.test` against `jsvw.test`. One counterexample is enough to establish that the email is
        // not a field to key an exact match on either — which matters, because FR-1209's identity
        // is email-based and was the obvious candidate for a second key.
        assertNotEquals(
            classifyCard(jswA).draft.emails.map { it.address },
            classifyCard(jswB).draft.emails.map { it.address },
        )
    }
}
