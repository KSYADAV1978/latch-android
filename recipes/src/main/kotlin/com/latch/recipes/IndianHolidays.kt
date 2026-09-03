package com.latch.recipes

import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import java.time.LocalDate

/**
 * FR-605: the app ships with an Indian public holiday list.
 *
 * **This is a placeholder covering the three gazetted holidays that fall on fixed dates.**
 * Most Indian public holidays follow lunar calendars and move every year, and several are
 * state-specific. Open decision 4 in §13 is exactly this question — bundled static list
 * versus a maintained source — and it has to be settled before v1.0, because a stale list
 * silently produces wrong working-day arithmetic rather than an obvious failure.
 *
 * Until it is settled, the calculator behaves correctly against whatever it is given; it is
 * the data that is incomplete, not the arithmetic.
 */
object IndianHolidays {

    fun fixedDateHolidays(year: Int): List<Holiday> = listOf(
        Holiday(LocalDate.of(year, 1, 26), "Republic Day", HolidaySource.BUNDLED),
        Holiday(LocalDate.of(year, 8, 15), "Independence Day", HolidaySource.BUNDLED),
        Holiday(LocalDate.of(year, 10, 2), "Gandhi Jayanti", HolidaySource.BUNDLED),
    )

    fun calendarFor(years: IntRange): HolidayCalendar =
        HolidayCalendar(years.flatMap(::fixedDateHolidays))

    /**
     * The bundled list a client hands to FR-605's arithmetic, over the years it can reach.
     *
     * **The window is a shared decision and not each client's**, which is why it moved here: a
     * recipe step can look a few days either side of a capture, an Inbox row can be replayed
     * against the instant it was captured at (FR-515), and a client with a narrower window would
     * step over a holiday its neighbour honoured — the same chain, two answers, from one
     * account. Three years back covers a replayed capture; five forward covers a renewal
     * captured well ahead of its date.
     */
    fun around(today: LocalDate): List<Holiday> =
        ((today.year - 3)..(today.year + 5)).flatMap(::fixedDateHolidays)
}
