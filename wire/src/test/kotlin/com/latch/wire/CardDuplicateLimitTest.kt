package com.latch.wire

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Why two photographs of one card become two contacts (SRS 1.123, 1.130).
 *
 * **These are the two real recognitions**, taken from the device on 5 September 2026 four minutes
 * apart, of one printed card held still under the same light. Nothing about the card changed and
 * nothing about the app changed; the recogniser simply saw a different photograph.
 *
 * The point of this file is not to pin behaviour anybody wants. It is to hold the two facts that
 * make the decision, side by side, so that whoever takes it is looking at evidence rather than at
 * a note: **the source hash differs, and the FR-1209 identity does not.**
 */
class CardDuplicateLimitTest {

    /** As recognised at 20:33. */
    private val firstReading = listOf(
        "Arvind Bhandari",
        "Senior",
        "General Manager",
        "Corporate Affairs",
        "Bharat Limited",
        "Dalmia",
        "m +91 7000900582",
        "e bhandari.arvind @dalmiabharat.test",
    ).joinToString("\n")

    /** As recognised at 20:35. Same card, same session, four minutes later. */
    private val secondReading = listOf(
        "Arvind Bhandari",
        "Dalmia",
        "Senior General Manager",
        "Corporate Affairs",
        "Bharat Limited",
        "m 91 7000900582",
        "e",
        "bhandari.arvind @dalmiabharat.test",
    ).joinToString("\n")

    @Test
    fun `two photographs of one card do not share a source hash`() {
        // FR-1208 keys on this, so this single line is the whole reason two contacts were written
        // into a real account on 5 September without either being reported as already saved.
        // The differences are ordinary OCR: a line break moved, a "+" was dropped, "Senior" and
        // "General Manager" merged. `normaliseForHash` collapses whitespace and case and nothing
        // else — it cannot and should not paper over a changed character.
        assertNotEquals(
            cardSourceHashOf(firstReading),
            cardSourceHashOf(secondReading),
            "the readings were identical; this fixture no longer demonstrates anything",
        )
    }

    @Test
    fun `the same reading twice does share one, so the hash itself is sound`() {
        // The control. Without it the test above would be equally consistent with a hash that
        // never matches anything — which is exactly the failure SRS 1.38 spent five days on, and
        // exactly what a duplicate check that silently never fires looks like from outside.
        assertEquals(cardSourceHashOf(firstReading), cardSourceHashOf(firstReading))
        assertEquals(
            cardSourceHashOf(firstReading),
            cardSourceHashOf(firstReading.replace("\n", "\r\n")),
            "line endings alone changed the hash",
        )
    }

    @Test
    fun `but both readings yield the same FR-1209 identity`() {
        // **The cure, already computed and already written to every contact Latch creates.**
        // SRS 1.118 noticed that `sharesContactIdentity` is called from nowhere; the write path
        // matches on the hash alone. Both photographs read the email identically — it is the one
        // field on a card that OCR either gets right or renders unusable — so an identity check
        // would have caught this duplicate where the hash could not.
        val first = CardDraft(
            displayName = "Arvind Bhandari",
            emails = listOf(CardEmail("bhandari.arvind@dalmiabharat.test")),
        )
        val second = CardDraft(
            displayName = "Arvind Bhandari",
            // Case and spacing differ, as they do between two recognitions; normalisation is
            // deliberately case and whitespace only, which is enough for exactly this.
            emails = listOf(CardEmail(" Bhandari.Arvind@DalmiaBharat.test ")),
        )
        assertEquals(contactIdentityKeys(first), contactIdentityKeys(second))
        assertTrue(sharesContactIdentity(contactIdentityKeys(first), contactIdentityKeys(second)))
    }

    @Test
    fun `and a card with no email still has no identity to match on`() {
        // Why adopting the identity as a second key would not close the gap, only narrow it.
        // Many printed cards carry no email at all, and FR-1209 deliberately refuses to let two
        // empty identities match — the protection against the wrong merge a shared switchboard
        // number invites (v1.94). Those cards would still duplicate on a second photograph.
        val noEmail = CardDraft(displayName = "Arvind Bhandari")
        assertEquals(emptyList(), contactIdentityKeys(noEmail))
        assertTrue(
            !sharesContactIdentity(contactIdentityKeys(noEmail), contactIdentityKeys(noEmail)),
            "two emailless cards matched, which is the wrong-merge hazard FR-1209 forbids",
        )
    }
}
