package com.latch.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-1221 and FR-1223.
 *
 * **These cases are invented and none of them counts toward FR-1222's corpus**, which requires
 * 120 *real* cards for NFR-502's reason: a corpus padded with cases written by the person writing
 * the rules tests that the rules do what they say, not that they work. What is tested here is the
 * behaviour each rule is *supposed* to have, including where it is supposed to give up.
 */
class CardClassifierTest {

    private fun classify(vararg lines: String) = classifyCard(lines.toList())

    // ---- what it should place ----------------------------------------------------------------

    @Test
    fun `an ordinary card yields its fields`() {
        val result = classify(
            "Anita Sharma",
            "Head of Sourcing",
            "Northwind Textiles Ltd",
            "M: +91 98200 12345",
            "anita@northwind.example",
            "www.northwind.example",
        )
        assertEquals("Anita Sharma", result.draft.displayName)
        assertEquals("Head of Sourcing", result.draft.jobTitle)
        assertEquals("Northwind Textiles Ltd", result.draft.organisation)
        assertEquals("anita@northwind.example", result.draft.emails.single().address)
        assertEquals("www.northwind.example", result.draft.urls.single())
        assertEquals("MOBILE", result.draft.phones.single().type)
    }

    @Test
    fun `a label beside a number becomes its type, and silence becomes none`() {
        assertEquals("MOBILE", classify("X", "Mobile 98200 12345").draft.phones.single().type)
        assertEquals("FAX", classify("X", "Fax: 022 2345 6789").draft.phones.single().type)
        assertEquals("WORK", classify("X", "Office 022 2345 6789").draft.phones.single().type)
        // A card that says nothing gets no type rather than an invented one.
        assertNull(classify("X", "+91 98200 12345").draft.phones.single().type)
    }

    // ---- what it must NOT place --------------------------------------------------------------

    @Test
    fun `a postcode is not a telephone number`() {
        // The floor of seven digits is the whole of this rule's safety. Without it an address
        // line yields a number, and a wrong number on a contact is not visibly wrong.
        val result = classify("Anita Sharma", "12 Nehru Road, Pune 411001")
        assertTrue(result.draft.phones.isEmpty(), "an address line became a phone number")
        assertTrue("12 Nehru Road, Pune 411001" in result.unplaced)
    }

    @Test
    fun `a domain inside an email is not a separate website`() {
        val result = classify("Anita Sharma", "anita@northwind.example")
        assertEquals(1, result.draft.emails.size)
        assertTrue(result.draft.urls.isEmpty(), "the address's domain became a URL")
    }

    @Test
    fun `the line after the name is not assumed to be the job title`() {
        // The rule most classifiers reach for, and it is wrong on every card that puts the
        // company second — which is most of them.
        val result = classify("Anita Sharma", "Northwind Textiles Ltd", "+91 98200 12345")
        assertNull(result.draft.jobTitle)
        assertEquals("Northwind Textiles Ltd", result.draft.organisation)
    }

    @Test
    fun `a line it cannot place is shown, never dropped`() {
        // FR-1223. A card whose tagline vanished looks identical to one that never had it.
        val result = classify("Anita Sharma", "Weaving since 1974", "+91 98200 12345")
        assertEquals(listOf("Weaving since 1974"), result.unplaced)
    }

    @Test
    fun `an unrecognised company is unplaced rather than guessed`() {
        // No suffix a company actually uses, so it is left to the user. Guessing here would put
        // somebody's tagline in the company field on their contact.
        val result = classify("Ravi Menon", "Blue River", "+91 99000 54321")
        assertNull(result.draft.organisation)
        assertEquals(listOf("Blue River"), result.unplaced)
    }

    @Test
    fun `nothing is invented from an empty card`() {
        val result = classify("   ", "")
        assertTrue(result.draft.isEmpty)
        assertTrue(result.unplaced.isEmpty())
    }

    // ---- determinism ---------------------------------------------------------------------------

    @Test
    fun `classification is a pure function of its input`() {
        val lines = listOf("Anita Sharma", "Head of Sourcing", "+91 98200 12345")
        assertEquals(classifyCard(lines), classifyCard(lines))
    }

    @Test
    fun `every line reaches either a field or the unplaced list`() {
        // The property that makes FR-1223 checkable at all: nothing is silently consumed.
        val lines = listOf(
            "Anita Sharma", "Head of Sourcing", "Northwind Textiles Ltd",
            "M: +91 98200 12345", "anita@northwind.example", "Weaving since 1974",
        )
        val result = classifyCard(lines)
        val placed = listOfNotNull(result.draft.displayName, result.draft.organisation, result.draft.jobTitle).size +
            result.draft.phones.size + result.draft.emails.size + result.draft.urls.size
        assertEquals(lines.size, placed + result.unplaced.size, "a line was neither placed nor shown")
    }
}
