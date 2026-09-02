package com.latch.android.capture

import com.latch.core.model.MatchType
import com.latch.core.model.RoutingMode
import com.latch.core.model.RoutingRule
import com.latch.data.AccountDefaults
import com.latch.data.LatchSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-905, FR-906 and FR-907.
 *
 * The destination is decided by one pure function that the confirmation screen and the saver
 * both call, which is what makes FR-906 — "never route silently to a calendar the user has not
 * seen" — a property rather than a promise. A screen that worked it out separately would
 * eventually show one calendar and write to another.
 */
class RoutingTest {

    private val optionB = AccountDefaults(
        accountId = "acct",
        email = "you@example.com",
        routingMode = RoutingMode.LATCH_CALENDAR,
        destinationCalendarId = "latch-cal",
        destinationCalendarName = "Latch",
        destinationCalendarColour = "#d50000",
        taskListId = "list-1",
    )

    private val optionA = optionB.copy(
        routingMode = RoutingMode.EXISTING_CALENDARS,
        destinationCalendarId = "personal",
        destinationCalendarName = "Personal",
        destinationCalendarColour = "#0b8043",
    )

    private fun rule(
        id: String = "rule-1",
        type: MatchType = MatchType.SOURCE_APP,
        value: String = "com.whatsapp",
        calendar: String = "family",
        priority: Int = 0,
    ) = RoutingRule(
        id = id,
        matchType = type,
        matchValue = value,
        calendarId = calendar,
        priority = priority,
        calendarName = calendar.replaceFirstChar(Char::uppercase),
        calendarColour = "#3f51b5",
    )

    private fun route(
        defaults: AccountDefaults = optionA,
        rules: List<RoutingRule> = emptyList(),
        sourceApp: String? = null,
        recipeId: String? = null,
        text: String = "",
    ) = destinationFor(
        defaults = defaults,
        settings = LatchSettings(routingRules = rules),
        sourceApp = sourceApp,
        recipeId = recipeId,
        captureText = text,
    )

    @Test
    fun `with no rules the account default is the destination`() {
        assertEquals("personal", route().calendarId)
        assertEquals("Personal", route().calendarName)
        assertEquals("list-1", route().taskListId)
    }

    @Test
    fun `under Option B no rule applies at all`() {
        // FR-103: Option B is "the destination for **all** captures". A rule sending some
        // elsewhere would make one setting mean two things.
        val routed = route(defaults = optionB, rules = listOf(rule()), sourceApp = "com.whatsapp")
        assertEquals("latch-cal", routed.calendarId)
    }

    @Test
    fun `a source-app rule routes a capture from that app`() {
        val routed = route(rules = listOf(rule()), sourceApp = "com.whatsapp")
        assertEquals("family", routed.calendarId)
        assertEquals("Family", routed.calendarName)
    }

    @Test
    fun `a source-app rule leaves a capture from another app alone`() {
        assertEquals("personal", route(rules = listOf(rule()), sourceApp = "com.google.android.gm").calendarId)
    }

    @Test
    fun `a capture with no known source app matches no source rule`() {
        // `getReferrer()` is often null, which §7.2 reads as unknown rather than as an error.
        // An unknown source matching a rule would route captures the user never meant to.
        assertEquals("personal", route(rules = listOf(rule()), sourceApp = null).calendarId)
    }

    @Test
    fun `a blank match value never matches, which stops an empty rule catching everything`() {
        assertEquals(
            "personal",
            route(rules = listOf(rule(value = "")), sourceApp = "com.whatsapp").calendarId,
        )
    }

    @Test
    fun `a recipe rule routes by the recipe applied`() {
        val routed = route(
            rules = listOf(rule(type = MatchType.RECIPE, value = "builtin.travel_booking", calendar = "travel")),
            recipeId = "builtin.travel_booking",
        )
        assertEquals("travel", routed.calendarId)
    }

