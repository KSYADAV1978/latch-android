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
import java.time.LocalDate

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
     * How close a bare weekday has to be to an explicit date before it can be treated as the
     * writer restating that date rather than naming a second one. "Friday 12 September" is
     * one commitment; "Friday, and again on 12 September" is two.
     *
     * Proximity is necessary and **not sufficient** — see [corroborationOf].
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

        val attachment = corroborationOf(accepted, normalized)

        return accepted
            .filterNot { it in attachment }
            // A corroborating weekday is dropped as a date and its span absorbed into the one
            // date it corroborates: "Friday 12 September" is one phrase, and the span should
            // describe the phrase the writer actually wrote. That matters twice over — FR-504
            // shows the span back to the user, and §7.2's latch.item_key is the title with the
            // date cut out of it, so a span that stops short of the weekday leaves the day name
            // behind and the identity moves when the meeting does, which is the one thing
            // FR-804 needs it not to do. Doing it here rather than in each rule's regex covers
            // every date format, including any added later.
            .map { date ->
                val absorbed = attachment.filterValues { it == date }.keys
                if (absorbed.isEmpty()) return@map date
                // "Monday, 31 August" begins at the weekday, so absorbing it can move the
                // start of the phrase earlier than the date word itself.
                val span = date.span.spanning(absorbed.map { it.span })
                date.copy(span = span, firstMentionAt = minOf(date.firstMentionAt, span.first))
            }
            // SRS 1.23: two dates joined by a range connective are one commitment, and the
            // pair is folded before mentions are merged so that neither endpoint is still
            // loose when merging happens.
            .let { mergeRanges(it, normalized) }
            // FR-511 counts distinct dates, not distinct mentions of one. Keyed by the pair,
            // so a range and a bare restatement of its opening date stay apart: SRS 1.23
            // withdraws an absorbed endpoint from merging, and merging 31 August the range
            // opener into 31 August the evening meeting would destroy one of the two.
            .groupBy { it.date to it.endDate }
            .map { (_, mentions) -> mergeMentions(mentions) }
            .sortedBy { it.firstMentionAt }
    }

    /**
     * Everything that may sit between two dates and still leave them one range.
     *
     * Deliberately a closed list rather than a distance, for the reason [samePhrase] is
     * literal: "5 September, final on 6 September" is two commitments and no threshold
     * separates it from "5 September to 6 September" reliably. The dash is here because
     * [Normalizer] has already folded en and em dashes onto it.
     */
    private val RANGE_JOIN = Regex("""^[\s,]*(?:to|-|until|till|through|thru)[\s,]*$""")

    /**
     * Folds "from X to Y" into one candidate carrying both ends (SRS 1.23).
     *
     * Works over the dates in written order and consumes the closing date, so an endpoint
     * cannot also survive as a candidate of its own — that withdrawal is the point, not a
     * side effect. Only a later closing date is accepted: "6 September to 31 August" is a
     * writer's slip or a coincidence of two unrelated dates, and reading it as a range would
     * produce an item that ends before it starts.
     */
    private fun mergeRanges(dates: List<RawDate>, text: String): List<RawDate> {
        val inOrder = dates.sortedBy { it.span.first }
        val consumed = BooleanArray(inOrder.size)
        val merged = mutableListOf<RawDate>()

        for ((index, start) in inOrder.withIndex()) {
            if (consumed[index]) continue
            val next = inOrder.getOrNull(index + 1)
            val joinable = next != null &&
                !consumed[index + 1] &&
                next.date > start.date &&
                start.span.last < next.span.first &&
                RANGE_JOIN.matches(text.substring(start.span.last + 1, next.span.first))

            if (joinable) {
                consumed[index + 1] = true
                val span = start.span.spanning(listOf(next!!.span))
                merged += start.copy(
                    endDate = next.date,
                    span = span,
                    // A range is only as certain as its weaker end.
                    confidence = minOf(start.confidence, next.confidence),
                    firstMentionAt = minOf(start.firstMentionAt, span.first),
                )
            } else {
                merged += start
            }
        }
        return merged
    }

    /**
     * Which explicit date, if any, each bare weekday is restating.
     *
     * **A weekday corroborates only a date it actually falls on.** This used to be decided by
     * proximity alone, and proximity alone is wrong in both directions. It let one weekday be
     * absorbed by *every* explicit date within the window rather than one, which produced
     * candidate spans that overlapped each other — and since §7.2's `item_key` is the title
     * with the primary's span blanked out, an over-reaching span blanks part of a neighbouring
     * date and silently changes an identity that cannot be corrected once written. It also
     * attached weekdays to dates they contradict: in "September 6, 2026 … Monday, August 31",
     * 6 September 2026 is a Sunday, and reading the Monday beside it as a restatement of it is
     * simply false.
     *
     * So the day of the week has to match. Proximity survives as a second filter — a matching
     * date on the far side of a long capture is a coincidence, not a restatement — and
     * nearest-wins is only the tiebreak between two dates that both match. A weekday matching
     * no candidate attaches to none, stays a date in its own right, and becomes its own
     * FR-511 candidate, which is what "Gym Friday, and the review on 12 September" needs.
     *
     * **A weekday that contradicts a date it is written into still restates it.** This is the
     * second clause and it is not a softening of the first — it is what stops the rule doing
     * more harm than the bug. SRS §7.2 gives "PTM on Friday 12 September" as its normative
     * example of corroboration, and **12 September 2026 is a Saturday**: the spec's own example
     * has a weekday that matches nothing. Writers get weekdays wrong constantly, and reading a
     * slip as a second commitment invents an item nobody asked for. So where a weekday sits in
     * the same phrase as a date — nothing between them but spaces and commas, no line break and
     * no other words — it is absorbed whether or not the day matches, and the contradiction is
     * left for FR-504 to show rather than acted on. Without this clause "Friday" survives as its
     * own FR-511 candidate and drops out of §7.2's blanked span, which is precisely the
     * `item_key` defect that clause was added to fix and which §7.2 v1.11 calls load-bearing.
     *
     * The reported failure is unaffected by the second clause and that is the point: between
     * "September 6, 2026" and "Monday" there is a line break and the word "Date:", so they are
     * not one phrase, and 6 September 2026 is a Sunday, so they do not match either.
     *
     * Returned as weekday-to-date so each weekday has at most one owner by construction; the
     * old shape could not express that and is what allowed the double attachment.
     */
    private fun corroborationOf(accepted: List<RawDate>, text: String): Map<RawDate, RawDate> {
        val explicit = accepted.filter { it.source == DateSource.EXPLICIT }
        if (explicit.isEmpty()) return emptyMap()

        return accepted
            .filter { it.source == DateSource.WEEKDAY }
            .mapNotNull { weekday ->
                val near = explicit.filter { it.span.gapTo(weekday.span) <= CORROBORATION_WINDOW }
                // Nearest wins; an exact tie falls to the earlier mention so the result does
                // not depend on rule order.
                val nearest = compareBy<RawDate>({ it.span.gapTo(weekday.span) }, { it.span.first })

                val owner = near.filter { it.date.dayOfWeek == weekday.date.dayOfWeek }
                    .minWithOrNull(nearest)
                    ?: near.filter { samePhrase(text, weekday.span, it.span) }.minWithOrNull(nearest)

                owner?.let { weekday to it }
            }
            .toMap()
    }

    /**
     * Whether two spans are parts of one written phrase: everything between them is a space or
     * a comma, and there is nothing else at all.
     *
     * A deliberately literal test rather than a tuned character distance. "friday 12 september"
     * and "monday, 31 august" pass; "september 6, 2026\ndate: monday" fails on the line break
     * and the word between, and "gym friday, and the review on 12 september" fails on the
     * words. A distance threshold would have to be tuned to sit between two of those and would
     * silently mean something different in a language with longer separators.
     */
    private fun samePhrase(text: String, a: IntRange, b: IntRange): Boolean {
        val between = when {
            a.last < b.first -> text.substring(a.last + 1, b.first)
            b.last < a.first -> text.substring(b.last + 1, a.first)
            else -> return true
        }
        return between.all { it == ' ' || it == ',' }
    }

    /**
     * One date written twice is one candidate — but which mention it is shown as, and where it
     * counts as appearing, are two different questions.
     *
     * The strongest mention supplies the value and the span, because that is the one whose text
     * the user should see highlighted and whose characters `item_key` blanks. The **earliest**
     * mention supplies the position, because that is where the writer first committed to the
     * date, and it is what puts the candidates in the order the capture reads.
     *
     * Collapsing the two is the defect this splits apart. "from August 31 to September 6, 2026
     * … Monday, August 31, 2026 at 21:30" mentions 31 August twice — once without a year near
     * the start, once with a year near the end. Taking the higher-confidence mention whole
     * moved 31 August's apparent position to the second mention, behind a 6 September that the
     * writer had introduced later, and the capture was then saved against the wrong date.
     */
    private fun mergeMentions(mentions: List<RawDate>): RawDate {
        val strongest = mentions.maxBy { it.confidence }
        return strongest.copy(firstMentionAt = mentions.minOf { it.span.first })
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
                endDate = date.endDate?.let { Field(it, date.confidence, date.span) },
                classification = Classifier.classify(
                    hasDate = true,
                    hasTime = start != null,
                    isRange = date.endDate != null,
                    ambiguousRelative = date.ambiguousRelative,
                ),
                isPast = date.date < today,
                ambiguousOrder = date.ambiguousOrder,
                ambiguousRelative = date.ambiguousRelative,
                isExplicit = date.source == DateSource.EXPLICIT,
            )
        }
    }
}
