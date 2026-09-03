package com.latch.desktop.inbox

import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureState
import com.latch.core.model.InboxCapture
import com.latch.core.model.InboxReason
import com.latch.core.model.ItemType
import com.latch.desktop.store.reversingSecrets
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-701 to FR-705 on Windows: the store, and the record beneath it.
 *
 * The condition that would make each of these fail is named on the test rather than left to be
 * inferred, which is the standing convention here — a criterion verified against a fixture too
 * easy to fail it has not been verified.
 */
class DesktopInboxTest {

    private val directory: File = File.createTempFile("latch-inbox", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val file = File(directory, "inbox.dat")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun inbox() = DesktopInbox(file, reversingSecrets())

    private val at: Instant = Instant.parse("2026-09-03T09:00:00Z")

    private fun aRow(
        id: String = "r1",
        text: String = "Ask about the uniform order",
        reason: InboxReason = InboxReason.UNDATED,
        capturedAt: Instant = at,
        snoozedUntil: Instant? = null,
    ) = InboxCapture(
        id = id,
        rawText = text,
        layer = CaptureLayer.SHARE_SHEET,
        appId = null,
        preferredTitle = null,
        ocrUsed = false,
        capturedAt = capturedAt,
        capturedLocal = LocalDateTime.parse("2026-09-03T14:30:00"),
        zone = "Asia/Kolkata",
        confidence = 0.42,
        reason = reason,
        snoozedUntil = snoozedUntil,
    )

    // ---- the record ---------------------------------------------------------------------------

    @Test
    fun `every field of a row survives the record`() {
        val row = aRow().copy(
            appId = "com.example.mail",
            preferredTitle = "Fees",
            ocrUsed = true,
            assignedDate = LocalDate.parse("2027-09-20"),
            editedTitle = "Uniform order",
            typeOverride = ItemType.EVENT,
            snoozedUntil = at.plus(Duration.ofDays(7)),
            state = CaptureState.INBOX,
        )
        assertEquals(row, decodeInboxCapture(encodeInboxCapture(row)))
    }

    @Test
    fun `a confidence below one survives as a fraction`() {
        // The condition that would make this fail is reading a Double back through an integer
        // accessor, which turns every below-threshold capture into a confident one — silently,
        // and in the field FR-512 routed the row on.
        val decoded = assertNotNull(decodeInboxCapture(encodeInboxCapture(aRow())))
        assertEquals(0.42, decoded.confidence)
    }

    @Test
    fun `captured text with newlines and unicode survives`() {
        val text = "फ़ीस due 20 September 2027" + "\n" + "second line\ttabbed" + "\n" + "\"quoted\""
        val decoded = assertNotNull(decodeInboxCapture(encodeInboxCapture(aRow(text = text))))
        assertEquals(text, decoded.rawText)
    }

    @Test
    fun `a record from another version decodes to null rather than to a wrong row`() {
        val json = encodeInboxCapture(aRow()).replace("\"v\":1", "\"v\":2")
        assertNull(decodeInboxCapture(json))
    }

    @Test
    fun `a reason or layer this build does not know decodes to null rather than throwing`() {
        // One row written by a later version must not take out the list. `valueOf` would throw
        // here and lose every row in the file.
        assertNull(decodeInboxCapture(encodeInboxCapture(aRow()).replace("UNDATED", "TELEPATHY")))
        assertNull(decodeInboxCapture(encodeInboxCapture(aRow()).replace("SHARE_SHEET", "SEMAPHORE")))
    }

    @Test
    fun `nonsense decodes to null`() {
        assertNull(decodeInboxCapture(""))
        assertNull(decodeInboxCapture("{"))
        assertNull(decodeInboxCapture("[]"))
    }

    // ---- the store ----------------------------------------------------------------------------

    @Test
    fun `a row survives being written and read back`() {
        val inbox = inbox()
        inbox.add(aRow())
        assertEquals(listOf(aRow()), inbox.all())
        assertEquals(aRow(), inbox.find("r1"))
        assertNull(inbox.find("nothing"))
    }

    @Test
    fun `FR-703 the captured text is not in the file in the clear`() {
        inbox().add(aRow(text = "Ask about the uniform order"))
        assertFalse("uniform order" in file.readText(), file.readText().take(200))
    }

    @Test
    fun `rows come back oldest first, whatever order they were written in`() {
        // FR-705 wants an aged capture surfacing rather than sinking under newer ones.
        val inbox = inbox()
        inbox.add(aRow(id = "new", capturedAt = at))
        inbox.add(aRow(id = "old", capturedAt = at.minus(Duration.ofDays(10))))
        assertEquals(listOf("old", "new"), inbox.all().map { it.id })
    }

    @Test
    fun `FR-702 an update replaces one row and leaves the rest`() {
        val inbox = inbox()
        inbox.add(aRow(id = "a"))
        inbox.add(aRow(id = "b"))
        inbox.update(aRow(id = "a").copy(assignedDate = LocalDate.parse("2027-09-20")))

        assertEquals(LocalDate.parse("2027-09-20"), inbox.find("a")?.assignedDate)
        assertNull(inbox.find("b")?.assignedDate)
    }

    @Test
    fun `FR-702 discard removes one row and nothing else`() {
        val inbox = inbox()
        inbox.add(aRow(id = "a"))
        inbox.add(aRow(id = "b"))
        inbox.discard("a")
        assertEquals(listOf("b"), inbox.all().map { it.id })
    }

    @Test
    fun `FR-702 a snoozed row leaves the list and FR-704 count, and comes back`() {
        val inbox = inbox()
        val wakes = at.plus(Duration.ofDays(7))
        inbox.add(aRow(id = "sleeping", snoozedUntil = wakes))
        inbox.add(aRow(id = "awake"))

        assertEquals(listOf("awake"), inbox.due(at).map { it.id })
        assertEquals(InboxStatus(due = 1, snoozed = 1, unreadable = 0), inbox.status(at))
        // It is still there — snoozing is not discarding.
        assertEquals(2, inbox.all().size)
        assertEquals(2, inbox.due(wakes).size)
    }

    @Test
    fun `a row this build cannot decrypt is KEPT, not dropped`() {
        // **The one place this client and the phone deliberately differ**, and the reason is
        // that an Inbox row is a capture that exists nowhere else — FR-703 guarantees exactly
        // that. `SqliteCaptureInbox` deletes such a row to keep FR-704's count honest; here the
        // row stays on disk and the count is kept honest by counting it separately instead.
        //
        // The fixture creates the real condition: a cipher that refuses one specific value.
        val refusing = reversingSecrets(refuses = setOf("QkFE"))
        inbox().add(aRow())
        file.appendText("\nQkFE")

        val inbox = DesktopInbox(file, refusing)
        assertEquals(1, inbox.all().size, "the readable row was lost")
        assertEquals(InboxStatus(due = 1, snoozed = 0, unreadable = 1), inbox.status(at))

        // And it survives a rewrite, which is where a store that merely skipped it would lose it.
        inbox.add(aRow(id = "r2"))
        assertTrue("QkFE" in file.readText(), "the unreadable row was discarded on rewrite")
        assertEquals(2, inbox.all().size)
    }

    @Test
    fun `an unreadable row is not counted as waiting on the user`() {
        // FR-704: the count is what the user can act on. A row that can never be opened is not
        // one of those, and counting it would be a number that never goes down — which is the
        // objection the phone's store answers by deleting the row instead.
        inbox().add(aRow())
        file.appendText("\nQkFE")
        val status = DesktopInbox(file, reversingSecrets(refuses = setOf("QkFE"))).status(at)
        assertEquals(1, status.due)
        assertEquals(1, status.unreadable)
    }

    @Test
    fun `a file with no version header reads as empty rather than as rows`() {
        file.parentFile.mkdirs()
        file.writeText("latch-inbox/2\nQ0JB")
        assertEquals(emptyList(), inbox().all())
        assertEquals(InboxStatus(0, 0, 0), inbox().status(at))
    }

    @Test
    fun `NFR-205 clear deletes the file rather than emptying it`() {
        val inbox = inbox()
        inbox.add(aRow())
        assertTrue(file.isFile)
        inbox.clear()
        assertFalse(file.exists())
        assertEquals(emptyList(), inbox.all())
    }

    @Test
    fun `emptying the inbox by discarding leaves no file behind`() {
        val inbox = inbox()
        inbox.add(aRow())
        inbox.discard("r1")
        assertFalse(file.exists(), "an empty file still holds a record of what was captured")
    }
}
