package com.latch.desktop.ui

import com.latch.core.model.DEFAULT_WORKING_DAYS
import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import com.latch.core.model.LatchSettings
import com.latch.core.model.MatchType
import com.latch.core.model.RoutingRule
import com.latch.desktop.store.DesktopSettings
import com.latch.desktop.store.decodeDesktopSettings
import com.latch.desktop.store.encodeDesktopSettings
import com.latch.parser.DateOrder
import com.latch.wire.parseContextFor
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-1001 on Windows: what a typed form means, and what a stored record survives.
 *
 * The window collects characters and this is everything that decides what they are worth, so
 * every refusal a user can meet is asserted here rather than discovered on a screen.
 */
class SettingsModelTest {

    private val defaults = DesktopSettings()

    private fun form(
        hotkey: String = "Ctrl+Shift+K",
        dayFirst: Boolean = true,
        duration: String = "60",
        reminders: String = "",
        threshold: String = "60",
        days: Set<DayOfWeek> = DEFAULT_WORKING_DAYS,
        zone: String = "",
    ) = SettingsForm(hotkey, dayFirst, duration, reminders, threshold, days, zone)

    // ---- the round trip through the window --------------------------------------------------

    @Test
    fun `the form a record produces saves back to the same record`() {
        // The condition that would make this fail is a field the form drops on the way out or
        // on the way back — which on a settings screen looks like a preference that silently
        // reverts, and is the hardest kind to notice.
        val stored = DesktopSettings(
            hotkey = "Ctrl+Alt+F9",
            shared = LatchSettings(
                workingDays = DEFAULT_WORKING_DAYS + DayOfWeek.SATURDAY,
                dayFirstDates = false,
                defaultEventDuration = Duration.ofMinutes(45),
                defaultReminderMinutes = listOf(10, 60),
                confidenceThreshold = 0.75,
                timeZone = "Asia/Kolkata",
            ),
        )
        val applied = applyForm(defaults, formOf(stored))
        assertEquals(stored, assertNotNull(applied.settings))
    }

    @Test
    fun `a field this client does not offer is carried through rather than cleared`() {
        // FR-905's rules and FR-1003's toggles are on the shared record because the phone needs
        // them. A Save here that rebuilt the record from the form would wipe them, and the user
        // would find out on their phone.
        val current = defaults.copy(
            shared = defaults.shared.copy(
                routingRules = listOf(RoutingRule("r", MatchType.KEYWORD, "fees", "cal", 1)),
                monitoredPackages = setOf("com.whatsapp"),
                webhookEnabled = true,
            ),
        )
        val saved = assertNotNull(applyForm(current, form(threshold = "70")).settings)
        assertEquals(current.shared.routingRules, saved.shared.routingRules)
        assertEquals(setOf("com.whatsapp"), saved.shared.monitoredPackages)
        assertTrue(saved.shared.webhookEnabled)
        assertEquals(0.7, saved.shared.confidenceThreshold)
    }

    // ---- FR-302's hotkey ----------------------------------------------------------------------

    @Test
    fun `a hotkey is stored in its display form, not as it was typed`() {
        val saved = assertNotNull(applyForm(defaults, form(hotkey = "  control + shift + k ")).settings)
        assertEquals("Ctrl+Shift+K", saved.hotkey)
    }

    @Test
    fun `a hotkey with no modifier is refused, and says why`() {
        // Windows would register a bare K happily, at which point the letter K stops working in
        // every application on the machine. The refusal is the feature.
        val applied = applyForm(defaults, form(hotkey = "K"))
        assertNull(applied.settings)
        assertEquals(listOf(SettingsProblem.HOTKEY_NO_MODIFIER), applied.problems)
        assertTrue("Ctrl" in settingsProblemText(SettingsProblem.HOTKEY_NO_MODIFIER))
    }

    @Test
    fun `every way a hotkey can be wrong has its own refusal`() {
        assertEquals(listOf(SettingsProblem.HOTKEY_EMPTY), applyForm(defaults, form(hotkey = "")).problems)
        assertEquals(listOf(SettingsProblem.HOTKEY_NO_KEY), applyForm(defaults, form(hotkey = "Ctrl+Shift")).problems)
        assertEquals(
            listOf(SettingsProblem.HOTKEY_UNKNOWN_KEY),
            applyForm(defaults, form(hotkey = "Ctrl+Pause")).problems,
        )
        assertEquals(
            listOf(SettingsProblem.HOTKEY_TOO_MANY_KEYS),
            applyForm(defaults, form(hotkey = "Ctrl+K+J")).problems,
        )
    }

