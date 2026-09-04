package com.latch.cards

import com.latch.core.model.CardDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-1204's grammar.
 *
 * **The cases here are the ones real cards get wrong**, not the ones the RFC is clearest about.
 * A parser that handles a textbook vCard and drops a folded `ADR` looks correct in every demo and
 * loses an address on the first real card — which is the shape of every defect this project has
 * paid for: right in the model, wrong where it meets the world.
 */
class CardParserTest {

    private fun parsed(payload: String): CardDraft {
        val result = parseCard(payload)
        assertTrue(result is CardParse.Parsed, "expected a parse, got $result")
        return result.draft
    }

    // ---- what is not a card ---------------------------------------------------------------

    @Test
    fun `a URL is not a card`() {
        assertEquals(
            CardParse.Unreadable(CardUnreadable.NOT_A_CARD),
            parseCard("https://example.com/whoami"),
        )
    }

    @Test
    fun `a Wi-Fi payload is not a card`() {
        // The other thing QR codes on printed material actually carry.
        assertEquals(
            CardParse.Unreadable(CardUnreadable.NOT_A_CARD),
            parseCard("WIFI:S:MyNetwork;T:WPA;P:secret;;"),
        )
    }

    @Test
    fun `a card grammar carrying nothing usable is unreadable, not an empty sheet`() {
        // FR-1204: reported rather than partially accepted. Half a contact looks exactly like a
        // whole one on a preview screen.
        assertEquals(
            CardParse.Unreadable(CardUnreadable.NO_FIELDS),
            parseCard("BEGIN:VCARD\nVERSION:3.0\nREV:2026-09-04T10:00:00Z\nEND:VCARD"),
        )
    }

    // ---- the ordinary case ----------------------------------------------------------------

    @Test
    fun `a plain vCard 3 0 yields every field it carries`() {
        val draft = parsed(
            """
            BEGIN:VCARD
            VERSION:3.0
            N:Sharma;Anita;;;
            FN:Anita Sharma
            ORG:Northwind Textiles;Exports
            TITLE:Head of Sourcing
            TEL;TYPE=CELL:+91 98200 12345
            TEL;TYPE=WORK:+91 22 2345 6789
            EMAIL;TYPE=WORK:anita@northwind.example
            URL:https://northwind.example
            END:VCARD
            """.trimIndent()
        )
        assertEquals("Anita Sharma", draft.displayName)
        assertEquals("Anita", draft.givenName)
        assertEquals("Sharma", draft.familyName)
        // The department is dropped: "Northwind Textiles;Exports" as one string would name a
        // company nobody works for.
        assertEquals("Northwind Textiles", draft.organisation)
        assertEquals("Head of Sourcing", draft.jobTitle)
        assertEquals(listOf("+91 98200 12345", "+91 22 2345 6789"), draft.phones.map { it.number })
        assertEquals(listOf("CELL", "WORK"), draft.phones.map { it.type })
        assertEquals("anita@northwind.example", draft.emails.single().address)
        assertEquals("https://northwind.example", draft.urls.single())
    }

    @Test
    fun `unknown properties contribute nothing`() {
        // A card is not obliged to restrict itself to what this app understands, and squeezing
        // an unknown property into a field that nearly fits is how a wrong phone number happens.
        val draft = parsed(
            "BEGIN:VCARD\nVERSION:3.0\nFN:Ravi Menon\nBDAY:1985-03-02\n" +
                "X-SKYPE:ravi.menon\nPHOTO;VALUE=URI:https://example.com/r.jpg\nEND:VCARD"
        )
        assertEquals("Ravi Menon", draft.displayName)
        assertTrue(draft.phones.isEmpty())
        assertTrue(draft.urls.isEmpty(), "a PHOTO URI is not the contact's website")
        assertEquals(null, draft.note)
    }

    // ---- what real cards get wrong ---------------------------------------------------------

    @Test
    fun `a folded line is one property, not two`() {
        // RFC 6350 folding, which every conforming exporter produces for a long value. Read as
        // separate lines the ADR is lost entirely rather than truncated — a card that appears to
        // have no address at all.
        val draft = parsed(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Long Address\r\n" +
                "ADR;TYPE=WORK:;;221B Baker Street\r\n  and a very long second part;London;;NW1 6XE;UK\r\n" +
                "END:VCARD"
        )
        val address = draft.addresses.single()
        assertTrue("221B Baker Street and a very long second part" in address, address)
        assertTrue("NW1 6XE" in address, address)
    }

    @Test
    fun `an escaped semicolon does not split a component`() {
        // "Smith; Jones" is one company. Splitting naively invents a department.
        val draft = parsed("BEGIN:VCARD\nVERSION:3.0\nFN:X\nORG:Smith\\; Jones Ltd\nEND:VCARD")
        assertEquals("Smith; Jones Ltd", draft.organisation)
    }

    @Test
    fun `escaped newlines and commas survive`() {
        val draft = parsed("BEGIN:VCARD\nVERSION:3.0\nFN:X\nNOTE:Met at the fair\\, Hall 2\\nFollow up\nEND:VCARD")
        assertEquals("Met at the fair, Hall 2\nFollow up", draft.note)
    }

