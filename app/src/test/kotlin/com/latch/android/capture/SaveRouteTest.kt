package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureSource
import com.latch.core.model.ItemType
import com.latch.data.InboxReason
import com.latch.parser.Classification
import com.latch.parser.Confidence
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.wire.dateSpans
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-512, FR-506 rows 3 and 4, AC-03: where a confirmed capture goes.
 *
 * **This is the end of FR-512's interim reading**, which SRS 1.10 recorded as being in force
 * "only until the FR-700 Capture Inbox exists". Every case below was, until this slice, a save
 * straight to the user's account with a note beside it.
 */
class SaveRouteTest {

    private val context = ParseContext(
        now = LocalDateTime.parse("2026-09-01T09:00:00"),
        zone = ZoneId.of("Asia/Kolkata"),
    )

    private val shareSheet = CaptureSource(layer = CaptureLayer.SHARE_SHEET)
    private val notification = CaptureSource(layer = CaptureLayer.NOTIFICATION)

    private fun parse(text: String) = DateParser.parse(text, context)

    private fun route(text: String, source: CaptureSource = shareSheet): SaveRoute {
        val result = parse(text)
        return saveRoute(result, result.candidates.indices.toSet(), context.confidenceThreshold, source)
    }

    // AC-03: "Select text containing no date → Undated task created in Capture Inbox; no
    // calendar entry created."

    @Test
    fun `text with no date at all goes to the Inbox`() {
        val text = "Ask about the uniform order"
        // The condition that would make this check fail: the parser finding a date in it. It
        // must not, or the test would pass for the wrong reason.
        assertNull(parse(text).primary.date, "the fixture must genuinely hold no date")
        assertNull(parse(text).primary.time, "the fixture must genuinely hold no time")
        assertEquals(Classification.TASK_UNDATED, parse(text).primary.classification)

        val route = assertIs<SaveRoute.Inbox>(route(text))
        assertEquals(InboxReason.UNDATED, route.reason)
    }

    @Test
    fun `an ordinary dated capture still goes straight to Google`() {
        // The regression guard on all of this: the common case must not have moved.
        val result = parse("Kickoff 8 September 2027 at 9am")
        assertNotNull(result.primary.date)
        assertEquals(SaveRoute.Google, route("Kickoff 8 September 2027 at 9am"))
    }

    @Test
    fun `a date with no time is still an ordinary save, not an Inbox route`() {
        // FR-506's second row is a task with a due date, which is a finished thing. Routing it
        // would put every dateless-but-dated capture behind a triage step.
        assertEquals(SaveRoute.Google, route("Fees due 20 September 2027"))
    }

    // FR-512 itself.

    @Test
    fun `a capture below the confidence threshold goes to the Inbox`() {
        val result = parse("Kickoff 8 September 2027 at 9am")
        val low = result.copy(
            candidates = result.candidates.map {
                it.copy(date = it.date?.copy(confidence = Confidence.LOW))
            },
        )
        val route = assertIs<SaveRoute.Inbox>(
            saveRoute(low, low.candidates.indices.toSet(), context.confidenceThreshold, shareSheet)
        )
        assertEquals(InboxReason.LOW_CONFIDENCE, route.reason)
    }

    @Test
    fun `the threshold is a parameter, so a deployment can move it`() {
        val result = parse("Kickoff 8 September 2027 at 9am")
        // The same capture routes or does not, depending only on the threshold — which is what
        // FR-512's "configurable threshold" means.
        assertEquals(
            SaveRoute.Google,
            saveRoute(result, result.candidates.indices.toSet(), Confidence(0.6), shareSheet),
        )
        assertIs<SaveRoute.Inbox>(
            saveRoute(result, result.candidates.indices.toSet(), Confidence(0.99), shareSheet)
        )
    }

    // NFR-206 / FR-210: the layer that may never reach persistent storage.

    @Test
    fun `a notification capture is never routed, whatever its confidence`() {
        // The third appearance of one rule — FR-805a and FR-210a already exclude this layer
        // from the item description and from webhook delivery. FR-512's interim behaviour
        // survives here permanently, because the alternative is losing the capture.
        assertEquals(SaveRoute.Google, route("Ask about the uniform order", notification))

        val result = parse("Kickoff 8 September 2027 at 9am")
        assertEquals(
            SaveRoute.Google,
            saveRoute(result, result.candidates.indices.toSet(), Confidence(0.99), notification),
        )
    }