    @Test
    fun `the registration is redone only when the combination actually changed`() {
        // Re-registering stops a sidecar and starts another, and the new combination may be
        // held by something else — so doing it for an unrelated Save could leave the user with
        // no shortcut at all.
        val before = defaults
        val sameKey = assertNotNull(applyForm(before, form(threshold = "80")).settings)
        assertFalse(hotkeyChanged(before, sameKey))

        val newKey = assertNotNull(applyForm(before, form(hotkey = "Ctrl+Alt+J")).settings)
        assertTrue(hotkeyChanged(before, newKey))
    }

    // ---- the rest of the form -------------------------------------------------------------------

    @Test
    fun `every problem is reported, not just the first`() {
        // A form that reported one error at a time makes a user with two typos press Save twice
        // to find out about the second.
        val applied = applyForm(defaults, form(hotkey = "K", duration = "x", threshold = "900", zone = "Mars/Olympus"))
        assertNull(applied.settings)
        assertEquals(
            setOf(
                SettingsProblem.HOTKEY_NO_MODIFIER,
                SettingsProblem.DURATION,
                SettingsProblem.THRESHOLD,
                SettingsProblem.TIME_ZONE,
            ),
            applied.problems.toSet(),
        )
    }

    @Test
    fun `nothing is saved when anything is wrong`() {
        assertNull(applyForm(defaults, form(dayFirst = false, duration = "0")).settings)
    }

    @Test
    fun `a zero-length default event is refused`() {
        assertEquals(listOf(SettingsProblem.DURATION), applyForm(defaults, form(duration = "0")).problems)
        assertEquals(listOf(SettingsProblem.DURATION), applyForm(defaults, form(duration = "-30")).problems)
        assertEquals(listOf(SettingsProblem.DURATION), applyForm(defaults, form(duration = "2000")).problems)
        assertEquals(
            Duration.ofMinutes(1440),
            assertNotNull(applyForm(defaults, form(duration = "1440")).settings).shared.defaultEventDuration,
        )
    }

    @Test
    fun `an empty reminder list means the calendar's own defaults, not no reminders`() {
        // `:wire` omits the override entirely for an empty list, which leaves Google's defaults
        // in force. An empty *override* would mean "no reminders at all" — a different
        // instruction, and one this app has never taken on a user's behalf.
        assertEquals(emptyList(), remindersOf(""))
        assertEquals(emptyList(), remindersOf("   "))
        assertEquals(
            emptyList(),
            assertNotNull(applyForm(defaults, form(reminders = "")).settings).shared.defaultReminderMinutes,
        )
    }

    @Test
    fun `reminders are read as minutes, sorted, de-duplicated, and refused when they are not numbers`() {
        assertEquals(listOf(10, 30, 1440), remindersOf("1440, 10, 30, 10"))
        assertEquals(listOf(0), remindersOf("0"))
        assertNull(remindersOf("half an hour"))
        assertNull(remindersOf("30, tomorrow"))
        assertNull(remindersOf("-5"))
        assertEquals(listOf(SettingsProblem.REMINDERS), applyForm(defaults, form(reminders = "soon")).problems)
    }

    @Test
    fun `FR-605 a week with no working days is refused rather than stored`() {
        // `WorkingWeek` throws on construction for an empty week — one with no working days
        // never terminates — so refusing it here is where the user can read why.
        val applied = applyForm(defaults, form(days = emptySet()))
        assertEquals(listOf(SettingsProblem.WORKING_WEEK), applied.problems)
    }

    @Test
    fun `FR-605 a six-day week is a first-class answer`() {
        val six = DEFAULT_WORKING_DAYS + DayOfWeek.SATURDAY
        assertEquals(six, assertNotNull(applyForm(defaults, form(days = six)).settings).shared.workingDays)
    }

    @Test
    fun `an empty time zone means follow this PC`() {
        assertNull(assertNotNull(applyForm(defaults, form(zone = "  ")).settings).shared.timeZone)
    }

    @Test
    fun `an unknown time zone is refused rather than silently ignored`() {
        assertEquals(listOf(SettingsProblem.TIME_ZONE), applyForm(defaults, form(zone = "IST")).problems)
        assertEquals(
            "Asia/Kolkata",
            assertNotNull(applyForm(defaults, form(zone = "Asia/Kolkata")).settings).shared.timeZone,
        )
    }

    // ---- the record on disk ---------------------------------------------------------------------

