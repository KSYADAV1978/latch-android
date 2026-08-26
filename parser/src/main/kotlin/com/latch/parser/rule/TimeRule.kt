package com.latch.parser.rule

import com.latch.parser.Confidence
import com.latch.parser.ParseContext
import java.time.LocalTime

/**
 * FR-503 times: "11 AM", "11:00", "1130 hrs", "3.30 pm", "11 baje".
 *
 * **The "baje" heuristic.** A bare "N baje" carries no meridiem and Indian usage resolves it
 * by daylight: "4 baje" is the afternoon, "11 baje" the morning. AC-13 requires "kal 4 baje"
 * to land on 16:00, so hours 1–7 are read as afternoon and 8–12 as written. It is a guess
 * about a time the writer did specify, not an invented one, and it is marked MEDIUM so the
 * user sees the reading before saving.
 */
internal object TimeRule {

    private const val MERIDIEM = """(a\.?m\.?|p\.?m\.?)"""

    /** "11:00 am", "11:00" */
    private val HOUR_COLON_MINUTE = Regex("""(?<!\d)(\d{1,2}):(\d{2})\s*$MERIDIEM?(?!\d)""")

    /**
     * "3.30 pm". A dot is only a time separator when a meridiem confirms it: without that
     * rule "12.09.26" and even bare "12.09" get eaten as times, and FR-503's `DD.MM.YY`
     * format stops working.
     */
    private val HOUR_DOT_MINUTE = Regex("""(?<!\d)(\d{1,2})\.(\d{2})\s*$MERIDIEM(?!\d)""")

    /** "11 am", "3pm" — a bare hour needs the meridiem to be a time at all. */
    private val HOUR_MERIDIEM = Regex("""(?<!\d)(\d{1,2})\s*$MERIDIEM""")

    /** "1130 hrs", "0900 hours" */
    private val MILITARY = Regex("""(?<!\d)(\d{3,4})\s*(?:hrs|hours|hr)\b""")

    /** "4 baje", "4:30 baje" */
    private val BAJE = Regex("""(?<!\d)(\d{1,2})(?::(\d{2}))?\s*baje\b""")

    fun find(text: String, ctx: ParseContext): List<RawTime> {
        val results = mutableListOf<RawTime>()

        for (match in (HOUR_COLON_MINUTE.findAll(text) + HOUR_DOT_MINUTE.findAll(text))) {
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            val meridiem = match.groupValues[3]
            val time = buildTime(hour, minute, meridiem) ?: continue
            if (results.any { it.span overlaps match.range }) continue
            results += RawTime(
                time = time,
                span = match.range,
                confidence = if (meridiem.isEmpty()) Confidence.HIGH else Confidence.CERTAIN,
            )
        }

        for (match in HOUR_MERIDIEM.findAll(text)) {
            if (results.any { it.span overlaps match.range }) continue
            val time = buildTime(match.groupValues[1].toInt(), 0, match.groupValues[2]) ?: continue
            results += RawTime(time = time, span = match.range, confidence = Confidence.CERTAIN)
        }

        for (match in MILITARY.findAll(text)) {
            val digits = match.groupValues[1].padStart(4, '0')
            val hour = digits.substring(0, 2).toInt()
            val minute = digits.substring(2, 4).toInt()
            if (hour > 23 || minute > 59) continue
            results += RawTime(
                time = LocalTime.of(hour, minute),
                span = match.range,
                confidence = Confidence.CERTAIN,
            )
        }

        for (match in BAJE.findAll(text)) {
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour > 23 || minute > 59) continue
            val resolved = if (hour in 1..7) hour + 12 else hour
            results += RawTime(
                time = LocalTime.of(resolved % 24, minute),
                span = match.range,
                confidence = Confidence.MEDIUM,
            )
        }

        return results.sortedBy { it.span.first }
    }

    private fun buildTime(hour: Int, minute: Int, meridiem: String): LocalTime? {
        if (minute > 59) return null
        val pm = meridiem.startsWith("p")
        val am = meridiem.startsWith("a")
        val resolved = when {
            pm && hour < 12 -> hour + 12
            am && hour == 12 -> 0
            else -> hour
        }
        if (resolved > 23) return null
        return LocalTime.of(resolved, minute)
    }
}
