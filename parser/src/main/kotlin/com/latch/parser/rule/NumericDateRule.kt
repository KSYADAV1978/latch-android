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
 */
internal object NumericDateRule : DateRule {

    private val PATTERN = Regex("""(?<!\d)(\d{1,2})([/.-])(\d{1,2})(?:\2(\d{2,4}))?(?!\d)""")

    override fun find(text: String, ctx: ParseContext): List<RawDate> =
        PATTERN.findAll(text).mapNotNull { match ->
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
            )
        }.toList()
}
