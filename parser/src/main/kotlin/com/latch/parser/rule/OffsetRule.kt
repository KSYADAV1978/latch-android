package com.latch.parser.rule

import com.latch.core.model.Direction
import com.latch.core.model.OffsetUnit
import com.latch.parser.Confidence
import com.latch.parser.Field
import com.latch.parser.ParseContext
import com.latch.parser.PendingOffset

/**
 * FR-503 durations and offsets: "in 3 days", "within 2 weeks", "3 working days before".
 *
 * The first two anchor on today and resolve to a date here. The third does not: it is an
 * offset from something else, and resolving it needs the working week and holiday list from
 * :recipes. It comes back as a [PendingOffset] for that module to apply — see FR-604/605.
 */
internal object OffsetRule : DateRule {

    private val RELATIVE_TO_TODAY = Regex(
        """\b(?:in|within|after)\s+(\d{1,3})\s+(day|days|week|weeks|month|months)\b""",
    )

    private val WORKING_DAY_OFFSET = Regex(
        """\b(\d{1,3})\s+(working|business)\s+days?\s+(before|prior\s+to|after|later)\b""",
    )

    override fun find(text: String, ctx: ParseContext): List<RawDate> =
        RELATIVE_TO_TODAY.findAll(text).map { match ->
            val amount = match.groupValues[1].toLong()
            val today = ctx.now.toLocalDate()
            val date = when (match.groupValues[2].trimEnd('s')) {
                "day" -> today.plusDays(amount)
                "week" -> today.plusWeeks(amount)
                else -> today.plusMonths(amount)
            }
            RawDate(
                date = date,
                span = match.range,
                confidence = Confidence.HIGH,
                source = DateSource.OFFSET,
            )
        }.toList()

    /** Offsets with no anchor of their own. Not dates, so they are returned separately. */
    fun findPending(text: String): Field<PendingOffset>? =
        WORKING_DAY_OFFSET.find(text)?.let { match ->
            val direction = when (match.groupValues[3].substringBefore(' ')) {
                "before", "prior" -> Direction.BEFORE
                else -> Direction.AFTER
            }
            Field(
                value = PendingOffset(
                    value = match.groupValues[1].toInt(),
                    unit = OffsetUnit.WORKING_DAYS,
                    direction = direction,
                ),
                confidence = Confidence.HIGH,
                span = match.range,
            )
        }
}
