package com.latch.data

import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureState
import com.latch.core.model.ItemType
import com.latch.google.ItemDates
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-701's stored records, and the pure rules beside them.
 *
 * The SQL itself is not reachable from here — `SQLiteOpenHelper` is a throwing stub under JVM
 * unit tests, the same property that makes `BitmapFactory` untestable in `:ocr` — so the record
 * formats are `internal` functions and this is what exercises them. That split is deliberate
 * and is the same one `GoogleRest.kt`'s response mappers keep.
 */
class InboxRecordTest {

    private val capture = InboxCapture(
        id = "capture-1",
        rawText = "Sharma Ji\nPTM next week sometime\n\nOk noted.",
        layer = CaptureLayer.SHARE_SHEET,
        appId = "com.whatsapp",
        preferredTitle = null,
        ocrUsed = true,
        capturedAt = Instant.parse("2026-09-01T09:15:00Z"),
        capturedLocal = LocalDateTime.parse("2026-09-01T14:45:00"),
        zone = "Asia/Kolkata",
        confidence = 0.5,
        reason = InboxReason.LOW_CONFIDENCE,
    )

    @Test
    fun `a record round-trips every field`() {
        val full = capture.copy(
            preferredTitle = "Fees",
            assignedDate = LocalDate.parse("2027-09-20"),
            editedTitle = "School fees",
            snoozedUntil = Instant.parse("2026-09-08T09:15:00Z"),
            state = CaptureState.INBOX,
        )
        assertEquals(full, decodeInboxCapture(encodeInboxCapture(full)))
    }

    @Test
    fun `the newlines of a chat capture survive, which a separator format would not`() {
        // This is why the record is JSON and not the unit-separated form the account defaults
        // use: an FR-805 body is free user text and a screenshot's is full of line breaks.
        val decoded = assertNotNull(decodeInboxCapture(encodeInboxCapture(capture)))
        assertEquals(capture.rawText, decoded.rawText)
        assertTrue(decoded.rawText.contains('\n'))
    }

    @Test
    fun `the captured instant and zone survive, because re-parsing depends on them`() {
        // The load-bearing pair. Lose these and the Inbox re-parses "kal" against today, moving
        // the date one day further every time the list is opened.
        val decoded = assertNotNull(decodeInboxCapture(encodeInboxCapture(capture)))
        assertEquals(LocalDateTime.parse("2026-09-01T14:45:00"), decoded.capturedLocal)
        assertEquals("Asia/Kolkata", decoded.zone)
    }

    @Test
    fun `a record from a later version decodes to null rather than being mis-read`() {
        val record = encodeInboxCapture(capture).replace("\"v\":1", "\"v\":99")
        assertNull(decodeInboxCapture(record))
    }

    @Test
    fun `a reason a later version introduced decodes to null rather than throwing`() {
        val record = encodeInboxCapture(capture).replace("LOW_CONFIDENCE", "SOMETHING_NEW")
        assertNull(decodeInboxCapture(record))
    }

    @Test
    fun `malformed json is null, not an exception that would take out the list`() {
        assertNull(decodeInboxCapture("{"))
        assertNull(decodeInboxCapture(""))
    }

    // FR-702's snooze, and FR-704's count.

    @Test
    fun `an un-snoozed capture is always due`() {
        assertTrue(capture.isDue(Instant.parse("2026-09-01T09:15:00Z")))
    }

    @Test
    fun `a snoozed capture is not due until its moment, and is due exactly on it`() {
        val snoozed = capture.copy(snoozedUntil = Instant.parse("2026-09-08T00:00:00Z"))
        assertFalse(snoozed.isDue(Instant.parse("2026-09-07T23:59:59Z")))
        assertTrue(snoozed.isDue(Instant.parse("2026-09-08T00:00:00Z")))
    }

    // FR-705: surfaced for review, never deleted.

    @Test
    fun `a capture older than the review period is surfaced`() {
        val old = capture.copy(capturedAt = Instant.parse("2026-08-01T09:15:00Z"))
        val fresh = capture.copy(id = "capture-2", capturedAt = Instant.parse("2026-09-01T09:15:00Z"))
        val aged = agedCaptures(listOf(old, fresh), now = Instant.parse("2026-09-02T09:15:00Z"))
        assertEquals(listOf(old), aged)
    }

    @Test
    fun `the boundary is inclusive, so a fortnight old surfaces on the day`() {
        val exactly = capture.copy(capturedAt = Instant.parse("2026-08-19T09:15:00Z"))
        assertEquals(
            listOf(exactly),
            agedCaptures(listOf(exactly), now = Instant.parse("2026-09-02T09:15:00Z")),
        )
        assertTrue(
            agedCaptures(
                listOf(exactly),
                now = Instant.parse("2026-09-02T09:14:59Z"),
            ).isEmpty()
        )
    }