    @Test
    fun `an empty address component does not become a leading comma`() {
        val draft = parsed("BEGIN:VCARD\nVERSION:3.0\nFN:X\nADR:;;12 Nehru Road;Pune;;411001;India\nEND:VCARD")
        assertEquals("12 Nehru Road, Pune, 411001, India", draft.addresses.single())
    }

    @Test
    fun `a grouped property is not an unknown one`() {
        // Apple and several exporters emit `item1.TEL`. Left in place, every grouped property
        // becomes unrecognised and the card silently loses its phone number.
        val draft = parsed("BEGIN:VCARD\nVERSION:3.0\nFN:X\nitem1.TEL;TYPE=CELL:+44 7700 900123\nEND:VCARD")
        assertEquals("+44 7700 900123", draft.phones.single().number)
        assertEquals("CELL", draft.phones.single().type)
    }

    @Test
    fun `vCard 2 1 bare type parameters are read as types`() {
        // 2.1 writes `TEL;CELL:` where 3.0 writes `TEL;TYPE=CELL:`. Both are common on printed
        // cards because the generator, not the owner, chooses.
        val draft = parsed("BEGIN:VCARD\nVERSION:2.1\nFN:X\nTEL;CELL:+1 555 0100\nEND:VCARD")
        assertEquals("CELL", draft.phones.single().type)
    }

    @Test
    fun `a charset parameter is not mistaken for a phone label`() {
        val draft = parsed("BEGIN:VCARD\nVERSION:2.1\nFN:X\nTEL;CHARSET=UTF-8;WORK:+1 555 0100\nEND:VCARD")
        assertEquals("WORK", draft.phones.single().type)
    }

    @Test
    fun `quoted-printable is decoded, so a Devanagari name is not mojibake`() {
        // vCard 2.1's encoding for anything non-ASCII. Without this a Hindi name arrives as
        // "=E0=A4..." and reaches the user's contacts that way — NFR-404's script, one field over.
        val draft = parsed(
            "BEGIN:VCARD\nVERSION:2.1\n" +
                "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E0=A4=85=E0=A4=A8=E0=A4=BF=E0=A4=A4=E0=A4=BE\n" +
                "TEL:+91 98200 12345\nEND:VCARD"
        )
        assertEquals("अनिता", draft.displayName)
    }

    @Test
    fun `FN wins over N, because it is what the card's author chose to be called`() {
        val draft = parsed("BEGIN:VCARD\nVERSION:3.0\nN:Menon;Ravi;;Dr.;\nFN:Dr Ravi Menon\nEND:VCARD")
        assertEquals("Dr Ravi Menon", draft.displayName)
        assertEquals("Ravi", draft.givenName)
        assertEquals("Menon", draft.familyName)
    }

    @Test
    fun `a card with only N still has a display name`() {
        // FN is mandatory in 3.0 and 4.0 and routinely absent in 2.1.
        val draft = parsed("BEGIN:VCARD\nVERSION:2.1\nN:Iyer;Meera;;;\nTEL:+91 90000 11111\nEND:VCARD")
        assertEquals("Meera Iyer", draft.displayName)
    }

    // ---- MECARD ----------------------------------------------------------------------------

    @Test
    fun `MECARD reverses its name components`() {
        // Family,Given — the opposite of how it reads. A parser that passes it through produces
        // contacts filed under the wrong name.
        val draft = parsed("MECARD:N:Doe,John;TEL:+15550100;EMAIL:john@example.com;;")
        assertEquals("John", draft.givenName)
        assertEquals("Doe", draft.familyName)
        assertEquals("John Doe", draft.displayName)
        assertEquals("+15550100", draft.phones.single().number)
        assertEquals("john@example.com", draft.emails.single().address)
    }

    @Test
    fun `MECARD carries organisation, title and url`() {
        val draft = parsed("MECARD:N:Rao,Sunita;ORG:Blue River;TITLE:Partner;URL:https://blue.example;;")
        assertEquals("Blue River", draft.organisation)
        assertEquals("Partner", draft.jobTitle)
        assertEquals("https://blue.example", draft.urls.single())
    }

    @Test
    fun `a MECARD without the double terminator still parses`() {
        // Plenty of generators omit it, and refusing the card over a terminator would fail the
        // user for their printer's mistake.
        val draft = parsed("MECARD:N:Doe,John;TEL:+15550100")
        assertEquals("John Doe", draft.displayName)
    }

    @Test
    fun `a lower-case prefix is still a card`() {
        assertTrue(parseCard("begin:vcard\nVERSION:3.0\nFN:X\nEND:VCARD") is CardParse.Parsed)
        assertTrue(parseCard("mecard:N:Doe,John;;") is CardParse.Parsed)
    }

    // ---- determinism -----------------------------------------------------------------------

    @Test
    fun `parsing is a pure function of its input`() {
        // `:parser`'s rule, which is why that corpus is worth anything: no clock, no locale, no
        // ambient state, so a case that passes today passes in March.
        val payload = "BEGIN:VCARD\nVERSION:3.0\nFN:Repeatable\nTEL:+1 555 0100\nEND:VCARD"
        assertEquals(parseCard(payload), parseCard(payload))
    }
}
