package com.latch.android.capture

import com.latch.data.DuplicateSearch
import com.latch.data.ItemDates
import com.latch.data.RescheduleMatch
import com.latch.data.RescheduleSearch
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SRS §7.2's decision table, one test per row.
 *
 * The table is the whole of FR-803 and FR-804's discrimination, and every row of it is a
 * different answer to the user, so each gets its own case rather than being folded into a
 * "matches / does not match" pair.
 *
 * 5 March 2030 is a Tuesday and 7 March 2030 a Thursday — the same pair the device sequence
 * uses, chosen so nothing here rolls into the past.
 */
class WriteDecisionTest {

    private val existing = ItemDates.Event(
        start = LocalDateTime.parse("2030-03-05T21:30"),
        end = LocalDateTime.parse("2030-03-05T22:30"),
        timeZone = "Asia/Kolkata",
    )

    private val moved = ItemDates.Event(
        start = LocalDateTime.parse("2030-03-07T21:30"),
        end = LocalDateTime.parse("2030-03-07T22:30"),
        timeZone = "Asia/Kolkata",
    )

    private val match = RescheduleMatch("ev_1", existing)

    // ----- row 1: the hash matches, and the key is not consulted -----

    @Test
    fun `row 1 — a matching source hash is a duplicate`() {
        assertEquals(
            WriteDecision.Duplicate,
            writeDecision(DuplicateSearch("ev_1"), reschedule = null, proposed = moved),
        )
    }

    @Test
    fun `row 1 — a duplicate stays a duplicate even where a reschedule was somehow found`() {
        // The saver does not run the key query on this path at all. If a future change ever
        // does, the hash must still win: §7.2 says the key is not consulted, so a duplicate
        // can never present itself as a reschedule.
        assertEquals(
            WriteDecision.Duplicate,
            writeDecision(DuplicateSearch("ev_1"), RescheduleSearch(match), proposed = moved),
        )
    }

    // ----- row 2: same key, different dates -----

    @Test
    fun `row 2 — same key and a different date is a reschedule`() {
        val decision = writeDecision(DuplicateSearch(null), RescheduleSearch(match), moved)

        val reschedule = assertIs<WriteDecision.Reschedule>(decision)
        assertEquals("ev_1", reschedule.match.remoteId)
        // The offer has to carry what the item holds now, because that is what an undo of
        // the update will write back.
        assertEquals(existing, reschedule.match.dates)
    }

    @Test
    fun `row 2 — a same-day change of time is still a reschedule`() {
        // "Same resolved date" means "an update would change nothing". Moving 21:30 to 22:30
        // on the same day changes something, so there is something to offer.
        val laterSameDay = existing.copy(
            start = LocalDateTime.parse("2030-03-05T22:30"),
            end = LocalDateTime.parse("2030-03-05T23:30"),
        )

        assertIs<WriteDecision.Reschedule>(
            writeDecision(DuplicateSearch(null), RescheduleSearch(match), laterSameDay),
        )
    }

    // ----- row 3: same key, same date. The cell that stops the nagging. -----

    @Test
    fun `row 3 — same key, different hash, same date is a duplicate and never an offer`() {
        // A message restated — forwarded, quoted back, re-sent with a different greeting —
        // is different text naming the very same date. Offering to move the item to where it
        // already is would be the app misreading a restatement as a change.
        val decision = writeDecision(DuplicateSearch(null), RescheduleSearch(match), existing)

        assertEquals(WriteDecision.Duplicate, decision)
    }

    @Test
    fun `row 3 — re-capturing the text that applied a reschedule is a duplicate`() {
        // The reason keeping the original source_hash is safe. The moved item carries the
        // hash of the capture that created it, so this text is not caught by row 1 — it is
        // caught here, because the item now sits exactly where this text says.
        val alreadyMoved = RescheduleMatch("ev_1", moved)

        assertEquals(
            WriteDecision.Duplicate,
            writeDecision(DuplicateSearch(null), RescheduleSearch(alreadyMoved), moved),
        )
    }

    @Test
    fun `row 3 — an unchanged task due date is a duplicate`() {
        val due = RescheduleMatch("t_1", ItemDates.Task(LocalDate.parse("2030-03-05")))

        assertEquals(
            WriteDecision.Duplicate,
            writeDecision(
                DuplicateSearch(null),
                RescheduleSearch(due),
                ItemDates.Task(LocalDate.parse("2030-03-05")),
            ),
        )
    }

    // ----- row 4: nothing matches -----

    @Test
    fun `row 4 — no match on either is a create`() {
        assertEquals(
            WriteDecision.Create,
            writeDecision(DuplicateSearch(null), RescheduleSearch(), moved),
        )
    }

    @Test
    fun `row 4 — a capped scan with no match falls through to a create, not to an offer`() {
        // SRS §5.8: a capped scan means "did not look everywhere", and must never be read as
        // "not a reschedule". Creating is the safe answer; the user gets a create where an
        // update was available.
        assertEquals(
            WriteDecision.Create,
            writeDecision(DuplicateSearch(null), RescheduleSearch(scanCapped = true), moved),
        )
    }

    // ----- the comparison itself -----

    @Test
    fun `the time zone is not part of the comparison`() {
        // Google returns no zone for an event using its calendar's default. Comparing it
        // would make an unchanged item look changed and offer a move that moved nothing.
        assertTrue(movesNothing(existing.copy(timeZone = null), existing))
        assertTrue(movesNothing(existing, existing.copy(timeZone = "Europe/London")))
    }

    @Test
    fun `an all-day item and a timed one on the same day are not the same`() {
        assertTrue(!movesNothing(existing, existing.copy(allDay = true)))
    }

    @Test
    fun `an item of the other transport is never a match to move`() {
        assertTrue(!movesNothing(existing, ItemDates.Task(LocalDate.parse("2030-03-05"))))
        assertTrue(!movesNothing(ItemDates.Task(null), existing))
    }

    @Test
    fun `two undated tasks move nothing`() {
        assertTrue(movesNothing(ItemDates.Task(null), ItemDates.Task(null)))
    }
}
