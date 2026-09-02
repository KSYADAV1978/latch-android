package com.latch.desktop.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OcrOutputTest {

    private fun line(text: String, x: Double, y: Double, w: Double = 100.0, h: Double = 20.0) =
        "LINE\t$x\t$y\t$w\t$h\t$text"

    private fun output(vararg rows: String) =
        (listOf("ENGINE\ten-GB\t1440\t900") + rows).joinToString("\n")

    // ---- reading order ---------------------------------------------------------------------

    @Test
    fun `rows come back top to bottom whatever order the recogniser emitted them in`() {
        // The failure this exists for, stated first: on Android the recogniser returned an
        // earlier chat message *after* a later one, and §7.2's item_key derives from character
        // positions in the assembled text. So the fixture emits bottom-first. A function that
        // trusted the input order would return "third second first" and this would be red.
        val result = parseRecogniserOutput(
            output(
                line("third", x = 10.0, y = 300.0),
                line("first", x = 10.0, y = 100.0),
                line("second", x = 10.0, y = 200.0),
            )
        )
        assertIs<OcrOutcome.Recognised>(result)
        assertEquals("first\nsecond\nthird", result.text)
    }

    @Test
    fun `two boxes on one row read left to right`() {
        val result = parseRecogniserOutput(
            output(
                line("Notes", x = 700.0, y = 100.0),
                line("Date", x = 100.0, y = 102.0),
            )
        )
        assertIs<OcrOutcome.Recognised>(result)
        assertEquals("Date\nNotes", result.text)
    }

    @Test
    fun `the row tolerance is a ratio of the text's own height, so it holds at both scales`() {
        // The guarantee in prose: a screenshot's 20px rows and a photographed page's 200px rows
        // must both group the same way. A fixed pixel tolerance passes one of these and fails
        // the other, which is the point of choosing the fixture this way.
        val screenshot = parseRecogniserOutput(
            output(
                line("right", x = 700.0, y = 104.0, h = 20.0),
                line("left", x = 100.0, y = 100.0, h = 20.0),
            )
        )
        val photograph = parseRecogniserOutput(
            output(
                line("right", x = 7000.0, y = 1040.0, h = 200.0),
                line("left", x = 1000.0, y = 1000.0, h = 200.0),
            )
        )
        assertIs<OcrOutcome.Recognised>(screenshot)
        assertIs<OcrOutcome.Recognised>(photograph)
        assertEquals("left\nright", screenshot.text)
        assertEquals("left\nright", photograph.text)
    }

    @Test
    fun `a genuinely lower line is its own row even when it is far to the left`() {
        val result = parseRecogniserOutput(
            output(
                line("below", x = 0.0, y = 130.0, h = 20.0),
                line("above", x = 900.0, y = 100.0, h = 20.0),
            )
        )
        assertIs<OcrOutcome.Recognised>(result)
        assertEquals("above\nbelow", result.text)
    }

    // ---- failures --------------------------------------------------------------------------

    @Test
    fun `a named error becomes its own reason`() {
        val result = parseRecogniserOutput("ERROR\tNO_RECOGNISER\tno OCR language pack is installed")
        assertEquals(OcrOutcome.Failed(OcrFailure.NO_RECOGNISER, "no OCR language pack is installed"), result)
    }

    @Test
    fun `a reason this build does not know is a bridge failure, not a crash`() {
        val result = parseRecogniserOutput("ERROR\tSOMETHING_NEWER\tdetail")
        assertEquals(OcrOutcome.Failed(OcrFailure.BRIDGE_FAILED, "detail"), result)
    }

    @Test
    fun `output with no ENGINE record is a bridge failure`() {
        assertEquals(
            OcrOutcome.Failed(OcrFailure.BRIDGE_FAILED, "no ENGINE record"),
            parseRecogniserOutput(line("orphan", 0.0, 0.0)),
        )
    }

    @Test
    fun `recognition that found nothing is NO_TEXT and not an error`() {
        assertEquals(OcrOutcome.Failed(OcrFailure.NO_TEXT), parseRecogniserOutput(output()))
    }

    @Test
    fun `a malformed LINE is dropped and the rest survive`() {
        // Design principle 1's instinct at the smallest scale: one unreadable row must not cost
        // the capture the rows around it.
        val result = parseRecogniserOutput(
            output(line("kept", 0.0, 0.0), "LINE\tnot-a-number\t0\t1\t1\tdropped")
        )
        assertIs<OcrOutcome.Recognised>(result)
        assertEquals("kept", result.text)
    }

    @Test
    fun `the engine language and image size come back`() {
        val result = parseRecogniserOutput(output(line("x", 0.0, 0.0)))
        assertIs<OcrOutcome.Recognised>(result)
        assertEquals("en-GB", result.engineLanguage)
        assertEquals(1440, result.imageWidth)
        assertEquals(900, result.imageHeight)
    }

    @Test
    fun `geometry survives, because FR-509a and FR-805b are stated in rows`() {
        val result = parseRecogniserOutput(output(line("Fees due 20-09-2027", x = 12.0, y = 340.0)))
        assertIs<OcrOutcome.Recognised>(result)
        assertEquals(1, result.lines.size)
        assertEquals(12.0, result.lines[0].x)
        assertEquals(340.0, result.lines[0].y)
        assertTrue(result.lines[0].text.startsWith("Fees due"))
    }
}