    @Test
    fun `every stored field survives the record`() {
        val settings = DesktopSettings(
            hotkey = "Ctrl+Alt+F9",
            shared = LatchSettings(
                workingDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY),
                holidayAdditions = listOf(Holiday(LocalDate.parse("2027-01-26"), "Republic Day", HolidaySource.USER)),
                holidayRemovals = setOf(LocalDate.parse("2027-08-15")),
                dayFirstDates = false,
                defaultEventDuration = Duration.ofMinutes(45),
                defaultReminderMinutes = listOf(10, 60),
                confidenceThreshold = 0.75,
                timeZone = "Asia/Kolkata",
                webhookEnabled = true,
            ),
        )
        assertEquals(settings, decodeDesktopSettings(encodeDesktopSettings(settings)))
    }

    @Test
    fun `an unreadable record is the app as it shipped, not a broken one`() {
        // The opposite of what the destination record does with one, and the difference is what
        // is lost: forgotten preferences are a working client, a forgotten calendar is not.
        assertEquals(DesktopSettings(), decodeDesktopSettings(null))
        assertEquals(DesktopSettings(), decodeDesktopSettings(""))
        assertEquals(DesktopSettings(), decodeDesktopSettings("{"))
        assertEquals(
            DesktopSettings(),
            decodeDesktopSettings(encodeDesktopSettings(DesktopSettings()).replace("\"v\":1", "\"v\":2")),
        )
    }

    @Test
    fun `a record naming no working day reads as the shipped week rather than throwing`() {
        // `WorkingWeek` would refuse the empty set on construction, and the throw would land
        // wherever the record happened to be read — which on this client is a capture.
        val broken = encodeDesktopSettings(DesktopSettings()).replace(
            "\"working_days\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"]",
            "\"working_days\":[]",
        )
        assertEquals(DEFAULT_WORKING_DAYS, decodeDesktopSettings(broken).shared.workingDays)
    }

    @Test
    fun `a hotkey missing from a record falls back to the shipped one`() {
        val broken = encodeDesktopSettings(DesktopSettings()).replace("\"Ctrl+Shift+K\"", "\"\"")
        assertEquals("Ctrl+Shift+K", decodeDesktopSettings(broken).hotkey)
    }

    // ---- the settings actually reach the parse -----------------------------------------------

    @Test
    fun `FR-504 the date order reaches the parse`() {
        // The condition that would make this fail is a Settings screen that stores a preference
        // nothing reads — which is exactly what this client did with FR-507's override until
        // SRS 1.65, and reads as though the work were done.
        val monthFirst = LatchSettings(dayFirstDates = false, timeZone = "Asia/Kolkata")
        val context = parseContextFor(monthFirst, Instant.parse("2026-09-03T09:00:00Z"))
        assertEquals(DateOrder.MONTH_FIRST, context.dateOrder)
        assertEquals(ZoneId.of("Asia/Kolkata"), context.zone)
        assertEquals("2026-09-03T14:30", context.now.toString())
    }

    @Test
    fun `FR-512 and FR-1001 reach the parse too`() {
        val settings = LatchSettings(confidenceThreshold = 0.9, defaultEventDuration = Duration.ofMinutes(45))
        val context = parseContextFor(settings, Instant.now())
        assertEquals(0.9, context.confidenceThreshold.value)
        assertEquals(Duration.ofMinutes(45), context.defaultEventDuration)
    }

    @Test
    fun `a stored zone this JVM does not know falls back to the machine's`() {
        // A record moved between machines, or a name this tzdata does not carry. Resolving
        // locally is a working capture; throwing is a capture the user cannot make at all.
        val context = parseContextFor(LatchSettings(timeZone = "Mars/Olympus"), Instant.now())
        assertEquals(ZoneId.systemDefault(), context.zone)
    }

    // ---- what the window says about what is missing --------------------------------------------

    @Test
    fun `the gaps are named on screen rather than left absent`() {
        // SRS 1.65's finding applied before it can happen again: a capability whose control is
        // missing reads as though the work were done.
        val gaps = settingsGaps()
        assertTrue(gaps.any { "FR-900" in it && "FR-1002" in it }, gaps.toString())
        assertTrue(gaps.any { "FR-1003" in it }, gaps.toString())
        assertTrue(gaps.any { "FR-600" in it }, gaps.toString())
    }

    @Test
    fun `every problem has a sentence of its own`() {
        val sentences = SettingsProblem.entries.map(::settingsProblemText)
        assertEquals(sentences.size, sentences.toSet().size, "two problems read the same")
        assertTrue(sentences.none { it.isBlank() })
    }
}
