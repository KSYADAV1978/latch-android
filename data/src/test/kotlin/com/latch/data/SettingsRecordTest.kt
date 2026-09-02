package com.latch.data

import com.latch.core.model.CaptureLayer
import com.latch.core.model.Direction
import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import com.latch.core.model.ItemType
import com.latch.core.model.MatchType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import com.latch.core.model.RoutingRule
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-1001's record and FR-603's, both of them stored and neither of them reachable through the
 * store itself from a JVM test — preferences and SQLite are throwing stubs here.
 *
 * The defaults matter as much as the round trip. FR-1003 states the per-layer defaults verbatim
 * and FR-504 states the date order; a record that decoded to something else would be a
 * behaviour change hidden in a storage layer.
 */
class SettingsRecordTest {

    @Test
    fun `the defaults are the behaviour the app shipped with`() {
        val defaults = LatchSettings()
        // FR-1003, verbatim: text selection ON, share sheet ON, tile ON, notification listener
        // OFF, screenshot watcher OFF.
        assertEquals(
            setOf(CaptureLayer.TEXT_SELECTION, CaptureLayer.SHARE_SHEET, CaptureLayer.QUICK_TILE),
            defaults.enabledLayers,
        )
        assertFalse(CaptureLayer.NOTIFICATION in defaults.enabledLayers)
        assertFalse(CaptureLayer.SCREENSHOT_WATCHER in defaults.enabledLayers)
        // FR-504: DD/MM for Indian locales.
        assertTrue(defaults.dayFirstDates)
        // FR-512's default threshold, as the parser's own ParseContext has always carried it.
        assertEquals(0.6, defaults.confidenceThreshold)
        assertEquals(Duration.ofHours(1), defaults.defaultEventDuration)
        assertEquals(DEFAULT_WORKING_DAYS, defaults.workingDays)
    }

    @Test
    fun `a record round-trips every field`() {
        val settings = LatchSettings(
            workingDays = setOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY),
            holidayAdditions = listOf(
                Holiday(LocalDate.parse("2026-09-04"), "Ganesh Chaturthi", HolidaySource.USER)
            ),
            holidayRemovals = setOf(LocalDate.parse("2026-01-26")),
            dayFirstDates = false,
            defaultEventDuration = Duration.ofMinutes(45),
            defaultReminderMinutes = listOf(10, 60),
            confidenceThreshold = 0.8,
            timeZone = "Asia/Kolkata",
            enabledLayers = setOf(CaptureLayer.TEXT_SELECTION),
            routingRules = listOf(
                RoutingRule("rule-1", MatchType.SOURCE_APP, "com.whatsapp", "work-cal", 1)
            ),
        )
        assertEquals(settings, decodeSettings(encodeSettings(settings)))
    }

    @Test
    fun `a record from a later version decodes to null, and the store then uses the defaults`() {
        val record = encodeSettings(LatchSettings()).replace("\"v\":1", "\"v\":99")
        assertNull(decodeSettings(record))
    }

    @Test
    fun `a value a later version introduced is dropped, and the rest of the record survives`() {
        // Field by field where a *value* is unrecognised, all-or-nothing only on the version:
        // losing one preference is better than losing all of them, and a layout this code does
        // not know cannot be read one field at a time either.
        val record = encodeSettings(LatchSettings(defaultReminderMinutes = listOf(30)))
            .replace("\"TEXT_SELECTION\"", "\"SOMETHING_NEW\"")
        val decoded = assertNotNull(decodeSettings(record))
        assertFalse(CaptureLayer.TEXT_SELECTION in decoded.enabledLayers)
        assertEquals(listOf(30), decoded.defaultReminderMinutes)
    }

    @Test
    fun `a working week with no working days falls back rather than hanging`() {
        // `WorkingWeek`'s own init refuses one, and the calculator's loop would never terminate
        // against it — so a record that somehow held one must not reach either.
        val record = encodeSettings(LatchSettings()).replace(
            "\"working_days\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"]",
            "\"working_days\":[]",
        )
        val decoded = assertNotNull(decodeSettings(record))
        assertTrue(decoded.workingDays.isNotEmpty())
    }

    @Test
    fun `an out-of-range threshold falls back to the default`() {
        // `Confidence`'s own init requires 0..1, so a record carrying 2.0 would throw on the
        // first parse rather than at the moment it was written.
        val record = encodeSettings(LatchSettings()).replace("\"confidence_threshold\":0.6", "\"confidence_threshold\":2")
        assertEquals(0.6, assertNotNull(decodeSettings(record)).confidenceThreshold)
    }

    @Test
    fun `malformed json decodes to null rather than throwing`() {
        assertNull(decodeSettings("{"))
    }

    // FR-605's list, composed.

    @Test
    fun `the holiday list is the bundled set plus additions minus removals`() {
        val bundled = listOf(
            Holiday(LocalDate.parse("2026-01-26"), "Republic Day", HolidaySource.BUNDLED),
            Holiday(LocalDate.parse("2026-08-15"), "Independence Day", HolidaySource.BUNDLED),
        )
        val settings = LatchSettings(
            holidayAdditions = listOf(
                Holiday(LocalDate.parse("2026-09-04"), "Ganesh Chaturthi", HolidaySource.USER)
            ),
            holidayRemovals = setOf(LocalDate.parse("2026-01-26")),
        )
        assertEquals(
            listOf(LocalDate.parse("2026-08-15"), LocalDate.parse("2026-09-04")),
            settings.holidays(bundled).map { it.date },
        )
    }
}

/** FR-603's stored recipes, and the shadowing rule that lets a built-in be "edited". */
class RecipeRecordTest {

    private val recipe = Recipe(
        id = "user.abc",
        name = "My meeting",
        builtIn = false,
        steps = listOf(
            RecipeStep(0, OffsetUnit.CALENDAR_DAYS, Direction.AFTER, ItemType.EVENT, "{title}", listOf(Duration.ofMinutes(30))),
            RecipeStep(3, OffsetUnit.WORKING_DAYS, Direction.BEFORE, ItemType.TASK, "Prepare for {title}"),
        ),
    )

    @Test
    fun `a recipe round-trips every step and its reminders`() {
        assertEquals(recipe, decodeRecipe(encodeRecipe(recipe)))
    }

    @Test
    fun `a stored recipe is never built in, even where it shadows one`() {
        // `builtIn` says where a recipe came from, and one in this table came from the user —
        // including a copy of a shipped one, which is a copy and not the original.
        val shadowing = recipe.copy(id = "builtin.meeting_prep", builtIn = true)
        assertEquals(false, assertNotNull(decodeRecipe(encodeRecipe(shadowing))).builtIn)
        assertEquals("builtin.meeting_prep", assertNotNull(decodeRecipe(encodeRecipe(shadowing))).id)
    }

    @Test
    fun `a recipe with no steps decodes to null, because it would expand to nothing`() {
        val record = encodeRecipe(recipe).replace(Regex("\"steps\":\\[.*\\]"), "\"steps\":[]")
        assertNull(decodeRecipe(record))
    }

    @Test
    fun `a step whose unit a later version introduced takes only its own recipe with it`() {
        val record = encodeRecipe(recipe).replace("\"WORKING_DAYS\"", "\"LUNAR_DAYS\"")
        assertNull(decodeRecipe(record))
    }

    @Test
    fun `a record from a later version decodes to null`() {
        assertNull(decodeRecipe(encodeRecipe(recipe).replace("\"v\":1", "\"v\":2")))
    }

    @Test
    fun `malformed json decodes to null rather than throwing`() {
        assertNull(decodeRecipe("not json"))
    }
}
