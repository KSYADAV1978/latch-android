package com.latch.android.capture

import com.latch.android.recipes.duplicateOf
import com.latch.android.recipes.editableCopyOf
import com.latch.android.recipes.isShippedUnedited
import com.latch.android.recipes.newRecipe
import com.latch.android.recipes.withOffset
import com.latch.core.model.Direction
import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.data.LatchSettings
import com.latch.data.recipesFor
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.recipes.BuiltInRecipes
import com.latch.recipes.IndianHolidays
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-601 to FR-608, and AC-06.
 *
 * The arithmetic is `:recipes`' and is tested there; what this covers is the wiring `:app`
 * owns — which date anchors a chain, where its items go (FR-607), what a deselection does
 * (FR-608), and what FR-606 has to be told in order to say "weekend skipped".
 */
class RecipeChainTest {

    private val context = ParseContext(
        now = LocalDateTime.parse("2026-09-02T09:00:00"),
        zone = ZoneId.of("Asia/Kolkata"),
    )

    private val defaults = AccountDefaults(
        accountId = "acct",
        email = "you@example.com",
        routingMode = RoutingMode.LATCH_CALENDAR,
        destinationCalendarId = "latch-cal",
        destinationCalendarName = "Latch",
        destinationCalendarColour = "#d50000",
        taskListId = "list-1",
    )

    private val settings = LatchSettings()

    private fun parse(text: String) = DateParser.parse(text, context)

    // ----- AC-06 -----

    @Test
    fun `AC-06 - a prep step three working days before a Tuesday lands on the Thursday`() {
        // "Apply 'Meeting + prep' to a Tuesday meeting with a '3 working days before' step →
        // Prep item lands on the preceding Thursday; UI states that the weekend was skipped."
        val text = "Project sync on 8 September 2026 at 11:00"
        // The condition that makes this check mean anything: 8 Sep 2026 must be a Tuesday, or
        // the weekend is never crossed and the test passes for the wrong reason.
        assertEquals(DayOfWeek.TUESDAY, LocalDate.parse("2026-09-08").dayOfWeek)

        val planned = assertNotNull(
            expandRecipe(
                recipe = assertNotNull(BuiltInRecipes.byId(BuiltInRecipes.MEETING_WITH_PREP)),
                result = parse(text),
                settings = settings,
                bundledHolidays = emptyList(),
                chainId = "chain",
            )
        )

        val prep = planned.single { it.step.offsetUnit == OffsetUnit.WORKING_DAYS }
        assertEquals(LocalDate.parse("2026-09-03"), prep.dueDate)
        assertEquals(DayOfWeek.THURSDAY, prep.dueDate?.dayOfWeek)
    }

    @Test
    fun `AC-06's second half - FR-606 is told the weekend was skipped`() {
        val planned = assertNotNull(
            expandRecipe(
                recipe = assertNotNull(BuiltInRecipes.byId(BuiltInRecipes.MEETING_WITH_PREP)),
                result = parse("Project sync on 8 September 2026 at 11:00"),
                settings = settings,
                bundledHolidays = emptyList(),
                chainId = "chain",
            )
        )
        val prep = planned.single { it.step.offsetUnit == OffsetUnit.WORKING_DAYS }
        val skipped = skippedDaysOf(prep)

        assertTrue(skipped.any, "FR-606 has nothing to say unless the shift reports what it stepped over")
        assertEquals(2, skipped.nonWorkingDays, "the Saturday and the Sunday")
        assertTrue(skipped.holidays.isEmpty())
    }

    @Test
    fun `a step that skipped nothing says nothing`() {
        // A note about nothing is worse than no note: a user who reads "weekend skipped" on a
        // shift that crossed no weekend will stop believing the line.
        val planned = assertNotNull(
            expandRecipe(
                recipe = assertNotNull(BuiltInRecipes.byId(BuiltInRecipes.MEETING_WITH_PREP)),
                result = parse("Project sync on 8 September 2026 at 11:00"),
                settings = settings,
                bundledHolidays = emptyList(),
                chainId = "chain",
            )
        )
        val followUp = planned.single {
            it.step.direction == Direction.AFTER && it.step.itemType == ItemType.TASK
        }
        assertFalse(skippedDaysOf(followUp).any)
    }

    // ----- FR-605: the working week and the holiday list -----

