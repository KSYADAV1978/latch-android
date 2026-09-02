package com.latch.desktop.capture

import com.latch.desktop.ocr.OcrFailure
import com.latch.desktop.ocr.OcrOutcome
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.wire.WireDestination
import com.latch.wire.DraftResult
import com.latch.wire.draftItems
import com.latch.wire.itemKeyOf
import com.latch.wire.itemKeyTitle
import com.latch.wire.sourceHashOf
import java.awt.image.BufferedImage
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapturePipelineTest {

    private val blank = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)

    private fun recognised(text: String) = { _: BufferedImage ->
        OcrOutcome.Recognised(text, emptyList(), "en-GB", 100, 100) as OcrOutcome
    }

    private fun failing(reason: OcrFailure) = { _: BufferedImage ->
        OcrOutcome.Failed(reason, "detail") as OcrOutcome
    }

    @Test
    fun `copied text becomes a capture that did not use OCR`() {
        val outcome = buildCapture(Clip.Text("Kickoff 8 September 2027 at 9am"), recognised("unused"))
        assertIs<CaptureOutcome.Ready>(outcome)
        assertEquals("Kickoff 8 September 2027 at 9am", outcome.capture.text)
        assertFalse(outcome.capture.ocrUsed, "a text capture must not claim OCR")
    }

    @Test
    fun `a recognised image is flagged as OCR, which changes what Google receives`() {
        // Not a cosmetic flag: `ocrUsed` is what makes FR-509a take the title from the date's
        // own row rather than the opening of the text, and what makes FR-805b excerpt rather
        // than quote. Setting it wrongly writes a different item.
        val outcome = buildCapture(Clip.Image(blank), recognised("Fees due 20/09/2027"))
        assertIs<CaptureOutcome.Ready>(outcome)
        assertTrue(outcome.capture.ocrUsed)
    }

    @Test
    fun `an empty clipboard and whitespace are both nothing to capture`() {
        assertEquals(
            CaptureOutcome.Nothing(EmptyCapture.NOTHING_COPIED),
            buildCapture(Clip.Empty, recognised("x")),
        )
        assertEquals(
            CaptureOutcome.Nothing(EmptyCapture.NOTHING_COPIED),
            buildCapture(Clip.Text("   \n  "), recognised("x")),
        )
    }

    @Test
    fun `a machine with no language pack is its own answer, not a generic failure`() {
        // The Windows-only case. Android bundles its models; this reads what the machine has,
        // so a user whose phone reads a notice their desktop cannot is owed the sentence that
        // says which — and that needs the reason to survive as far as the UI.
        listOf(OcrFailure.NO_RECOGNISER, OcrFailure.NO_WINRT).forEach { reason ->
            val outcome = buildCapture(Clip.Image(blank), failing(reason))
            assertEquals(EmptyCapture.NO_RECOGNISER, (outcome as CaptureOutcome.Nothing).reason)
        }
    }

    @Test
    fun `an image with no text is told apart from an image that could not be read`() {
        assertEquals(
            EmptyCapture.NO_TEXT_IN_IMAGE,
            (buildCapture(Clip.Image(blank), failing(OcrFailure.NO_TEXT)) as CaptureOutcome.Nothing).reason,
        )
        assertEquals(
            EmptyCapture.RECOGNITION_FAILED,
            (buildCapture(Clip.Image(blank), failing(OcrFailure.UNREADABLE_SOURCE)) as CaptureOutcome.Nothing).reason,
        )
    }

    // ---- the point of the shared modules, demonstrated ---------------------------------------

    @Test
    fun `a desktop capture derives the same wire identity the phone would`() {
        // The claim `:wire` exists to make, asserted rather than argued: `source_hash` and
        // `item_key` are computed here by the same compiled functions the Android client runs.
        // What would break it is a second implementation, and there is not one — so this test
        // is a tripwire for someone adding one.
        val text = "PTM on Monday 14 September 2026."
        val capture = DesktopCapture(text = text)
        val context = ParseContext(now = LocalDateTime.of(2026, 9, 2, 12, 0))
        val result = DateParser.parse(text, context)

        assertEquals(sourceHashOf(text), sourceHashOf(capture.text))

        // The key must not carry the date, or a reschedule of this meeting could never match
        // it — which is FR-804's whole mechanism.
        val key = itemKeyTitle(capture, result)
        assertFalse("14" in key, "the date survived into the item key: '" + key + "'")
        assertFalse("September" in key, "the month survived into the item key: '" + key + "'")
        assertTrue(itemKeyOf(key).length == 64, "a SHA-256 hex digest")
    }

    @Test
    fun `a desktop capture drafts the same item the phone would`() {
        val text = "Trip from 20 September to 24 September 2027"
        val context = ParseContext(now = LocalDateTime.of(2026, 9, 2, 12, 0))
        val result = DateParser.parse(text, context)

        val draft = draftItems(
            captured = DesktopCapture(text = text),
            result = result,
            context = context,
            destination = WireDestination(calendarId = "cal", taskListId = "list"),
            captureId = "c1",
            chainId = "ch1",
        )
        assertIs<DraftResult.Ready>(draft)
        val item = draft.items.single()

        // FR-502's inclusive-to-exclusive conversion, which a device pass checked specifically
        // on 28 Aug 2026: the event covers the 20th through the 24th, so Google's exclusive
        // end is the 25th. A second implementation getting this wrong would put one client's
        // trips a day short.
        assertEquals("2027-09-20", item.start?.toLocalDate().toString())
        assertEquals("2027-09-25", item.end?.toLocalDate().toString())
        assertTrue(item.allDay)
        assertEquals("cal", item.calendarId)
    }
}