    @Test
    fun `a keyword rule matches case-insensitively on the captured text`() {
        // MatchType has carried KEYWORD since §7.1's table was written; a value nothing reads
        // is a trap for whoever adds the next one.
        val routed = route(
            rules = listOf(rule(type = MatchType.KEYWORD, value = "invoice", calendar = "work")),
            text = "INVOICE dated 12 March",
        )
        assertEquals("work", routed.calendarId)
    }

    @Test
    fun `the lowest priority wins`() {
        // A rule set whose answer depended on a map iteration would give the same capture
        // different destinations on different launches.
        val routed = route(
            rules = listOf(
                rule(id = "late", calendar = "second", priority = 5),
                rule(id = "early", calendar = "first", priority = 1),
            ),
            sourceApp = "com.whatsapp",
        )
        assertEquals("first", routed.calendarId)
    }

    @Test
    fun `the task list is never routed`() {
        // §8.2: task lists cannot be coloured or hidden individually, so routing between them
        // would buy the user nothing they could see — and FR-607 wants a chain in one place.
        assertEquals("list-1", route(rules = listOf(rule()), sourceApp = "com.whatsapp").taskListId)
    }

    // ----- FR-907 -----

    @Test
    fun `the offer comes after three overrides and not before`() {
        val settings = LatchSettings()
        assertFalse(shouldOfferRule(mapOf("com.whatsapp" to 2), "com.whatsapp", settings, optionA))
        assertTrue(shouldOfferRule(mapOf("com.whatsapp" to 3), "com.whatsapp", settings, optionA))
        assertEquals(3, OVERRIDES_BEFORE_OFFER)
    }

    @Test
    fun `the count is per source, so overrides from different apps do not add up`() {
        val counts = mapOf("com.whatsapp" to 2, "com.google.android.gm" to 2)
        assertFalse(shouldOfferRule(counts, "com.whatsapp", LatchSettings(), optionA))
    }

    @Test
    fun `no offer under Option B, where there are no rules to make`() {
        assertFalse(shouldOfferRule(mapOf("com.whatsapp" to 9), "com.whatsapp", LatchSettings(), optionB))
    }

    @Test
    fun `a source that already has a rule is not asked again`() {
        // They have answered this question; asking again is the nagging FR-704 forbids one
        // requirement over.
        val settings = LatchSettings(routingRules = listOf(rule()))
        assertFalse(shouldOfferRule(mapOf("com.whatsapp" to 9), "com.whatsapp", settings, optionA))
    }

    @Test
    fun `an unknown source is never offered a rule`() {
        assertFalse(shouldOfferRule(mapOf("" to 9), null, LatchSettings(), optionA))
        assertFalse(shouldOfferRule(mapOf("" to 9), "  ", LatchSettings(), optionA))
    }

    @Test
    fun `counting an override is per source and ignores an unknown one`() {
        assertEquals(mapOf("com.whatsapp" to 1), countingOverride(emptyMap(), "com.whatsapp"))
        assertEquals(mapOf("com.whatsapp" to 2), countingOverride(mapOf("com.whatsapp" to 1), "com.whatsapp"))
        assertEquals(emptyMap(), countingOverride(emptyMap(), null))
    }

    @Test
    fun `the rule an accepted offer makes wins over the user's earlier ones`() {
        // Priority zero: a rule just asked for should not be shadowed by one set up months ago
        // and forgotten.
        val destination = Destination("family", "Family", "#3f51b5", "list-1")
        val made = ruleFromOverride("rule-new", "com.whatsapp", destination)

        assertEquals(0, made.priority)
        assertEquals(MatchType.SOURCE_APP, made.matchType)
        // The name and colour travel with it, from a calendar the user has just seen on the
        // confirmation screen — FR-906 satisfied when the rule is made rather than every time
        // it fires.
        assertEquals("Family", made.calendarName)
        assertEquals("#3f51b5", made.calendarColour)

        val routed = route(rules = listOf(rule(priority = 1), made), sourceApp = "com.whatsapp")
        assertEquals("family", routed.calendarId)
    }
}
