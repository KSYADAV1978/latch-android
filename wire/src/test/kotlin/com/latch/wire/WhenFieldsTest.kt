package com.latch.wire

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.parser.DateOrder
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SRS 1.169: what the sheet may say *when* an item is, asserted against what is actually written.
 *
 * **This exists because the same defect happened twice in one hour on 6 September 2026**, and both
 * times it was the confirmation sheet drifting from `draftItems`: a to-do displayed a time Google
 * Tasks discards (SRS 1.167), and a range displayed only its opening date while the event written
 * covered a span (SRS 1.168). Neither was reachable from any test, because the decision lived in a
 * Compose function — which is this project's most repeated lesson arriving for the fourth time.
 *
 * **Every case asserts the pair, not the display alone.** A test that only pinned `whenFields`
 * would pin whatever it currently returns, which is the standing warning that a test can pin
 * behaviour exactly and pin the wrong behaviour. What makes these worth anything is that the
 * written `Item` is checked in the same breath.
 */
class WhenFieldsTest {

    private val context = ParseContext(
        now = LocalDateTime.of(2026, 9, 6, 10, 0),
        zone = ZoneId.of("Asia/Kolkata"),
        dateOrder = DateOrder.DAY_FIRST,
    )

    private class Captured(override val text: String) : WireCapture {
        override val preferredTitle: String? = null
        override val ocrUsed: Boolean = false
    }

    private fun candidate(text: String, type: ItemType) =
        withTypeOverrides(DateParser.parse(text, context), mapOf(0 to type)).candidates.first()

    private fun written(text: String, type: ItemType): Item {
        val parsed = withTypeOverrides(DateParser.parse(text, context), mapOf(0 to type))
        val drafted = draftItems(
            Captured(text), parsed, context, WireDestination("cal", "tasks"), "cap", "chain",
        )
        return assertIs<DraftResult.Ready>(drafted).items.first()
    }

    @Test
    fun `an event shows the time it is saved with`() {
        val text = "Kickoff 8 September 2027 at 9am"
        val shown = whenFields(candidate(text, ItemType.EVENT), ItemType.EVENT)
        val item = written(text, ItemType.EVENT)

        assertNotNull(shown.time)
        assertEquals(shown.date, item.start?.toLocalDate())
        assertEquals(shown.time, item.start?.toLocalTime())
    }

    @Test
    fun `a to-do shows no time, because none is saved`() {
        // SRS 1.167. §8.1: Google Tasks has no time of day, and `draftItems` sets `dueDate` alone.
        val text = "Kickoff 8 September 2027 at 9am"
        val shown = whenFields(candidate(text, ItemType.TASK), ItemType.TASK)
        val item = written(text, ItemType.TASK)

        assertNull(shown.time, "a to-do showing a time is showing what the save discards")
        assertEquals(shown.date, item.dueDate)
        assertNull(item.start, "and the write agrees: no start at all")
    }

    @Test
    fun `a range shows both ends, and the event carries both`() {
        // SRS 1.168. FR-504: the *resolved interpretation* shall be displayed, and a range's
        // interpretation is a span — showing half of one is not showing it.
        val text = "Trip from 20 September to 24 September 2027"
        val shown = whenFields(candidate(text, ItemType.EVENT), ItemType.EVENT)
        val item = written(text, ItemType.EVENT)

        assertNotNull(shown.endDate, "the closing half was missing for a whole slice")
        assertEquals(shown.date, item.start?.toLocalDate())
        // FR-502: the parser holds the end inclusive and `draftItems` converts it to Google's
        // exclusive end, so the written date is the day *after* the one shown. That difference is
        // the point of the assertion: shown and written are related, not equal.
        assertEquals(shown.endDate?.plusDays(1), item.end?.toLocalDate())
    }

    @Test
    fun `a range as a to-do shows one date, and one is saved`() {
        val text = "Trip from 20 September to 24 September 2027"
        val shown = whenFields(candidate(text, ItemType.TASK), ItemType.TASK)
        val item = written(text, ItemType.TASK)

        assertNull(shown.endDate, "a to-do has one date, not a span")
        assertNull(shown.time)
        // The badge's cost note promises the first day survives. This is that promise, checked.
        assertEquals(shown.date, item.dueDate)
    }

    @Test
    fun `an undated capture shows nothing rather than inventing something`() {
        // Design principle 1, in the one place a sheet could quietly fill a gap.
        val shown = whenFields(
            DateParser.parse("Ask about the uniform order", context).candidates.first(),
            ItemType.TASK,
        )
        assertNull(shown.date)
        assertNull(shown.time)
        assertNull(shown.endDate)
        assertNull(shown.endTime)
    }
}
