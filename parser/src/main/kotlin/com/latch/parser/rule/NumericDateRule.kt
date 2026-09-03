package com.latch.parser.rule

import com.latch.parser.Confidence
import com.latch.parser.DateOrder
import com.latch.parser.ParseContext
import java.time.DateTimeException
import java.time.LocalDate

/**
 * FR-503: `DD/MM/YY`, `DD-MM-YYYY`, `DD.MM.YY`.
 *
 * Two decisions here are worth knowing about before changing anything:
 *
 * 1. **A missing year is the current year, never the next one.** Rolling a past date forward
 *    would make "the order dated 12 March" resolve to next March, and AC-04 requires that
 *    capture to produce no dated item at all. The past-ness is reported instead (FR-510).
 * 2. **Order comes from the setting, not from the text** (FR-504), except where the text
 *    settles it — 25/12 can only be DD/MM. Where both readings are possible the candidate
 *    is flagged [RawDate.ambiguousOrder] so the UI can show the reading it chose.
 * 3. **A year-first date is matched whole, before the day-first pattern gets to it.**
 *    `YYYY-MM-DD` is not in FR-503's minimum list, and it was not handled: the day-first
 *    pattern matched the trailing `10-14` of `2027-10-14`, found no year attached to it,
 *    and filled in the current one — so a renewal due in October 2027 resolved to October
 *    **2026**, a year early, with no warning and no ambiguity flag. That is worse than not
 *    supporting the format: the app invented a year while a correct one was sitting in the
 *    text, which is design principle 1's failure in the direction it does not name.
 *
 *    Found on 3 Sep 2026 while building §7.2's date-free-title conformance vectors, where
 *    it also showed as a second defect — the leftover `2027-` stayed in `latch.item_key`,
 *    so rescheduling such an item across a year boundary would have failed to match it and
 *    written a duplicate.
 */
internal object NumericDateRule : DateRule {

    private val PATTERN = Regex("""(?<!\d)(\d{1,2})([/.-])(\d{1,2})(?:\2(\d{2,4}))?(?!\d)""")

    /**
     * `YYYY-MM-DD` and `YYYY/MM/DD`.
     *
     * **A dot is deliberately not a separator here.** `2027.10.14` is a plausible date and
     * an equally plausible version number, and a build note reading "shipping 2027.10.14"
     * becoming a calendar entry is a worse failure than a date this rule declines to read.
     * The two supported separators are the ones ISO 8601 and exported spreadsheets use.
     */
    private val YEAR_FIRST = Regex("""(?<!\d)(\d{4})([/-])(\d{1,2})\2(\d{1,2})(?!\d)""")

    override fun find(text: String, ctx: ParseContext): List<RawDate> {
        // Year-first is matched first, and its spans are then withheld from the day-first
        // pattern — including the spans of shapes that turned out to be impossible dates,
        // so that `2027-13-45` is refused outright rather than half-matched into something
        // plausible-looking.
        val claimed = YEAR_FIRST.findAll(text).map { it.range }.toList()
        val yearFirst = YEAR_FIRST.findAll(text).mapNotNull { match ->
            val (yearRaw, _, monthRaw, dayRaw) = match.destructured
            val date = try {
                LocalDate.of(yearRaw.toInt(), monthRaw.toInt(), dayRaw.toInt())
            } catch (_: DateTimeException) {
                return@mapNotNull null
            }
            RawDate(
                date = date,
                span = match.range,
                // Nothing about a year-first date is ambiguous: the year fixes which end is
                // which, so FR-504's setting has no bearing on it.
                confidence = Confidence.CERTAIN,
                source = DateSource.EXPLICIT,
                ambiguousOrder = false,
                hasWrittenYear = true,
            )
        }.toList()

        val dayFirst = PATTERN.findAll(text)
            .filterNot { match -> claimed.any { it.first <= match.range.last && match.range.first <= it.last } }
            .mapNotNull { match ->
            val (firstRaw, separator, secondRaw, yearRaw) = match.destructured
            val first = firstRaw.toInt()
            val second = secondRaw.toInt()
            val hasYear = yearRaw.isNotEmpty()

            // "3.30 pm" is a time, not the 3rd of month 30. A dotted pair with no year is
            // only a date if both halves could be one.
            if (separator == "." && !hasYear && (first > 31 || second > 12)) return@mapNotNull null

            val bothPlausible = first <= 12 && second <= 12
            val dayFirst = when {
                first > 12 -> true
                second > 12 -> false
                else -> ctx.dateOrder == DateOrder.DAY_FIRST
            }
            val day = if (dayFirst) first else second
            val month = if (dayFirst) second else first
            val year = when {
                !hasYear -> ctx.now.year
                yearRaw.length <= 2 -> 2000 + yearRaw.toInt()
                else -> yearRaw.toInt()
            }

            val date = try {
                LocalDate.of(year, month, day)
            } catch (_: DateTimeException) {
                return@mapNotNull null // 32/13 and similar: recognised shape, impossible date
            }

            val confidence = when {
                hasYear && !bothPlausible -> Confidence.CERTAIN
                hasYear -> Confidence.HIGH
                bothPlausible -> Confidence.MEDIUM
                else -> Confidence.HIGH
            }

            RawDate(
                date = date,
                span = match.range,
                confidence = confidence,
                source = DateSource.EXPLICIT,
                ambiguousOrder = bothPlausible,
                hasWrittenYear = hasYear,
            )
        }.toList()

        return yearFirst + dayFirst
    }
}
