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
        // It is now an *address* rather than an unplaced line — the postcode anchors it. The
        // point of the case is unchanged: it must never become a telephone number.
        assertEquals(listOf("12 Nehru Road, Pune 411001"), result.draft.addresses)
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

/**
 * The two repairs card eleven forced, tested at the seam where each could invent rather than
 * recover — which is the only interesting half of either.
 */
class CardOcrRepairTest {

    @Test
    fun `a domain broken by a space is repaired`() {
        val result = classifyCard(listOf("Email: rameshkumar.bhagat@jsw. test Phone : + 91 11 4000 8600"))
        assertEquals(listOf("rameshkumar.bhagat@jsw.test"), result.draft.emails.map { it.address })
    }

    @Test
    fun `prose after a full stop is not made into a domain`() {
        // The dangerous half. `acme.` is not a valid domain either, so the repair is *offered* a
        // following word here exactly as it was on the real card — and must refuse it, because a
        // capitalised word is a sentence and not a top-level domain.
        val result = classifyCard(listOf("Write to john@acme. Regards, the team"))
        assertTrue(
            result.draft.emails.isEmpty(),
            "invented ${result.draft.emails.map { it.address }} from a sentence",
        )
    }

    @Test
    fun `a long lowercase word is not made into a domain either`() {
        val result = classifyCard(listOf("reach us at sales@example. contact anytime"))
        assertTrue(result.draft.emails.isEmpty(), "invented ${result.draft.emails.map { it.address }}")
    }

    @Test
    fun `a plus separated from its country code is kept`() {
        val result = classifyCard(listOf("Phone : + 91 11 4000 8600"))
        assertEquals(listOf("+91 11 4000 8600"), result.draft.phones.map { it.number })
    }

    @Test
    fun `a number with no plus does not acquire one`() {
        val result = classifyCard(listOf("Tel 011 20001745"))
        assertEquals(listOf("011 20001745"), result.draft.phones.map { it.number })
    }

    @Test
    fun `the label still reaches a number the plus rule matched`() {
        // `phoneTypeNear` reads what precedes the match, so widening the pattern leftwards is the
        // one way this change could have broken a rule it has nothing to do with.
        val result = classifyCard(listOf("Mobile: + 91 99000 67612"))
        assertEquals("MOBILE", result.draft.phones.single().type)
    }

    // ---- SRS 1.126: the recogniser bleeding Devanagari into Latin ----------------------------

    private val anusvara = "\u0902"
    private val danda = "\u0964"

    @Test
    fun `a Devanagari mark inside a Latin word does not cost the name`() {
        // The whole defect in one assertion. The mark made `looksLikePersonName` refuse the line,
        // so the real name fell to the notes and the NEXT line was promoted — the contact Google
        // would have received was called "Corporate Affairs".
        val result = classifyCard(
            listOf(
                "Ramesh Kumar Bhaga${anusvara}a",
                "Executive Vice President -",
                "Corporate Affairs",
            )
        )
        assertEquals("Ramesh Kumar Bhagat", result.draft.displayName)
        assertTrue(
            "Corporate Affairs" !in listOfNotNull(result.draft.displayName),
            "a job-title fragment was promoted into the name",
        )
    }

    @Test
    fun `the mark is dropped and nothing else about the word changes`() {
        assertEquals("Bhagat", repairScriptBleed("Bhaga${anusvara}a"))
        assertEquals("Ramesh Kumar Bhagat", repairScriptBleed("Ramesh Kumar Bhaga${anusvara}a"))
    }

    @Test
    fun `an accented Latin letter is left alone`() {
        // The reason this is confined to the Devanagari block. Latin uses combining marks
        // legitimately, and a rule about combining marks in general would spell `Zoë` as `Zoe`
        // and `José` as `Jose` — quietly changing somebody's name, which is the harm the whole
        // repair exists to prevent.
        val combined = "Zo\u0065\u0308"
        assertEquals(combined, repairScriptBleed(combined))
        assertEquals("Jos\u00e9 Ferreira", repairScriptBleed("Jos\u00e9 Ferreira"))
    }

    @Test
    fun `a genuine Devanagari word is untouched`() {
        // NFR-403. The mark's base here is Devanagari too, which is the ordinary correct case and
        // must survive: stripping it would mangle a Hindi card into nonsense.
        val hindi = "\u0939\u093f\u0928\u094d\u0926\u0940"
        assertEquals(hindi, repairScriptBleed(hindi))
    }

    @Test
    fun `a Devanagari mark on a space or a digit is left alone`() {
        // Only a *Latin letter* base is unambiguously wrong. Anything else and the repair would
        // be guessing about text it does not understand.
        assertEquals("91 ${anusvara}4000", repairScriptBleed("91 ${anusvara}4000"))
    }

    @Test
    fun `a danda in a number is deliberately not repaired`() {
        // Recorded as a decision rather than an omission. Replacing it with a digit invents one;
        // deleting it yields `+91 1 4000 8600`, a *more* plausible wrong number than the
        // truncation the user can currently see and correct on the sheet.
        val result = classifyCard(listOf("Phone : + 91 1${danda} 4000 8600"))
        assertEquals(listOf("4000 8600"), result.draft.phones.map { it.number })
    }

    @Test
    fun `a line with no Devanagari in it comes back identical`() {
        val line = "Executive Vice President - Corporate Affairs"
        assertTrue(line === repairScriptBleed(line), "the untouched path allocated a new string")
    }
}
