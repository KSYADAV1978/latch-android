package com.latch.parser.rule

import com.latch.parser.Confidence
import com.latch.parser.ParseContext
import java.time.DateTimeException
import java.time.LocalDate

/**
 * FR-503: "12 September", "12th Sept", "Sept 12", with or without a year.
 * As in [NumericDateRule], a missing year is the current year and is never rolled forward.
 */
internal object WrittenDateRule : DateRule {

    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1,
        "february" to 2, "feb" to 2,
        "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4,
        "may" to 5,
        "june" to 6, "jun" to 6,
        "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8,
        "september" to 9, "sept" to 9, "sep" to 9,
        "october" to 10, "oct" to 10,
        "november" to 11, "nov" to 11,
        "december" to 12, "dec" to 12,
    )

    private val MONTH_ALTERNATION = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private const val ORDINAL = """(?:st|nd|rd|th)?"""
    private const val YEAR = """(?:[,\s]+(\d{4}))?"""

    /** "12 September 2026", "12th Sept" */
    private val DAY_MONTH =
        Regex("""(?<!\d)(\d{1,2})$ORDINAL\s+($MONTH_ALTERNATION)\b\.?$YEAR""")

    /**
     * "September 12, 2026", "Sept 12th". The `(?!\d)` stops "September 2026" being read as
     * the 20th: without it the day group happily takes the first two digits of the year.
     */
    private val MONTH_DAY =
        Regex("""\b($MONTH_ALTERNATION)\b\.?\s+(\d{1,2})$ORDINAL(?!\d)$YEAR""")

    override fun find(text: String, ctx: ParseContext): List<RawDate> {
        val dayMonth = DAY_MONTH.findAll(text).mapNotNull { match ->
            val (dayRaw, monthName, yearRaw) = match.destructured
            build(dayRaw.toInt(), MONTHS.getValue(monthName), yearRaw, match.range, ctx)
        }
        val monthDay = MONTH_DAY.findAll(text).mapNotNull { match ->
            val (monthName, dayRaw, yearRaw) = match.destructured
            build(dayRaw.toInt(), MONTHS.getValue(monthName), yearRaw, match.range, ctx)
        }
        // "12 September" matches DAY_MONTH; MONTH_DAY cannot also claim it, so no overlap
        // filtering is needed beyond what the assembler already does.
        return (dayMonth + monthDay).toList()
    }

    private fun build(
        day: Int,
        month: Int,
        yearRaw: String,
        span: IntRange,
        ctx: ParseContext,
    ): RawDate? {
        val year = if (yearRaw.isEmpty()) ctx.now.year else yearRaw.toInt()
        val date = try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            return null
        }
        return RawDate(
            date = date,
            span = span,
            confidence = if (yearRaw.isEmpty()) Confidence.HIGH else Confidence.CERTAIN,
            source = DateSource.EXPLICIT,
        )
    }
}
