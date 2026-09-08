package com.latch.android.capture

import com.latch.google.DuplicateSearch
import com.latch.google.ItemDates
import com.latch.google.RescheduleMatch
import com.latch.google.RescheduleSearch
import com.latch.google.WriteBasis
import com.latch.google.WriteDecision
import com.latch.google.writeBasis
import com.latch.google.basisSafeName
import com.latch.google.writeDecision
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The diagnostic line, and the property that makes it safe to log.
 *
 * Two things are under test and the second matters more. That the line says **which query
 * answered** — because "Already saved" reaches the screen by two routes and this is the only
 * place the difference survives. And that it says **nothing about the capture** — because the
 * obvious spelling of it would have printed the title stored on the user's own item.
 */
class SaveDecisionLogTest {

    private val proposed = ItemDates.Task(LocalDate.parse("2026-09-18"))

    /** A stored item whose title is the thing that must never reach a log. */
    private fun match(dates: ItemDates) = RescheduleMatch(
        remoteId = "task-1",
        title = "Board papers for the September review",
        dates = dates,
    )

    private val hashHit = DuplicateSearch(existingId = "task-1")
    private val hashMiss = DuplicateSearch(existingId = null)

    // ---- which query answered -----------------------------------------------------------

    @Test
    fun `a source-hash match and an item-key match at the same date are told apart`() {
        // **The whole reason this exists.** Both produce `WriteDecision.Duplicate` and both
        // render "Already saved. Nothing was written again."; on 1 Sep 2026 that identity hid a
        // real defect, and on 3 Sep 2026 it left AC-07's reverse direction unclosable on its
        // mechanism. The condition that would make this fail is a line derived from the
        // decision rather than from the searches.
        val viaHash = writeBasis(hashHit, null, proposed)
        val viaKey = writeBasis(hashMiss, RescheduleSearch(match(proposed)), proposed)

        assertEquals(WriteBasis.SOURCE_HASH, viaHash)
        assertEquals(WriteBasis.ITEM_KEY_SAME_DATE, viaKey)

        // The decision is identical; only the basis separates them.
        assertEquals(
            writeDecision(hashHit, null, proposed),
            writeDecision(hashMiss, RescheduleSearch(match(proposed)), proposed),
        )
        assertFalse(
            saveDecisionLine(viaHash, WriteDecision.Duplicate, false) ==
                saveDecisionLine(viaKey, WriteDecision.Duplicate, false),
            "the two routes to Duplicate produced the same line",
        )
    }

    @Test
    fun `every row of the table has its own basis`() {
        val elsewhere = ItemDates.Task(LocalDate.parse("2026-09-25"))
        assertEquals(WriteBasis.SOURCE_HASH, writeBasis(hashHit, null, proposed))
        assertEquals(
            WriteBasis.ITEM_KEY_DIFFERENT_DATE,
            writeBasis(hashMiss, RescheduleSearch(match(elsewhere)), proposed),
        )
        assertEquals(WriteBasis.NO_MATCH, writeBasis(hashMiss, RescheduleSearch(null), proposed))
        // SRS 1.25: the key query is skipped for a multi-item capture. "Nothing was looked for"
        // is a different fact about the account from "nothing matched".
        assertEquals(WriteBasis.KEY_NOT_CONSULTED, writeBasis(hashMiss, null, proposed))
    }

    @Test
    fun `the basis agrees with the decision it describes`() {
        // A diagnostic that told a different story from the code it describes would be worse
        // than none, so the two are asserted against each other rather than in isolation.
        val cases = listOf(
            Triple(hashHit, null, WriteDecision.Duplicate),
            Triple(hashMiss, RescheduleSearch(match(proposed)), WriteDecision.Duplicate),
            Triple(hashMiss, RescheduleSearch(null), WriteDecision.Create),
            Triple(hashMiss, null, WriteDecision.Create),
        )
        cases.forEach { (duplicate, reschedule, expected) ->
            assertEquals(expected, writeDecision(duplicate, reschedule, proposed))
        }
        val moved = RescheduleSearch(match(ItemDates.Task(LocalDate.parse("2026-09-25"))))
        assertTrue(writeDecision(hashMiss, moved, proposed) is WriteDecision.Reschedule)
        assertEquals(WriteBasis.ITEM_KEY_DIFFERENT_DATE, writeBasis(hashMiss, moved, proposed))
    }

    @Test
    fun `a capped scan is named, because a NO_MATCH beside one is a different fact`() {
        // SRS §5.8 forbids reading a capped scan as "no duplicate". The line says which.
        assertTrue("scan=capped" in saveDecisionLine(WriteBasis.NO_MATCH, WriteDecision.Create, true))
        assertFalse("scan=capped" in saveDecisionLine(WriteBasis.NO_MATCH, WriteDecision.Create, false))
    }

    // ---- and nothing else ---------------------------------------------------------------

