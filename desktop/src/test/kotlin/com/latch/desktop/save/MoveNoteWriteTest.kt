package com.latch.desktop.save

import com.latch.core.model.ItemType
import com.latch.desktop.FakeCalendar
import com.latch.desktop.FakeTasks
import com.latch.google.ItemDates
import com.latch.google.movedFromNote
import com.latch.google.worthNoting
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * FR-804's move note where it actually reaches an account (SRS 1.79).
 *
 * SRS 1.77 recorded the finding and named the cost: **the write is easy and the undo is not.**
 * An update that writes "moved from X" and an undo that puts the dates back without removing
 * that line leaves a false statement standing in the user's own calendar — worse than the
 * silence it replaced, because it is confidently wrong rather than merely quiet.
 */
class MoveNoteWriteTest {

    private val template = "Moved by Latch from %1\$s, on %2\$s."
    private val today = LocalDate.parse("2026-09-04")

    private val from = ItemDates.Event(
        start = LocalDateTime.parse("2027-09-08T09:00"),
        end = LocalDateTime.parse("2027-09-08T10:00"),
        allDay = false,
        timeZone = "Asia/Kolkata",
    )

    // ---- the sentence -------------------------------------------------------------------

    @Test
    fun `the note names where the item came from, and today`() {
        // Where it moved *to* is the item itself and is on the face of it in any calendar.
        // What the user cannot recover is the date it left: after the patch that exists
        // nowhere but in the undo offer.
        val note = movedFromNote(template, from, today)
        // Substrings, not an exact sentence: the month's spelling is the JVM locale's business
        // ("Sep" or "Sept"), and pinning it would make this a test of tzdata rather than of the
        // note. What is pinned is that the note names the old date with its weekday and time,
        // and today without a weekday.
        assertTrue(note.startsWith("Moved by Latch from Wed 8 Sep"), note)
        assertTrue("2027, 09:00, on 4 Sep" in note, note)
        assertTrue(note.endsWith("2026."), note)
    }

    @Test
    fun `an all-day event is named by its day and never given a time`() {
        val allDay = from.copy(allDay = true)
        assertTrue("09:00" !in movedFromNote(template, allDay, today))
        assertTrue("Wed 8 Sep" in movedFromNote(template, allDay, today))
    }

    @Test
    fun `an undated task is not worth a note`() {
        // "Moved from  on 4 Sep" is worse than silence, so the caller is told not to write one.
        assertTrue(worthNoting(ItemDates.Task(LocalDate.parse("2027-09-08"))))
        assertTrue(!worthNoting(ItemDates.Task(null)))
    }

    @Test
    fun `a client with no wording configured writes nothing`() {
        assertEquals("", movedFromNote("", from, today))
    }
}