    @Test
    fun `a snoozed capture is never surfaced for review, however old it is`() {
        // FR-704 forbids nagging, and re-raising something the user has explicitly deferred is
        // the definition of it.
        val old = capture.copy(
            capturedAt = Instant.parse("2026-01-01T09:15:00Z"),
            snoozedUntil = Instant.parse("2026-12-01T00:00:00Z"),
        )
        assertTrue(agedCaptures(listOf(old), now = Instant.parse("2026-09-02T09:15:00Z")).isEmpty())
    }

    @Test
    fun `the review period is a parameter, because FR-705 calls it configurable`() {
        val old = capture.copy(capturedAt = Instant.parse("2026-08-30T09:15:00Z"))
        val now = Instant.parse("2026-09-02T09:15:00Z")
        assertTrue(agedCaptures(listOf(old), now).isEmpty())
        assertEquals(listOf(old), agedCaptures(listOf(old), now, after = Duration.ofDays(1)))
    }
}

/** FR-807's stored offer, and FR-803's index row. */
class LocalStoreRecordTest {

    private val written = CreatedItem.Written(ItemType.EVENT, "latch-cal", "evt-1")
    private val queued = CreatedItem.Queued("queue-1")
    private val updated = CreatedItem.Updated(
        type = ItemType.TASK,
        containerId = "list-1",
        remoteId = "task-1",
        priorDates = ItemDates.Task(LocalDate.parse("2027-09-20")),
    )

    @Test
    fun `an offer round-trips all three shapes of created item`() {
        val offer = StoredUndoOffer(
            chainId = "chain-1",
            expiresAt = Instant.parse("2026-09-01T09:15:10Z"),
            created = listOf(written, queued, updated),
        )
        assertEquals(offer, decodeUndoOffer(encodeUndoOffer(offer)))
    }

    @Test
    fun `an update's prior dates survive, because an undo has nowhere else to read them`() {
        val offer = StoredUndoOffer("chain-1", Instant.parse("2026-09-01T09:15:10Z"), listOf(updated))
        val decoded = assertNotNull(decodeUndoOffer(encodeUndoOffer(offer)))
        assertEquals(
            ItemDates.Task(LocalDate.parse("2027-09-20")),
            (decoded.created.single() as CreatedItem.Updated).priorDates,
        )
    }

    @Test
    fun `one unreadable item makes the whole offer unreadable`() {
        // A partial offer is worse than none: an undo that removed three of four items and
        // reported success is the more damaging of the two lies FR-807's note weighs.
        val offer = StoredUndoOffer("chain-1", Instant.parse("2026-09-01T09:15:10Z"), listOf(written, queued))
        val record = encodeUndoOffer(offer).replace("\"kind\":\"QUEUED\"", "\"kind\":\"SOMETHING_NEW\"")
        assertNull(decodeUndoOffer(record))
    }

    @Test
    fun `an offer knows when it has lapsed`() {
        val offer = StoredUndoOffer("chain-1", Instant.parse("2026-09-01T09:15:10Z"), listOf(written))
        assertTrue(offer.isOpen(Instant.parse("2026-09-01T09:15:09Z")))
        assertFalse(offer.isOpen(Instant.parse("2026-09-01T09:15:10Z")))
    }

    @Test
    fun `an index row round-trips through the columns it is stored in`() {
        val row = writtenItemFrom(
            remoteId = "evt-1",
            containerId = "latch-cal",
            type = "EVENT",
            sourceHash = "a".repeat(64),
            itemKey = "b".repeat(64),
            dates = encodeItemDates(
                ItemDates.Event(
                    start = LocalDateTime.parse("2027-09-08T09:00"),
                    end = LocalDateTime.parse("2027-09-08T10:00"),
                    allDay = false,
                    timeZone = "Asia/Kolkata",
                )
            ).toString(),
            writtenAt = 1_756_000_000_000,
        )
        val item = assertNotNull(row)
        assertEquals("evt-1", item.remoteId)
        assertEquals(ItemType.EVENT, item.type)
        assertEquals(
            ItemDates.Event(
                LocalDateTime.parse("2027-09-08T09:00"),
                LocalDateTime.parse("2027-09-08T10:00"),
                allDay = false,
                timeZone = "Asia/Kolkata",
            ),
            item.dates,
        )
    }

    @Test
    fun `an index row with dates it cannot read is dropped rather than half-built`() {
        assertNull(
            writtenItemFrom(
                remoteId = "evt-1",
                containerId = "latch-cal",
                type = "EVENT",
                sourceHash = "a".repeat(64),
                itemKey = "b".repeat(64),
                dates = "{\"kind\":\"SOMETHING_NEW\"}",
                writtenAt = 0,
            )
        )
    }
}