    @Test
    fun `no capture content reaches the line, for any decision`() {
        // The obvious spelling — logging the decision object — would have printed
        // `RescheduleMatch`, which carries the title **stored on the user's item**. The phone's
        // logcat is readable by anyone holding the device.
        val moved = ItemDates.Event(
            start = LocalDateTime.parse("2026-09-25T09:00"),
            end = LocalDateTime.parse("2026-09-25T10:00"),
            allDay = false,
            timeZone = "Asia/Kolkata",
        )
        val decision = WriteDecision.Reschedule(match(moved))
        val line = saveDecisionLine(WriteBasis.ITEM_KEY_DIFFERENT_DATE, decision, false)

        assertFalse("Board papers" in line, line)
        assertFalse("September review" in line, line)
        assertFalse("task-1" in line, line)
        // Nor the dates, which are as much the user's business as the words.
        assertFalse("2026" in line, line)
        assertEquals("save decision=Reschedule basis=ITEM_KEY_DIFFERENT_DATE", line)
    }

    @Test
    fun `the decision's own name carries no match either`() {
        val decision = WriteDecision.Reschedule(match(proposed))
        // `toString()` on the data class does carry it, which is why nothing prints that.
        assertTrue("Board papers" in decision.toString(), "the hazard this guards is not real")
        assertFalse("Board papers" in decision.basisSafeName)
    }

    @Test
    fun `every line is short, and every basis produces one`() {
        WriteBasis.entries.forEach { basis ->
            listOf(WriteDecision.Duplicate, WriteDecision.Create).forEach { decision ->
                val line = saveDecisionLine(basis, decision, false)
                assertTrue(line.startsWith("save decision="), line)
                assertTrue(line.length < 80, line)
            }
        }
    }

    // ---- SRS 1.193: the undo's own line -------------------------------------------------

    @Test
    fun `the three acts an undo performs are told apart`() {
        // **The whole reason this exists**, and it is `WriteBasis`' reason one requirement over.
        // FR-807's undo deletes what was created, drops what was queued and *restores* what was
        // updated, and all three end at "Removed. Nothing was left in your Google account." On
        // 7 Sep 2026 that identity left FR-1232 closable only on the account, because nothing
        // the phone emitted said the undo had restored anything.
        val deleted = undoDecisionLine("save", UndoTally(deleted = 1))
        val restored = undoDecisionLine("save", UndoTally(restored = 1))
        val dropped = undoDecisionLine("save", UndoTally(dropped = 1))

        assertEquals(3, setOf(deleted, restored, dropped).size, "three acts, three lines")
        assertTrue("restored=1" in restored, restored)
        assertTrue("restored=0" in deleted, deleted)
    }

    @Test
    fun `a zero is printed rather than omitted`() {
        // An absent `restored=` cannot be told from a `restored=0`, and for a diagnostic the
        // difference between "it restored nothing" and "this build does not report restores"
        // is the whole value of the line.
        val line = undoDecisionLine("save", UndoTally(deleted = 2))
        listOf("deleted=2", "restored=0", "dropped=0", "failed=0").forEach {
            assertTrue(it in line, line)
        }
    }

    @Test
    fun `a partial undo is named as one, not merely counted`() {
        // NFR-303. The recourse for the items still in the account is to remove them in Google
        // by hand, and the line has to be greppable for the case that needs it.
        val partial = undoDecisionLine("save", UndoTally(deleted = 2, failed = 2))
        assertTrue(partial.startsWith("save decision=UndoFailed"), partial)
        assertTrue(undoDecisionLine("save", UndoTally(deleted = 4)).startsWith("save decision=Undone"))
    }

    @Test
    fun `the card pillar uses the same line under its own subject`() {
        // One grep gives the whole story of a capture, which is what `LatchTiming` is for. The
        // subject is a literal at the call site, so there is no path by which a value reaches it.
        val line = undoDecisionLine("card", UndoTally(restored = 1))
        assertEquals("card decision=Undone deleted=0 restored=1 dropped=0 failed=0", line)
    }

    @Test
    fun `no content can reach an undo line at all, and structurally`() {
        // Stronger than the save line's property rather than the same one: every argument here
        // is an Int except a subject the call sites supply as a literal, so there is nothing
        // for a title, a date or a resource name to travel in.
        val line = undoDecisionLine("save", UndoTally(deleted = 1, restored = 1, dropped = 1, failed = 1))
        assertTrue(line.length < 80, line)
        assertTrue(
            line.all { it.isLetterOrDigit() || it in " =_" },
            "an undo line is letters, digits and separators: $line",
        )
    }

    @Test
    fun `the tally counts what was attempted`() {
        // Used by nothing in production yet and asserted anyway: the arithmetic is the only
        // thing tying the four numbers to the chain they describe, and a line whose counts did
        // not add up to the save would send a reader looking for a fifth item.
        assertEquals(4, UndoTally(deleted = 1, restored = 1, dropped = 1, failed = 1).attempted)
        assertEquals(0, UndoTally().attempted)
    }
}
