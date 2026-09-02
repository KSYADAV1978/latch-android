package com.latch.android.capture

import com.latch.core.model.MatchType
import com.latch.core.model.RoutingMode
import com.latch.core.model.RoutingRule
import com.latch.data.AccountDefaults
import com.latch.data.LatchSettings
import com.latch.google.latestEventMatch

/**
 * Where a capture will be written, name and colour included so FR-904's chip can be drawn
 * without a network call.
 *
 * The task list is not routed. FR-905 speaks of calendars, §8.2 records that task lists cannot
 * be coloured or hidden individually — so routing tasks between lists would buy the user
 * nothing they could see — and FR-607 wants a chain in one place. One list, from the account's
 * defaults, is therefore the answer and is written down as one rather than left as an omission.
 */
data class Destination(
    val calendarId: String,
    val calendarName: String,
    val calendarColour: String,
    val taskListId: String,
)

/**
 * FR-905, FR-906: which calendar this capture goes to.
 *
 * **Under Option B there is no routing at all**, and that is FR-103 rather than a shortcut:
 * Option B is "a new Google Calendar is created and becomes the destination for **all**
 * captures", and a rule that sent some elsewhere would be the same setting meaning two things.
 * Rules are an Option A feature, which is what FR-905 says.
 *
 * **Lowest priority wins**, and ties go to the order the user put the rules in. A rule set where
 * the answer depended on a map iteration would give the same capture different destinations on
 * different launches, which is the failure `latestEventMatch` exists to prevent one layer over.
 *
 * Pure, so FR-906 — "never route silently to a calendar the user has not seen" — is a property
 * the confirmation screen and the saver derive the same way from the same inputs. A screen that
 * worked the destination out separately would eventually show one calendar and write to another,
 * which is precisely what that requirement forbids.
 */
fun destinationFor(
    defaults: AccountDefaults,
    settings: LatchSettings,
    sourceApp: String?,
    recipeId: String?,
    captureText: String,
): Destination {
    val fallback = Destination(
        calendarId = defaults.destinationCalendarId,
        calendarName = defaults.destinationCalendarName,
        calendarColour = defaults.destinationCalendarColour,
        taskListId = defaults.taskListId,
    )
    if (defaults.routingMode != RoutingMode.EXISTING_CALENDARS) return fallback

    val rule = settings.routingRules
        .sortedWith(compareBy({ it.priority }, { settings.routingRules.indexOf(it) }))
        .firstOrNull { it.matches(sourceApp, recipeId, captureText) }
        ?: return fallback

    return fallback.copy(
        calendarId = rule.calendarId,
        calendarName = rule.calendarName,
        calendarColour = rule.calendarColour,
    )
}

/**
 * FR-905 names source application and recipe. `MatchType` has carried a third value —
 * `KEYWORD` — since §7.1's table was written, and it is honoured here rather than left as a
 * value nothing reads: a type with an unreachable case is a trap for whoever adds the next one.
 *
 * A keyword match is deliberately case-insensitive and a plain substring, not a pattern. A
 * regular expression in a routing rule is a way for a user to route their captures somewhere
 * they did not intend and could not debug.
 */
private fun RoutingRule.matches(sourceApp: String?, recipeId: String?, captureText: String): Boolean =
    when (matchType) {
        MatchType.SOURCE_APP -> matchValue.isNotBlank() && matchValue.equals(sourceApp, ignoreCase = true)
        MatchType.RECIPE -> matchValue.isNotBlank() && matchValue == recipeId
        MatchType.KEYWORD -> matchValue.isNotBlank() && captureText.contains(matchValue, ignoreCase = true)
    }

/**
 * FR-907: "Where the user overrides the destination for the same source three times, the app
 * shall offer to make that a rule. It shall not create the rule automatically."
 *
 * Both halves are here. [OVERRIDES_BEFORE_OFFER] is the count, and what this returns is an
 * *offer* — the caller shows it and the user answers; nothing is written by counting.
 *
 * The count is per source application, because that is what FR-905's rule would match on. A
 * count kept per destination instead would offer a rule that could not be expressed.
 */
fun shouldOfferRule(
    overrideCounts: Map<String, Int>,
    sourceApp: String?,
    settings: LatchSettings,
    defaults: AccountDefaults,
): Boolean {
    if (defaults.routingMode != RoutingMode.EXISTING_CALENDARS) return false
    val app = sourceApp?.takeIf { it.isNotBlank() } ?: return false
    // A source that already has a rule is not a source the user keeps overriding — they have
    // answered this question, and asking again would be the nagging FR-704 forbids elsewhere.
    if (settings.routingRules.any { it.matchType == MatchType.SOURCE_APP && it.matchValue.equals(app, true) }) {
        return false
    }
    return (overrideCounts[app] ?: 0) >= OVERRIDES_BEFORE_OFFER
}

/** FR-907's number, verbatim: three. */
const val OVERRIDES_BEFORE_OFFER: Int = 3

/** One more override from this source. Pure, so the counting rule is testable. */
fun countingOverride(counts: Map<String, Int>, sourceApp: String?): Map<String, Int> {
    val app = sourceApp?.takeIf { it.isNotBlank() } ?: return counts
    return counts + (app to (counts[app] ?: 0) + 1)
}

/**
 * FR-907's answer, taken: the rule the offer would create.
 *
 * Priority zero, so a rule the user has just asked for wins over any they set up earlier and
 * forgot. The name and colour travel with it, from a calendar they have just seen on the
 * confirmation screen — which is FR-906's requirement satisfied at the moment the rule is made
 * rather than every time it fires.
 */
fun ruleFromOverride(id: String, sourceApp: String, destination: Destination): RoutingRule =
    RoutingRule(
        id = id,
        matchType = MatchType.SOURCE_APP,
        matchValue = sourceApp,
        calendarId = destination.calendarId,
        priority = 0,
        calendarName = destination.calendarName,
        calendarColour = destination.calendarColour,
    )
