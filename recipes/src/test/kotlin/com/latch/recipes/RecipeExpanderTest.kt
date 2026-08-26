package com.latch.recipes

import com.latch.core.model.ItemType
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeExpanderTest {

    private val expander = RecipeExpander()

    @Test
    fun `FR-602 - eight recipes ship with the app`() {
        assertTrue(BuiltInRecipes.all.size >= 8, "found ${BuiltInRecipes.all.size}")
        assertEquals(
            BuiltInRecipes.all.size,
            BuiltInRecipes.all.map { it.id }.distinct().size,
            "recipe ids are stored data and must be unique",
        )
    }

    @Test
    fun `AC-06 - the meeting recipe puts prep on the preceding Thursday and says the weekend was skipped`() {
        val tuesdayMeeting = LocalDateTime.of(2026, 9, 1, 15, 0)

        val chain = expander.expand(
            recipe = BuiltInRecipes.byId(BuiltInRecipes.MEETING_WITH_PREP)!!,
            anchor = tuesdayMeeting,
            capturedTitle = "Budget review",
            chainId = "chain-1",
        )

        val prep = chain.first { it.title.startsWith("Prepare") }
        assertEquals(LocalDate.of(2026, 8, 27), prep.dueDate)
        assertEquals(2, prep.shift.skippedNonWorkingDays.size, "FR-606 needs this to say so")
    }

    @Test
    fun `every item in a chain carries the same chain id`() {
        val chain = expander.expand(
            recipe = BuiltInRecipes.byId(BuiltInRecipes.TRAVEL_BOOKING)!!,
            anchor = LocalDateTime.of(2026, 11, 14, 11, 30),
            capturedTitle = "Flight AI-202",
            chainId = "chain-2",
        )

        assertEquals(setOf("chain-2"), chain.map { it.chainId }.toSet())
        assertEquals(3, chain.size)
    }

    @Test
    fun `tasks in a chain carry a due date and no start time`() {
        val chain = expander.expand(
            recipe = BuiltInRecipes.byId(BuiltInRecipes.EXAM_OR_INTERVIEW)!!,
            anchor = LocalDateTime.of(2026, 12, 3, 9, 0),
            capturedTitle = "Physics exam",
            chainId = "chain-3",
        )

        chain.filter { it.type == ItemType.TASK }.forEach {
            assertEquals(null, it.start, "Google Tasks discards the time (§8.1)")
            assertTrue(it.dueDate != null)
        }
        chain.filter { it.type == ItemType.EVENT }.forEach {
            assertTrue(it.start != null)
        }
    }

    @Test
    fun `the captured title is substituted into every step template`() {
        val chain = expander.expand(
            recipe = BuiltInRecipes.byId(BuiltInRecipes.PAYMENT_DUE)!!,
            anchor = LocalDateTime.of(2026, 9, 5, 0, 0),
            capturedTitle = "Electricity bill",
            chainId = "chain-4",
        )

        assertTrue(chain.none { it.title.contains("{title}") })
        assertTrue(chain.all { it.title.contains("Electricity bill") })
    }
}