    @Test
    fun `a six-day working week moves the answer, which is what FR-605 is for`() {
        // A six-day week is common in the target market, and this is the whole reason the
        // working week is configurable rather than a Mon–Fri constant.
        val sixDay = settings.copy(workingDays = settings.workingDays + DayOfWeek.SATURDAY)
        val planned = assertNotNull(
            expandRecipe(
                recipe = assertNotNull(BuiltInRecipes.byId(BuiltInRecipes.MEETING_WITH_PREP)),
                result = parse("Project sync on 8 September 2026 at 11:00"),
                settings = sixDay,
                bundledHolidays = emptyList(),
                chainId = "chain",
            )
        )
        val prep = planned.single { it.step.offsetUnit == OffsetUnit.WORKING_DAYS }
        // Sat 5, Fri 4, Thu 3 are the three working days back, so the Saturday now counts and
        // the answer moves a day later than the five-day week gave.
        assertEquals(LocalDate.parse("2026-09-04"), prep.dueDate)
    }

    @Test
    fun `a holiday is stepped over and named`() {
        val holiday = Holiday(LocalDate.parse("2026-09-04"), "Ganesh Chaturthi", HolidaySource.USER)
        val planned = assertNotNull(
            expandRecipe(
                recipe = assertNotNull(BuiltInRecipes.byId(BuiltInRecipes.MEETING_WITH_PREP)),
                result = parse("Project sync on 8 September 2026 at 11:00"),
                settings = settings.copy(holidayAdditions = listOf(holiday)),
                bundledHolidays = emptyList(),
                chainId = "chain",
            )
        )
        val prep = planned.single { it.step.offsetUnit == OffsetUnit.WORKING_DAYS }
        assertEquals(LocalDate.parse("2026-09-02"), prep.dueDate)
        assertEquals(listOf("Ganesh Chaturthi"), skippedDaysOf(prep).holidays)
    }

    @Test
    fun `a removed bundled holiday stops being one`() {
        // FR-605: "permit user additions and removals". A removal is by date, because a
        // bundled holiday has no id the user could name.
        val bundled = IndianHolidays.fixedDateHolidays(2026)
        val republicDay = LocalDate.parse("2026-01-26")
        assertTrue(bundled.any { it.date == republicDay }, "the fixture must genuinely bundle it")

        val kept = settings.holidays(bundled)
        assertTrue(kept.any { it.date == republicDay })

        val removed = settings.copy(holidayRemovals = setOf(republicDay)).holidays(bundled)
        assertFalse(removed.any { it.date == republicDay })
    }

    // ----- FR-601's narrowing -----

    @Test
    fun `a recipe is not offered for a capture holding several dates`() {
        // The same narrowing SRS 1.25 took for FR-804, and for the same reason: an action
        // defined over one date cannot be applied to four without answering which anchors it.
        val result = parse("Invoice dated 20 September 2027, review on 24 September 2027")
        assertTrue(result.candidates.size > 1, "the fixture must genuinely hold several dates")
        assertEquals(RecipeBlocker.SEVERAL_DATES, recipeBlocker(result))
        assertNull(
            expandRecipe(BuiltInRecipes.all.first(), result, settings, emptyList(), "chain")
        )
    }

    @Test
    fun `a recipe is not offered for a capture with no date to expand`() {
        val result = parse("Ask about the uniform order")
        assertEquals(RecipeBlocker.NO_DATE, recipeBlocker(result))
    }

    @Test
    fun `an ordinary single dated capture may have one`() {
        assertNull(recipeBlocker(parse("Project sync on 8 September 2026 at 11:00")))
    }

    // ----- FR-607 and FR-608 -----

    private fun plannedFor(text: String, recipeId: String = BuiltInRecipes.MEETING_WITH_PREP) =
        assertNotNull(
            expandRecipe(
                recipe = assertNotNull(BuiltInRecipes.byId(recipeId)),
                result = parse(text),
                settings = settings,
                bundledHolidays = emptyList(),
                chainId = "chain",
            )
        )

