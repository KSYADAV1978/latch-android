package com.latch.parser

import com.latch.parser.rule.DateSource
import com.latch.parser.rule.LocationRule
import com.latch.parser.rule.NumericDateRule
import com.latch.parser.rule.OffsetRule
import com.latch.parser.rule.RawDate
import com.latch.parser.rule.RawTime
import com.latch.parser.rule.RecurrenceRule
import com.latch.parser.rule.RelativeDateRule
import com.latch.parser.rule.TimeRule
import com.latch.parser.rule.WrittenDateRule
import com.latch.parser.rule.gapTo
import com.latch.parser.rule.overlaps
import com.latch.parser.rule.spanning

/**
 * The entry point for FR-500. Pure: same text plus same [ParseContext] gives the same
 * result, on the JVM under test and on the device.
 *
 * Nothing here reaches for the network or the clock — FR-501 is a property of the module
 * (see its build file), and determinism is what makes the NFR-502 corpus meaningful.
 */
object DateParser {

    private val dateRules = listOf(
        NumericDateRule,
        WrittenDateRule,
        RelativeDateRule,
        OffsetRule,
    )

    /**
     * How close a bare weekday has to be to an explicit date before it is treated as the
     * writer restating that date rather than naming a second one. "Friday 12 September" is
     * one commitment; "Friday, and again on 12 September" is two.
     */
    private const val CORROBORATION_WINDOW = 12

    fun parse(text: String, ctx: ParseContext): ParseResult {
        val normalized = Normalizer.normalize(text)

        val times = TimeRule.find(normalized, ctx)
        val dates = collectDates(normalized, ctx, times)
        val candidates = assemble(dates, times, ctx)

        return ParseResult(
            title = TitleExtractor.extract(text),
            candidates = candidates,
            location = LocationRule.find(text),
            recurrence = RecurrenceRule.find(normalized),
            pendingOffset = OffsetRule.findPending(normalized),
        )
    }

    private fun collectDates(
        normalized: String,
        ctx: ParseContext,
        times: List<RawTime>,
    ): List<RawDate> {
        val raw = dateRules.flatMap { it.find(normalized, ctx) }
            // A span already claimed as a time is not also a date: "3.30 pm" is half past
            // three, not the 3rd of a 30th month.
            .filterNot { date -> times.any { date.span overlaps it.span } }
            // Longest match wins, so "day after tomorrow" beats the "tomorrow" inside it.
            .sortedWith(compareByDescending<RawDate> { it.span.count() }.thenByDescending { it.confidence })

        val accepted = mutableListOf<RawDate>()
        for (date in raw) {
            if (accepted.any { it.span overlaps date.span }) continue
            accepted += date
        }

        val explicit = accepted.filter { it.source == DateSource.EXPLICIT }
        val corroborating = accepted.filter { candidate ->
            candidate.source == DateSource.WEEKDAY &&
                explicit.any { it.span.gapTo(candidate.span) <= CORROBORATION_WINDOW }
        }

        return accepted
            .filterNot { it in corroborating }
            // The corroborating weekday is dropped as a date and its span absorbed into the
            // date it corroborates: "Friday 12 September" is one phrase, and the span should
            // describe the phrase the writer actually wrote. That matters twice over — FR-504
            // shows the span back to the user, and §7.2's latch.item_key is the title with
            // the date cut out of it, so a span that stops short of the weekday leaves the day
            // name behind and the identity moves when the meeting does, which is the one thing
            // FR-804 needs it not to do. Doing it here rather than in each rule's regex covers
            // every date format, including any added later.
            .map { date ->
                if (date.source != DateSource.EXPLICIT) return@map date
                val absorbed = corroborating.filter { date.span.gapTo(it.span) <= CORROBORATION_WINDOW }
                if (absorbed.isEmpty()) date else date.copy(span = date.span.spanning(absorbed.map { it.span }))
            }
            // FR-511 counts distinct dates, not distinct mentions of one.
            .groupBy { it.date }
            .map { (_, sameDate) -> sameDate.maxBy { it.confidence } }
            .sortedBy { it.span.first }
    }

    /**
     * Pairs each date with the time nearest to it, and reads two adjacent times as a range.
     * With one date and one time the pairing is unconditional; with several of each, nearest
     * wins, which is what "12 Sept 11am, 14 Sept 3pm" needs.
     */
    private fun assemble(
        dates: List<RawDate>,
        times: List<RawTime>,
        ctx: ParseContext,
    ): List<DatedCandidate> {
        val today = ctx.now.toLocalDate()

        if (dates.isEmpty()) {
            val start = times.firstOrNull()
            val end = times.getOrNull(1)
            return listOf(
                DatedCandidate(
                    date = null,
                    time = start?.let { Field(it.time, it.confidence, it.span) },
                    endTime = end?.let { Field(it.time, it.confidence, it.span) },
                    classification = Classifier.classify(hasDate = false, hasTime = start != null),
                ),
            )
        }

        val assignments = times.groupBy { time ->
            dates.minBy { it.span.gapTo(time.span) }
        }

        return dates.map { date ->
            val owned = assignments[date].orEmpty().sortedBy { it.span.first }
            val start = owned.firstOrNull()
            val end = owned.getOrNull(1)
            DatedCandidate(
                date = Field(date.date, date.confidence, date.span),
                time = start?.let { Field(it.time, it.confidence, it.span) },
                endTime = end?.let { Field(it.time, it.confidence, it.span) },
                classification = Classifier.classify(
                    hasDate = true,
                    hasTime = start != null,
                    ambiguousRelative = date.ambiguousRelative,
                ),
                isPast = date.date < today,
                ambiguousOrder = date.ambiguousOrder,
                ambiguousRelative = date.ambiguousRelative,
            )
        }
    }
}
