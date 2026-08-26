package com.latch.parser.rule

import com.latch.parser.Confidence
import com.latch.parser.ParseContext
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * FR-503 relative references, English and Hinglish: "today", "tomorrow", "day after
 * tomorrow", "next Monday", "this Friday", "next week", "kal", "parso", "agle Monday",
 * "3 tarikh".
 *
 * **"kal" is deliberately read as tomorrow.** In Hindi it means both yesterday and tomorrow,
 * resolved by verb tense that this parser does not model. AC-13 ("kal 4 baje meeting" →
 * tomorrow 16:00) settles which way to lean, and the reduced confidence plus the FR-504
 * "show the interpretation" rule is what keeps that from being a silent guess.
 */
internal object RelativeDateRule : DateRule {

    private val DAY_OFFSETS: List<Pair<Regex, Int>> = listOf(
        // Longest first: "day after tomorrow" must win over "tomorrow".
        Regex("""\bday\s+after\s+tomorrow\b""") to 2,
        Regex("""\bparso\b|\bparson\b|\bparsun\b""") to 2,
        Regex("""\btomorrow\b|\btmrw\b""") to 1,
        Regex("""\bkal\b""") to 1,
        Regex("""\btoday\b|\baaj\b""") to 0,
        Regex("""\btonight\b|\baaj\s+raat\b""") to 0,
    )

    /** Lower confidence: recognised, but the writer may have meant yesterday. */
    private val HINGLISH_AMBIGUOUS = setOf("kal")

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    private val WEEKDAY_ALTERNATION = WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|")

    private val WEEKDAY_PATTERN =
        Regex("""\b(next|this|coming|agle|agli|is|iss)?\s*\b($WEEKDAY_ALTERNATION)\b""")

    private val NEXT_WEEK = Regex("""\bnext\s+week\b|\bagle\s+h[ae]fte\b""")

    /** "3 tarikh" — a day of the month with the month left implied. */
    private val TARIKH = Regex("""\b(\d{1,2})\s*(?:tarikh|tareekh|tarik)\b""")

    override fun find(text: String, ctx: ParseContext): List<RawDate> {
        val today = ctx.now.toLocalDate()
        val results = mutableListOf<RawDate>()

        for ((pattern, offset) in DAY_OFFSETS) {
            for (match in pattern.findAll(text)) {
                val ambiguous = match.value.trim() in HINGLISH_AMBIGUOUS
                results += RawDate(
                    date = today.plusDays(offset.toLong()),
                    span = match.range,
                    confidence = if (ambiguous) Confidence.MEDIUM else Confidence.HIGH,
                    source = DateSource.RELATIVE,
                )
            }
        }

        for (match in NEXT_WEEK.findAll(text)) {
            results += RawDate(
                date = today.plusWeeks(1),
                span = match.range,
                confidence = Confidence.LOW,
                source = DateSource.RELATIVE,
                // "next week" names a week, not a day. FR-506 sends this to the date picker
                // rather than saving it.
                ambiguousRelative = true,
            )
        }

        for (match in WEEKDAY_PATTERN.findAll(text)) {
            val (qualifier, name) = match.destructured
            val target = WEEKDAYS.getValue(name)
            val isNext = qualifier in setOf("next", "agle", "agli")
            results += RawDate(
                date = resolveWeekday(today, target, isNext),
                span = match.range,
                confidence = if (qualifier.isEmpty()) Confidence.MEDIUM else Confidence.HIGH,
                source = DateSource.WEEKDAY,
            )
        }

        for (match in TARIKH.findAll(text)) {
            val day = match.groupValues[1].toInt()
            val date = dayOfMonthForward(today, day) ?: continue
            results += RawDate(
                date = date,
                span = match.range,
                // The month is inferred, so this is a weaker claim than a written date.
                confidence = Confidence.MEDIUM,
                source = DateSource.RELATIVE,
            )
        }

        return results
    }

    /**
     * "this Friday" is the coming Friday, today included. "next Monday" is the Monday of the
     * following week — if the coming Monday is still inside this week, it is skipped, which
     * is what people mean when they bother to say "next".
     */
    private fun resolveWeekday(today: LocalDate, target: DayOfWeek, isNext: Boolean): LocalDate {
        var candidate = today
        while (candidate.dayOfWeek != target) candidate = candidate.plusDays(1)
        if (!isNext) return candidate

        if (candidate == today) candidate = candidate.plusWeeks(1)
        val weekOfYear = WeekFields.of(Locale.UK).weekOfWeekBasedYear()
        if (candidate.get(weekOfYear) == today.get(weekOfYear)) {
            candidate = candidate.plusWeeks(1)
        }
        return candidate
    }

    /**
     * A bare day of the month rolls into next month once it has passed. Unlike a missing
     * year (see [NumericDateRule]) the writer has given no month at all, and "3 tarikh" said
     * on the 25th is not a reference to three weeks ago.
     */
    private fun dayOfMonthForward(today: LocalDate, day: Int): LocalDate? = try {
        val thisMonth = today.withDayOfMonth(day)
        if (thisMonth < today) today.plusMonths(1).withDayOfMonth(day) else thisMonth
    } catch (_: DateTimeException) {
        null
    }
}
