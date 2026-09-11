package com.latch.cards

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SRS 1.213. Every fixture here is a real card from the 11 September run, and two of them are
 * addresses that reached the developer's account.
 */
class CardDoubtsTest {

    @Test
    fun `a name with no support from the email, beside a line that has some, is questioned`() {
        // The card came out named after a business unit while the person's own name sat unplaced.
        val draft = CardDraft(
            displayName = "Sterlite Copper",
            organisation = "VEDANTA LIMITED",
            emails = listOf(CardEmail("a.sunitha@vedanta.co.in", "WORK")),
        )
        val doubt = assertNotNull(nameDoubt(draft, listOf("vedanta", "A.SUNITHA")))
        assertEquals(CardDoubtField.NAME, doubt.field)
        assertEquals("A.SUNITHA", doubt.alternative)
        // **And the draft is untouched**, which is the whole point of the rule.
        assertEquals("Sterlite Copper", draft.displayName)
    }

    @Test
    fun `it asks on a shared mailbox too, and that is the cost of asking rather than acting`() {
        // The false positive that decided the design. This card's name is CORRECT; its email is the
        // federation's shared mailbox, so the evidence points at the wrong answer. Asking costs a
        // note the user dismisses. Swapping would have replaced a right name with an acronym, and
        // nothing in the shape of the two cards tells them apart.
        val draft = CardDraft(
            displayName = "Anand Mukherji",
            emails = listOf(CardEmail("fimi@fedmin.com", "WORK")),
        )
        assertEquals("FIMI", nameDoubt(draft, listOf("FIMI"))?.alternative)
    }

    @Test
    fun `a name its own email supports raises nothing`() {
        val draft = CardDraft(
            displayName = "Ranjit Mehra",
            emails = listOf(CardEmail("ranmehra@deloitte.com", "WORK")),
        )
        assertNull(nameDoubt(draft, listOf("Deloitte.", "Partner")))
    }

    @Test
    fun `a top-level domain nobody uses is questioned`() {
        // The company matches perfectly, so nothing but the ending gives this away.
        val draft = CardDraft(
            organisation = "Adani Enterprise Limited",
            emails = listOf(CardEmail("vinod.sharma@adani.cam", "WORK")),
        )
        assertEquals(CardDoubtField.EMAIL, emailDoubt(draft, emptyList())?.field)
    }

    @Test
    fun `a local part left behind in the unplaced list is questioned`() {
        // SRS 1.117 refuses to join the halves and is right. The half it placed is still an
        // address belonging to nobody, and this is what says so.
        val draft = CardDraft(
            displayName = "Dr Prashant Kumar Saraf",
            emails = listOf(CardEmail("Saraf@jsw.in", "WORK")),
        )
        assertNotNull(emailDoubt(draft, listOf("Email;: prashantkumar. Phone ; + 91")))
    }

    @Test
    fun `a logotype ending in a full stop is not a stranded local part`() {
        // Found on a device the minute this shipped, on the very first card of the pass. The
        // trademark full stop in "Deloitte." has every property the rule looks for; what tells it
        // apart is that the word is the card's own domain.
        val draft = CardDraft(
            displayName = "Ranjit Mehra",
            organisation = "Tohmatsu India LLP",
            emails = listOf(CardEmail("ranmehra@deloitte.com", "WORK")),
            urls = listOf("www.deloitte.com"),
        )
        assertNull(emailDoubt(draft, listOf("Deloitte.", "Deloitte Touche")))
    }

    @Test
    fun `a domain nearly the company's is questioned`() {
        val draft = CardDraft(
            organisation = "VEDANTA LIMITED",
            emails = listOf(CardEmail("a.sunitha@vedata.co.in", "WORK")),
            urls = listOf("www.vedantalimited.com"),
        )
        assertNotNull(emailDoubt(draft, emptyList()))
    }

    @Test
    fun `a domain that is simply a different one says nothing`() {
        // The direction that would make this worse than useless. A parent group's domain, a
        // personal address or a consultant's own are entirely unlike the employer and all correct;
        // only a near miss is evidence of damage.
        val parentGroup = CardDraft(
            organisation = "BOMBAY MINERALS LIMITED",
            emails = listOf(CardEmail("harshad@ashapura.com", "WORK")),
        )
        assertNull(emailDoubt(parentGroup, emptyList()))

        val personal = CardDraft(
            organisation = "FEDERATION OF MINING ASSOCIATIONS OF RAJASTHAN",
            emails = listOf(CardEmail("fomarab@rediffmail.com", "WORK")),
        )
        assertNull(emailDoubt(personal, emptyList()))
    }

    @Test
    fun `an abbreviation ending in a full stop is not a stranded local part`() {
        val draft = CardDraft(
            organisation = "Dess Technologies Pvt. Ltd.",
            emails = listOf(CardEmail("info@dess.in", "WORK")),
        )
        assertNull(emailDoubt(draft, listOf("Dess Technologies Pvt. Ltd.")))
    }

    @Test
    fun `edit distance counts what it says it counts`() {
        assertEquals(0, editsBetween("vedanta", "vedanta"))
        assertEquals(1, editsBetween("vedata", "vedanta"))
        assertEquals(2, editsBetween("vedta", "vedanta"))
    }
}
