package com.latch.wire

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.core.model.LatchSettings
import com.latch.parser.Classification
import com.latch.parser.DateOrder
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.recipes.BuiltInRecipes
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a date with no time becomes, asserted end to end from the text (SRS 1.179).
 *
 * **Asked as a question about behaviour and answered as tests, because the answer has three
 * parts and only one of them is `allDay`.** A bare date does not usually reach an event at all
 * — it classifies as a to-do — so "is an event with a date and no time all-day?" is a question
 * about the paths that *do* produce one: a range, and a badge the user flipped.
 *
 * The exclusive end is asserted beside the flag deliberately. `allDay = true` with an end on
 * the same day is an event Google renders as nothing at all, so the flag alone is not the
 * behaviour — it is half of it, and the half that fails silently is the other one.
 */
class AllDayTest {

    private val context = ParseContext(
        now = LocalDateTime.of(2026, 9, 7, 10, 0),
        zone = ZoneId.of("Asia/Kolkata"),
        dateOrder = DateOrder.DAY_FIRST,
    )

    private class Captured(override val text: String) : WireCapture {
        override val preferredTitle: String? = null
        override val ocrUsed: Boolean = false
    }

    private fun items(text: String, type: ItemType? = null): List<Item> {
        val parsed = DateParser.parse(text, context)
            .let { if (type == null) it else withTypeOverrides(it, mapOf(0 to type)) }
        val drafted = draftItems(
            Captured(text), parsed, context, WireDestination("cal", "tasks"), "cap", "chain",
        )
        return assertIs<DraftResult.Ready>(drafted).items
    }

    @Test
    fun `a bare date with no time is a to-do, not an all-day event`() {
        // FR-506. The premise worth stating before the all-day rule is asserted at all: this is
        // the commonest shape of all and it never reaches `eventDraft`.
        val parsed = DateParser.parse("Sports day on 8 September 2027", context)
        assertEquals(Classification.TASK_WITH_DUE_DATE, parsed.primary.classification)
        assertEquals(ItemType.TASK, items("Sports day on 8 September 2027").first().type)
    }

    @Test
    fun `a date range with no time is an all-day event, and its end is exclusive`() {
        // FR-502. The one path where a bare date becomes an event without the user asking.
        val parsed = DateParser.parse("Trip from 20 September to 24 September 2027", context)
        assertEquals(Classification.EVENT, parsed.primary.classification)

        val item = items("Trip from 20 September to 24 September 2027").first()
        assertTrue(item.allDay)
        assertEquals(LocalDateTime.of(2027, 9, 20, 0, 0), item.start)
        // Inclusive on the sheet, exclusive on the wire: the 24th is the last day covered.
        assertEquals(LocalDateTime.of(2027, 9, 25, 0, 0), item.end)
    }

    @Test
    fun `a single date overridden to an event is all-day and covers exactly one day`() {
        // FR-507. The other way a dateless-time event is reached: the user flips the badge.
        val item = items("Sports day on 8 September 2027", ItemType.EVENT).first()
        assertTrue(item.allDay, "a date with no time has nothing to put on a clock")
        assertEquals(LocalDateTime.of(2027, 9, 8, 0, 0), item.start)
        assertEquals(LocalDateTime.of(2027, 9, 9, 0, 0), item.end)
    }

    @Test
    fun `an explicit midnight is a timed event, never all-day`() {
        // The case that separates "no time was written" from "midnight was written", which is
        // the distinction `draftItems` gets right by testing the time's *absence* rather than
        // its value. Design principle 1 in miniature: 00:00 is what the writer wrote.
        for (text in listOf("Party 5 October 2027 at 12am", "Cutover 5 October 2027 at 00:00")) {
            val parsed = DateParser.parse(text, context)
            assertEquals(LocalTime.MIDNIGHT, parsed.primary.time?.value, text)

            val item = items(text, ItemType.EVENT).first()
            assertFalse(item.allDay, "$text: an explicit midnight is a time, not the absence of one")
            assertEquals(LocalDateTime.of(2027, 10, 5, 0, 0), item.start, text)
        }
    }

    @Test
    fun `a recipe's event step is all-day where the capture carried no time`() {
        // FR-601 through the other drafting path. This is the half that agrees with
        // `draftItems`; where the two disagree is SRS 1.180 and is deliberately not pinned here.
        val item = recipeEventStep("Sports day on 8 September 2027")
        assertTrue(item.allDay)
        assertEquals(LocalDateTime.of(2027, 9, 9, 0, 0), item.end)
    }

    private fun recipeEventStep(text: String): Item {
        val result = DateParser.parse(text, context)
        val recipe = BuiltInRecipes.all.first { r -> r.steps.any { it.itemType == ItemType.EVENT } }
        val planned = expandRecipe(recipe, result, LatchSettings(), emptyList(), "chain")!!
        return recipeItems(
            planned = planned,
            selected = planned.indices.toSet(),
            captureId = "cap",
            chainId = "chain",
            destination = WireDestination("cal", "tasks"),
            context = context,
        ).first { it.type == ItemType.EVENT }
    }
}
