package com.latch.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FR-509. */
class TitleExtractorTest {

    @Test
    fun `a short selection is used verbatim`() {
        assertEquals("Renew the car insurance", TitleExtractor.extract("Renew the car insurance").value)
    }

    @Test
    fun `a leading salutation is stripped`() {
        assertEquals(
            "PTM is on 12 September",
            TitleExtractor.extract("Dear Parent, PTM is on 12 September").value,
        )
    }

    @Test
    fun `a trailing sign-off is stripped`() {
        assertEquals(
            "Please confirm your attendance",
            TitleExtractor.extract("Please confirm your attendance\n\nRegards,\nPrincipal").value,
        )
    }

    @Test
    fun `a long selection is cut at the first clause and capped at 50 characters`() {
        val long = "The parent teacher meeting for class five will be held in the main auditorium. " +
            "Please arrive ten minutes early."

        val title = TitleExtractor.extract(long).value

        assertTrue(title.length <= 50, "was ${title.length}: $title")
        assertTrue(title.startsWith("The parent teacher meeting"))
        assertTrue(!title.contains("arrive"), "only the first clause is used")
    }

    @Test
    fun `a selection that is only a salutation still yields something usable`() {
        assertTrue(TitleExtractor.extract("Hello").value.isNotEmpty())
    }
}