    @Test
    fun `FR-607 - every item of a chain goes to the same calendar`() {
        val planned = plannedFor("Project sync on 8 September 2026 at 11:00")
        val items = recipeItems(
            planned = planned,
            selected = planned.indices.toSet(),
            captureId = "capture",
            chainId = "chain",
            defaults = defaults,
            context = context,
        )
        assertEquals(3, items.size)
        items.filter { it.type == ItemType.EVENT }.forEach {
            assertEquals("latch-cal", it.calendarId)
        }
        items.filter { it.type == ItemType.TASK }.forEach {
            assertEquals("list-1", it.taskListId)
        }
        assertEquals(setOf("chain"), items.map { it.chainId }.toSet())
    }

    @Test
    fun `FR-608 - a deselected step is not written`() {
        val planned = plannedFor("Project sync on 8 September 2026 at 11:00")
        val items = recipeItems(
            planned = planned,
            selected = setOf(0, 2),
            captureId = "capture",
            chainId = "chain",
            defaults = defaults,
            context = context,
        )
        assertEquals(2, items.size)
        assertFalse(items.any { it.title.startsWith("Prepare for") })
    }

    @Test
    fun `deselecting everything produces nothing rather than an empty chain`() {
        val planned = plannedFor("Project sync on 8 September 2026 at 11:00")
        assertTrue(
            recipeItems(planned, emptySet(), "capture", "chain", defaults, context).isEmpty()
        )
    }

    @Test
    fun `an event step keeps the captured time`() {
        val items = recipeItems(
            planned = plannedFor("Project sync on 8 September 2026 at 11:00"),
            selected = setOf(0),
            captureId = "capture",
            chainId = "chain",
            defaults = defaults,
            context = context,
        )
        val event = items.single()
        assertEquals(LocalDateTime.parse("2026-09-08T11:00"), event.start)
        assertEquals(LocalDateTime.parse("2026-09-08T12:00"), event.end)
        assertFalse(event.allDay)
    }

    @Test
    fun `an anchor with no time expands to an all-day event, not a meeting at midnight`() {
        val items = recipeItems(
            planned = plannedFor("Project sync on 8 September 2026"),
            selected = setOf(0),
            captureId = "capture",
            chainId = "chain",
            defaults = defaults,
            context = context,
        )
        val event = items.single()
        assertTrue(event.allDay)
        // Google reads an all-day end as exclusive.
        assertEquals(LocalDateTime.parse("2026-09-09T00:00"), event.end)
    }

    @Test
    fun `a step's own reminders reach the item, and a task's do not`() {
        val items = recipeItems(
            planned = plannedFor("Project sync on 8 September 2026 at 11:00"),
            selected = setOf(0, 1),
            captureId = "capture",
            chainId = "chain",
            defaults = defaults,
            context = context,
            defaultReminderMinutes = listOf(15),
        )
        val event = items.single { it.type == ItemType.EVENT }
        val task = items.single { it.type == ItemType.TASK }
        // The built-in asks for 30 minutes, which outranks the global default.
        assertEquals(listOf(30), event.reminderMinutes)
        // Google Tasks has no reminder of its own (§8.1 one field over).
        assertTrue(task.reminderMinutes.isEmpty())
    }

    @Test
    fun `the default lead time applies only where a step names none`() {
        val recipe = Recipe(
            id = "user.test",
            name = "Test",
            builtIn = false,
            steps = listOf(
                RecipeStep(
                    offsetValue = 0,
                    offsetUnit = OffsetUnit.CALENDAR_DAYS,
                    direction = Direction.AFTER,
                    itemType = ItemType.EVENT,
                    titleTemplate = "{title}",
                    reminderOffsets = emptyList(),
                )
            ),
        )
        val planned = assertNotNull(
            expandRecipe(recipe, parse("Project sync on 8 September 2026 at 11:00"), settings, emptyList(), "chain")
        )
        val items = recipeItems(planned, setOf(0), "capture", "chain", defaults, context, listOf(15))
        assertEquals(listOf(15), items.single().reminderMinutes)
    }

    // ----- FR-602 and FR-603 -----

    @Test
    fun `FR-602 - eight built-in recipes ship`() {
        assertTrue(BuiltInRecipes.all.size >= 8, "FR-602 names eight areas and asks for at least that many")
        assertEquals(
            BuiltInRecipes.all.size,
            BuiltInRecipes.all.map { it.id }.toSet().size,
            "ids are stored data once a user edits a copy, so they must be unique",
        )
    }

