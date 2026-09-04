package com.latch.wire

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FR-804's move note, and the one thing it must never do.
 *
 * §7.2's metadata lives inside a task's notes. A note appended carelessly would destroy the
 * item's identity on that transport — it would stop matching FR-803 and FR-804 for ever, and
 * nothing would report it. So the placement is tested against a **real encoded record** rather
 * than against a string that looks like one.
 */
class MoveNoteTest {

    private val metadata = RemoteMetadata(
        sourceHash = "a".repeat(64),
        itemKey = "b".repeat(64),
        chainId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        capturedAt = Instant.parse("2026-09-02T09:15:00Z"),
        recipeId = "builtin.meeting_prep",
    )

    private val note = "Moved by Latch from Wed 8 Sep 2027, 09:00, on 4 Sep 2026."

    /** A task's notes as they actually stand in an account: FR-805's body, then the marker. */
    private fun taskNotes(body: String = "Sent by the school office."): String =
        metadata.toTaskNotes(body)

    @Test
    fun `the metadata still decodes after a note is added`() {
        // The condition that would make this fail is a note that lands after the `[latch]`
        // line and is itself mistaken for one, or one that rewrites the line. Asserted by
        // round-tripping the real encoder and decoder, not by inspecting text.
        val after = bodyWithMoveNote(taskNotes(), note)
        assertEquals(metadata, assertNotNull(remoteMetadataFromTaskNotes(after)))
    }

    @Test
    fun `the metadata stays the last line, so a second move cannot orphan it`() {
        val once = bodyWithMoveNote(taskNotes(), note)
        val twice = bodyWithMoveNote(once, "Moved by Latch from Thu 9 Sep 2027, 09:00, on 5 Sep 2026.")

        assertTrue(twice.lines().last().trimStart().startsWith("[latch]"), twice)
        assertEquals(metadata, assertNotNull(remoteMetadataFromTaskNotes(twice)))
    }

    @Test
    fun `each move adds a line rather than replacing one`() {
        // An item moved twice has been moved twice. SRS 1.18's reading — the description is
        // provenance and not current state — is what makes accumulating right: the item's own
        // dates are the current state.
        val once = bodyWithMoveNote(taskNotes(), note)
        val second = "Moved by Latch from Thu 9 Sep 2027, 09:00, on 5 Sep 2026."
        val twice = bodyWithMoveNote(once, second)

        assertTrue(note in twice)
        assertTrue(second in twice)
        assertEquals(1, twice.lines().count { it == note })
    }

    @Test
    fun `the original body survives untouched`() {
        // FR-805's text is provenance. A note that displaced it would lose what the user
        // actually captured, which is the one thing the description is for.
        val after = bodyWithMoveNote(taskNotes("Sent by the school office."), note)
        assertTrue("Sent by the school office." in after)
    }

    @Test
    fun `an event body has no marker, so the note simply goes at the end`() {
        val description = "Kickoff 8 September 2027 at 9am"
        val after = bodyWithMoveNote(description, note)
        assertEquals(description + "\n" + note, after)
    }

    @Test
    fun `an empty body becomes the note alone, with no leading whitespace`() {
        assertEquals(note, bodyWithMoveNote("", note))
        assertEquals(note, bodyWithMoveNote("   ", note))
    }

    @Test
    fun `a blank note is a no-op`() {
        // A client with no wording configured must not be able to introduce whitespace into
        // somebody's calendar entry.
        val notes = taskNotes()
        assertEquals(notes, bodyWithMoveNote(notes, ""))
        assertEquals(notes, bodyWithMoveNote(notes, "   "))
    }
}
