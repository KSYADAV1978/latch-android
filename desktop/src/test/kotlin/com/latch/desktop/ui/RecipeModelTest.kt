package com.latch.desktop.ui

import com.latch.core.model.DEFAULT_WORKING_DAYS
import com.latch.core.model.Direction
import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import com.latch.core.model.ItemType
import com.latch.core.model.LatchSettings
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import com.latch.desktop.store.RecipeFile
import com.latch.desktop.store.decodeRecipe
import com.latch.desktop.store.encodeRecipe
import com.latch.desktop.store.reversingSecrets
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.recipes.BuiltInRecipes
import com.latch.recipes.recipesFor
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-600 on Windows: the chooser, the chain, FR-606's sentence and FR-603's editor.
 *
 * The arithmetic is `:recipes`' and the drafting is `:wire`'s, and both are tested there — what
 * this covers is what a user reads and what a typed recipe means.
 */
class RecipeModelTest {

    private val context = ParseContext(
        now = LocalDateTime.parse("2026-09-02T09:00:00"),
        zone = ZoneId.of("Asia/Kolkata"),
    )
    private val today: LocalDate = LocalDate.parse("2026-09-02")
    private val settings = LatchSettings()

    private fun parse(text: String) = DateParser.parse(text, context)

    private val directory: File = File.createTempFile("latch-recipes", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val file = File(directory, "recipes.dat")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store() = RecipeFile(file, reversingSecrets())

    // ---- FR-601's chooser ---------------------------------------------------------------------

    @Test
    fun `a single dated capture may be expanded`() {
        val offer = recipeChooser(parse("Project sync on 8 September 2026 at 11:00"), BuiltInRecipes.all)
        assertNull(offer.blocked)
        assertTrue(offer.canChoose)
        // FR-602: at least eight ship.
        assertTrue(offer.recipes.size >= 8, offer.recipes.size.toString())
    }

    @Test
    fun `a capture holding several dates says why rather than hiding the chooser`() {
        // The narrowing SRS 1.25 recorded for FR-804, applied to FR-601 — and the *reason* is on
        // screen, because SRS 1.65 records what an absent control costs on this client: it reads
        // as though the feature does not exist.
        val many = parse("PTM 6 Sep 2027, fees 1 Oct 2027 and the trip on 10 Oct 2027")
        val offer = recipeChooser(many, BuiltInRecipes.all)
        assertFalse(offer.canChoose)
        assertTrue("one date" in assertNotNull(offer.blocked))
    }

    @Test
    fun `a capture with no date says so`() {
        val offer = recipeChooser(parse("Ask about the uniform order"), BuiltInRecipes.all)
        assertFalse(offer.canChoose)
        assertTrue("expands a date" in assertNotNull(offer.blocked))
    }

    // ---- AC-06, as the sheet reads it ---------------------------------------------------------

    @Test
    fun `AC-06 - the prep step lands on the Thursday and the sheet says the weekend was skipped`() {
        // 8 Sep 2026 is a Tuesday, which is what makes a "3 working days before" step cross a
        // weekend at all. The arithmetic is pinned in :recipes and :wire; what is pinned here is
        // that FR-606's sentence actually reaches the row.
        assertEquals(DayOfWeek.TUESDAY, LocalDate.parse("2026-09-08").dayOfWeek)

        val recipe = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        val result = parse("Project sync on 8 September 2026 at 11:00")
        val chain = assertNotNull(applyRecipe(recipe, result, settings, "chain", today))
        val model = recipeChainModel(recipe, chain.planned, chain.selected)

        val prep = assertNotNull(model.rows.firstOrNull { "Prepare" in it.title || "prep" in it.title.lowercase() })
        assertTrue("Thu 3 Sep" in prep.whenLine, prep.whenLine)
        assertTrue("2026" in prep.whenLine, prep.whenLine)
        assertTrue("Skipped" in assertNotNull(prep.skipped), prep.skipped!!)
    }

    @Test
    fun `a step that skipped nothing says nothing`() {
        // A line reading "0 days skipped" beside every ordinary step would bury the one that
        // matters, which is what FR-606 is asking to be visible.
        val recipe = Recipe(
            id = "user.x",
            name = "Same day",
            builtIn = false,
            steps = listOf(
                RecipeStep(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.EVENT, "{title}")
            ),
        )
        val chain = assertNotNull(applyRecipe(recipe, parse("Project sync on 8 September 2026 at 11:00"), settings, "c", today))
        assertNull(recipeChainModel(recipe, chain.planned, chain.selected).rows.single().skipped)
    }

    @Test
    fun `a named holiday is named rather than counted`() {
        // A user who put a holiday in their own list wants to see which one moved their step.
        val settings = LatchSettings(
            holidayAdditions = listOf(Holiday(LocalDate.parse("2026-09-07"), "Ganesh Chaturthi", HolidaySource.USER)),
        )
        val recipe = Recipe(
            id = "user.x",
            name = "Day before",
            builtIn = false,
            steps = listOf(
                RecipeStep(1, OffsetUnit.WORKING_DAYS, Direction.BEFORE, ItemType.TASK, "Prep {title}")
            ),
        )
        val chain = assertNotNull(applyRecipe(recipe, parse("Project sync on 8 September 2026 at 11:00"), settings, "c", today))
        val row = recipeChainModel(recipe, chain.planned, chain.selected).rows.single()
        assertTrue("Ganesh Chaturthi" in assertNotNull(row.skipped), row.skipped!!)
    }

    @Test
    fun `FR-608 unticking every step takes Save away rather than writing nothing`() {
        val recipe = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        val chain = assertNotNull(applyRecipe(recipe, parse("Project sync on 8 September 2026 at 11:00"), settings, "c", today))
        assertTrue(recipeChainModel(recipe, chain.planned, chain.selected).canSave)
        assertFalse(recipeChainModel(recipe, chain.planned, emptySet()).canSave)
    }

    @Test
    fun `every step starts ticked, which is what a deselection departs from`() {
        val recipe = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        val chain = assertNotNull(applyRecipe(recipe, parse("Project sync on 8 September 2026 at 11:00"), settings, "c", today))
        assertEquals(chain.planned.indices.toSet(), chain.selected)
        assertTrue(recipeChainModel(recipe, chain.planned, chain.selected).rows.all { it.checked })
    }

    @Test
    fun `an all-day capture expands to all-day rows and offers no time`() {
        // A capture with no time becomes an all-day event rather than a meeting at 00:00 — so
        // the line must not offer a time either.
        val recipe = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        val chain = assertNotNull(applyRecipe(recipe, parse("Project sync on 8 September 2026"), settings, "c", today))
        val rows = recipeChainModel(recipe, chain.planned, chain.selected).rows
        assertTrue(rows.none { ":" in it.whenLine.substringAfter(",", "") }, rows.map { it.whenLine }.toString())
    }

    @Test
    fun `an explicit midnight keeps its time on the row`() {
        // SRS 1.190's third site. The when-line read the start's clock value, so a capture
        // written `at 12am` was displayed — and saved — as an all-day row. It reads the fact
        // the drafting step reads now, so the screen and the write cannot disagree either.
        val recipe = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        val chain = assertNotNull(applyRecipe(recipe, parse("Cutover on 8 September 2026 at 00:00"), settings, "c", today))
        val rows = recipeChainModel(recipe, chain.planned, chain.selected).rows
        assertTrue(
            rows.any { it.whenLine.endsWith("00:00") },
            rows.map { it.whenLine }.toString(),
        )
    }

    @Test
    fun `a capture a recipe cannot be applied to expands to nothing`() {
        val recipe = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        assertNull(applyRecipe(recipe, parse("Ask about the uniform order"), settings, "c", today))
    }

    @Test
    fun `FR-605 a six-day week moves the step`() {
        val recipe = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        val result = parse("Project sync on 8 September 2026 at 11:00")
        val six = LatchSettings(workingDays = DEFAULT_WORKING_DAYS + DayOfWeek.SATURDAY)

        val five = assertNotNull(applyRecipe(recipe, result, settings, "c", today))
        val sixDay = assertNotNull(applyRecipe(recipe, result, six, "c", today))
        assertFalse(
            recipeChainModel(recipe, five.planned, five.selected).rows.map { it.whenLine } ==
                recipeChainModel(recipe, sixDay.planned, sixDay.selected).rows.map { it.whenLine },
            "the working week made no difference to a working-day step",
        )
    }

    // ---- FR-603's editor ----------------------------------------------------------------------

    @Test
    fun `a recipe survives the editor unchanged`() {
        val recipe = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        val applied = applyRecipeForm(formOf(recipe))
        val saved = assertNotNull(applied.recipe)
        assertEquals(recipe.id, saved.id)
        assertEquals(recipe.name, saved.name)
        assertEquals(recipe.steps, saved.steps)
        // A stored recipe is never built-in, whatever it was a copy of — the flag says where it
        // came from, and this one came from the user even though its id did not.
        assertFalse(saved.builtIn)
    }

    @Test
    fun `every way a recipe can be wrong is reported at once`() {
        val broken = RecipeForm(
            id = "user.x",
            name = "  ",
            steps = listOf(
                RecipeStepForm("-3", OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, ItemType.TASK, " ", "soon")
            ),
        )
        val applied = applyRecipeForm(broken)
        assertNull(applied.recipe)
        assertEquals(
            setOf(
                RecipeProblem.NO_NAME,
                RecipeProblem.OFFSET,
                RecipeProblem.NO_TITLE,
                RecipeProblem.REMINDERS,
            ),
            applied.problems.toSet(),
        )
    }

    @Test
    fun `a negative offset is refused rather than clamped in the editor`() {
        // `withOffset` clamps, because a spinner that will not go below zero is a control the
        // user can feel. A *typed* negative is different: silently turning "-3 before" into
        // "0 before" would move a step by three days without saying so.
        val form = RecipeForm(
            "user.x", "Ok",
            listOf(RecipeStepForm("-1", OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, ItemType.TASK, "{title}", "")),
        )
        assertEquals(listOf(RecipeProblem.OFFSET), applyRecipeForm(form).problems)
    }

    @Test
    fun `a recipe with no steps is refused`() {
        assertEquals(
            listOf(RecipeProblem.NO_STEPS),
            applyRecipeForm(RecipeForm("user.x", "Empty", emptyList())).problems,
        )
    }

    @Test
    fun `a step's reminders are minutes, and blank means the calendar's own`() {
        val form = RecipeForm(
            "user.x", "Ok",
            listOf(RecipeStepForm("0", OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.EVENT, "{title}", "30, 1440")),
        )
        val saved = assertNotNull(applyRecipeForm(form).recipe)
        assertEquals(listOf(Duration.ofMinutes(30), Duration.ofMinutes(1440)), saved.steps.single().reminderOffsets)

        val bare = form.copy(steps = listOf(form.steps.single().copy(reminders = "")))
        assertEquals(emptyList(), assertNotNull(applyRecipeForm(bare).recipe).steps.single().reminderOffsets)
    }

    @Test
    fun `every problem has a sentence of its own`() {
        val sentences = RecipeProblem.entries.map(::recipeProblemText)
        assertEquals(sentences.size, sentences.toSet().size)
        assertTrue(sentences.none { it.isBlank() })
    }

    @Test
    fun `the list says where each recipe came from, and what deleting it would do`() {
        val shipped = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        val edited = shipped.copy(name = "My meeting prep", builtIn = false)
        val own = Recipe(
            "user.x", "Mine", false,
            steps = listOf(RecipeStep(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.TASK, "{title}")),
        )

        assertEquals("Shipped with Latch", recipeOrigin(shipped, emptyList()))
        assertEquals("Shipped with Latch, edited by you", recipeOrigin(edited, listOf(edited)))
        assertEquals("Yours", recipeOrigin(own, listOf(own)))

        // FR-603's delete is two actions behind one word, so the button says which.
        assertEquals("Restore the shipped version", deleteLabelFor(edited, listOf(edited)))
        assertEquals("Delete", deleteLabelFor(own, listOf(own)))
    }

    // ---- FR-603's store -----------------------------------------------------------------------

    @Test
    fun `a recipe survives the record and the file`() {
        val recipe = Recipe(
            id = "user.x",
            name = "Renewal chase",
            builtIn = false,
            targetCalendarId = "cal-1",
            steps = listOf(
                RecipeStep(3, OffsetUnit.WORKING_DAYS, Direction.BEFORE, ItemType.TASK, "Chase {title}", listOf(Duration.ofMinutes(30))),
                RecipeStep(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.EVENT, "{title}"),
            ),
        )
        assertEquals(recipe, decodeRecipe(encodeRecipe(recipe)))

        val store = store()
        store.save(recipe)
        assertEquals(listOf(recipe), store().all())
    }

    @Test
    fun `the recipe's own words are not in the file in the clear`() {
        // A recipe name and its title templates are text the user wrote.
        store().save(
            Recipe(
                "user.x", "Board papers", false,
                steps = listOf(RecipeStep(1, OffsetUnit.CALENDAR_DAYS, Direction.BEFORE, ItemType.TASK, "Read the board papers")),
            )
        )
        assertFalse("Board papers" in file.readText())
    }

    @Test
    fun `editing a built-in shadows it, and deleting the copy restores the shipped one`() {
        // FR-603's whole shape in one test. The condition that would make it fail is a store
        // that minted a new id for an edit, which would leave nine rows where there were eight.
        val shipped = assertNotNull(BuiltInRecipes.byId("builtin.meeting_prep"))
        val store = store()
        store.save(shipped.copy(name = "My meeting prep", builtIn = false))

        val withEdit = recipesFor(BuiltInRecipes.all, store.all())
        assertEquals(BuiltInRecipes.all.size, withEdit.size, "the edit added a row instead of shadowing one")
        assertEquals("My meeting prep", withEdit.first { it.id == shipped.id }.name)

        store.delete(shipped.id)
        assertEquals(shipped, recipesFor(BuiltInRecipes.all, store.all()).first { it.id == shipped.id })
    }

    @Test
    fun `a recipe this build cannot read is dropped, and its neighbours survive`() {
        // The opposite of the queue and the Inbox, and rightly: a recipe holds no capture. One
        // that cannot be read is a row the user could neither apply nor edit.
        val ok = Recipe(
            "user.a", "Fine", false,
            steps = listOf(RecipeStep(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.TASK, "{title}")),
        )
        store().save(ok)
        file.appendText("\nQkFE")
        assertEquals(listOf(ok), RecipeFile(file, reversingSecrets(refuses = setOf("QkFE"))).all())
    }

    @Test
    fun `a record from another version, or with no steps, decodes to nothing`() {
        val recipe = Recipe(
            "user.x", "X", false,
            steps = listOf(RecipeStep(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.TASK, "{title}")),
        )
        assertNull(decodeRecipe(encodeRecipe(recipe).replace("\"v\":1", "\"v\":2")))
        assertNull(decodeRecipe(encodeRecipe(recipe).replace("CALENDAR_DAYS", "FORTNIGHTS")))
        assertNull(decodeRecipe("{\"v\":1,\"id\":\"x\",\"steps\":[]}"))
        assertNull(decodeRecipe("{"))
    }

    @Test
    fun `saving a recipe twice replaces it rather than adding a second`() {
        val store = store()
        val recipe = Recipe(
            "user.x", "One", false,
            steps = listOf(RecipeStep(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.TASK, "{title}")),
        )
        store.save(recipe)
        store.save(recipe.copy(name = "Two"))
        assertEquals(listOf("Two"), store().all().map { it.name })
    }

    @Test
    fun `order is the order they were first written, so editing does not move a row`() {
        val store = store()
        fun recipe(id: String, name: String) = Recipe(
            id, name, false,
            steps = listOf(RecipeStep(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.TASK, "{title}")),
        )
        store.save(recipe("user.a", "A"))
        store.save(recipe("user.b", "B"))
        store.save(recipe("user.a", "A edited"))
        assertEquals(listOf("A edited", "B"), store().all().map { it.name })
    }

    @Test
    fun `NFR-205 clear leaves nothing behind`() {
        val store = store()
        store.save(
            Recipe(
                "user.x", "X", false,
                steps = listOf(RecipeStep(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.TASK, "{title}")),
            )
        )
        store.clear()
        assertFalse(file.exists())
        assertEquals(emptyList(), store.all())
    }
}
