package com.latch.parser.rule

import com.latch.parser.Confidence
import com.latch.parser.Field

/**
 * FR-502: recurrence, where present. Emitted as an RFC 5545 RRULE body so it can go
 * straight into the Calendar API's `recurrence` field without a second translation step.
 */
internal object RecurrenceRule {

    private val DAYS = mapOf(
        "monday" to "MO", "tuesday" to "TU", "wednesday" to "WE", "thursday" to "TH",
        "friday" to "FR", "saturday" to "SA", "sunday" to "SU",
    )

    private val EVERY_WEEKDAY = Regex("""\b(?:every|har)\s+(${DAYS.keys.joinToString("|")})\b""")
    private val SIMPLE = mapOf(
        Regex("""\b(?:daily|every\s+day|roz|rozana)\b""") to "FREQ=DAILY",
        Regex("""\bweekly|every\s+week\b""") to "FREQ=WEEKLY",
        Regex("""\bmonthly|every\s+month\b""") to "FREQ=MONTHLY",
        Regex("""\byearly|annually|every\s+year\b""") to "FREQ=YEARLY",
    )

    fun find(text: String): Field<String>? {
        EVERY_WEEKDAY.find(text)?.let { match ->
            val day = DAYS.getValue(match.groupValues[1])
            return Field("FREQ=WEEKLY;BYDAY=$day", Confidence.HIGH, match.range)
        }
        for ((pattern, rrule) in SIMPLE) {
            pattern.find(text)?.let { return Field(rrule, Confidence.MEDIUM, it.range) }
        }
        return null
    }
}