    @Test
    fun `a stored recipe carrying a built-in's id shadows it, keeping its place in the list`() {
        val edited = BuiltInRecipes.all.first().copy(name = "My meeting", builtIn = false)
        val offered = recipesFor(BuiltInRecipes.all, listOf(edited))

        assertEquals(BuiltInRecipes.all.size, offered.size, "shadowing replaces, it does not append")
        assertEquals("My meeting", offered.first().name)
        assertEquals(BuiltInRecipes.all.first().id, offered.first().id)
    }

    @Test
    fun `a recipe of the user's own is appended after the built-ins`() {
        val own = newRecipe("Mine", "{title}")
        val offered = recipesFor(BuiltInRecipes.all, listOf(own))
        assertEquals(BuiltInRecipes.all.size + 1, offered.size)
        assertEquals(own.id, offered.last().id)
    }

    @Test
    fun `duplicating always mints a new id, so the copy sits beside the original`() {
        // A duplicate that kept the id would *shadow* the original rather than sit beside it,
        // which is what edit means here and the opposite of what the user asked for.
        val source = BuiltInRecipes.all.first()
        val copy = duplicateOf(source, " (copy)")
        assertTrue(copy.id != source.id)
        assertFalse(copy.builtIn)
        assertEquals(source.steps, copy.steps)
    }

    @Test
    fun `editing a built-in keeps its id and stops calling itself built in`() {
        val edited = editableCopyOf(BuiltInRecipes.all.first())
        assertEquals(BuiltInRecipes.all.first().id, edited.id)
        assertFalse(edited.builtIn, "the flag says where a recipe came from, and this came from the user")
    }

    @Test
    fun `a shipped recipe is recognised as shipped until it is changed`() {
        assertTrue(isShippedUnedited(BuiltInRecipes.all.first()))
        assertFalse(isShippedUnedited(BuiltInRecipes.all.first().copy(name = "Changed")))
        assertFalse(isShippedUnedited(newRecipe("Mine", "{title}")))
    }

    @Test
    fun `a new recipe opens on something rather than on nothing`() {
        val recipe = newRecipe("My recipe", "{title}")
        assertEquals(1, recipe.steps.size)
        assertFalse(recipe.builtIn)
    }

    @Test
    fun `a negative offset is clamped rather than reaching the calculator`() {
        // `WorkingDayCalculator` refuses one outright — direction is a separate field — so a
        // number typed into the editor that reached it would throw inside a save.
        val step = newRecipe("x", "{title}").steps.first()
        assertEquals(0, withOffset(step, -3).offsetValue)
        assertEquals(3, withOffset(step, 3).offsetValue)
    }

    @Test
    fun `every built-in expands without throwing, on a plain dated capture`() {
        // The cheapest guard there is against a shipped recipe nobody tried: FR-602 names eight
        // and a step with a bad offset would fail inside a save rather than here.
        val result = parse("Project sync on 8 September 2026 at 11:00")
        BuiltInRecipes.all.forEach { recipe ->
            val planned = assertNotNull(
                expandRecipe(recipe, result, settings, emptyList(), "chain"),
                "${recipe.id} produced no chain",
            )
            assertTrue(planned.isNotEmpty(), "${recipe.id} expanded to nothing")
            val items = recipeItems(planned, planned.indices.toSet(), "capture", "chain", defaults, context)
            assertEquals(planned.size, items.size, "${recipe.id} lost a step on the way to items")
        }
    }

    @Test
    fun `a title template substitutes the captured title`() {
        val planned = plannedFor("Project sync on 8 September 2026 at 11:00")
        val prep = planned.single { it.step.offsetUnit == OffsetUnit.WORKING_DAYS }
        assertTrue(prep.title.startsWith("Prepare for "), prep.title)
        assertFalse(prep.title.contains("{title}"), "the placeholder should be gone")
    }

    @Test
    fun `the default event duration is the one from the parse context`() {
        val twoHours = context.copy(defaultEventDuration = Duration.ofHours(2))
        val items = recipeItems(
            planned = plannedFor("Project sync on 8 September 2026 at 11:00"),
            selected = setOf(0),
            captureId = "capture",
            chainId = "chain",
            defaults = defaults,
            context = twoHours,
        )
        assertEquals(LocalDateTime.parse("2026-09-08T13:00"), items.single().end)
    }
}