    // SRS 1.23: a blocked candidate blocks itself and not its neighbours.

    @Test
    fun `one blocked row beside a saveable one does not route the capture`() {
        val result = parse("Kickoff 8 September 2027 at 9am")
        val withBlocked = result.copy(
            candidates = result.candidates + result.primary.copy(
                date = null,
                classification = Classification.EVENT_INCOMPLETE,
            ),
        )
        assertEquals(
            SaveRoute.Google,
            saveRoute(
                withBlocked,
                withBlocked.candidates.indices.toSet(),
                context.confidenceThreshold,
                shareSheet,
            ),
        )
    }

    @Test
    fun `a capture whose only row is blocked goes to the Inbox`() {
        val result = parse("Kickoff 8 September 2027 at 9am")
        val blocked = result.copy(
            candidates = listOf(
                result.primary.copy(date = null, classification = Classification.EVENT_INCOMPLETE)
            ),
        )
        val route = assertIs<SaveRoute.Inbox>(
            saveRoute(blocked, setOf(0), context.confidenceThreshold, shareSheet)
        )
        assertEquals(InboxReason.INCOMPLETE, route.reason)
    }

    // FR-702's assigned date, applied to the parse.

    @Test
    fun `an assigned date turns an undated capture into a dated task`() {
        val result = parse("Ask about the uniform order")
        val dated = withAssignedDate(result, LocalDate.parse("2027-09-20"), today = LocalDate.parse("2026-09-01"))

        val candidate = dated.primary
        assertEquals(LocalDate.parse("2027-09-20"), candidate.date?.value)
        assertEquals(Confidence.CERTAIN, candidate.date?.confidence)
        assertEquals(ItemType.TASK, candidate.classification.itemType)
        assertEquals(Classification.TASK_WITH_DUE_DATE, candidate.classification)
        assertTrue(candidate.isExplicit, "a date the user typed is not an inference")
    }

    @Test
    fun `an assigned date carries no span, so it blanks nothing out of the item key`() {
        // §7.2 derives latch.item_key by blanking every matched span out of the captured text.
        // A date that was never in that text has nothing to blank, and giving it a span would
        // blank characters that mean something else.
        val dated = withAssignedDate(
            parse("Ask about the uniform order"),
            LocalDate.parse("2027-09-20"),
            today = LocalDate.parse("2026-09-01"),
        )
        assertNull(dated.primary.date?.span)
        assertTrue(dateSpans(dated).isEmpty())
    }

    @Test
    fun `an assigned date completes a row that had a time but no day`() {
        val result = parse("Kickoff 8 September 2027 at 9am")
        val blocked = result.copy(
            candidates = listOf(
                result.primary.copy(date = null, classification = Classification.EVENT_INCOMPLETE)
            ),
        )
        assertEquals(DraftBlocker.NEEDS_A_DATE, candidateBlocker(blocked.primary))

        val dated = withAssignedDate(blocked, LocalDate.parse("2027-09-08"), today = LocalDate.parse("2026-09-01"))
        assertNull(candidateBlocker(dated.primary), "the row should now be writable")
        assertEquals(Classification.EVENT, dated.primary.classification)
    }

    @Test
    fun `assigning a date does not overwrite one the writer actually wrote`() {
        val result = parse("Kickoff 8 September 2027 at 9am")
        val dated = withAssignedDate(result, LocalDate.parse("2030-01-01"), today = LocalDate.parse("2026-09-01"))
        assertEquals(LocalDate.parse("2027-09-08"), dated.primary.date?.value)
    }

    @Test
    fun `an assigned date in the past is flagged as past, which FR-510 reads`() {
        val dated = withAssignedDate(
            parse("Ask about the uniform order"),
            LocalDate.parse("2026-03-12"),
            today = LocalDate.parse("2026-09-01"),
        )
        assertTrue(dated.primary.isPast)
    }
}
