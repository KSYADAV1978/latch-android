package com.latch.data

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.core.model.SyncState
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * FR-806's record format, and the FR-803-adjacent decision about which failures are worth
 * coming back for.
 *
 * The record is where a silent defect would live — exactly as the account-defaults record was
 * and the response mappers are — because a queue entry that decodes wrong writes a wrong item
 * into the user's account, and §7.2 says an item written without correct metadata is
 * permanently unmanageable afterwards. So the whole codec is exercised here, off a device.
 */
class WriteQueueRecordTest {

    private val metadata = RemoteMetadata(
        sourceHash = sourceHashOf("Team sync 5 Sep at 3pm"),
        itemKey = itemKeyOf("Team sync"),
        chainId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        capturedAt = Instant.parse("2026-08-26T14:03:22Z"),
        sourceApp = "android:com.google.android.gm",
    )

    private val event = Item(
        id = "chain-1#0",
        captureId = "cap-1",
        chainId = "chain-1",
        type = ItemType.EVENT,
        title = "Team sync",
        start = LocalDateTime.parse("2026-09-05T15:00"),
        end = LocalDateTime.parse("2026-09-05T16:00"),
        location = "Room 4",
        calendarId = "latch-cal",
    )

    private fun entry(
        item: Item = event,
        body: String = "Team sync 5 Sep at 3pm",
        attempts: Int = 0,
        lastError: String? = null,
        givenUp: Boolean = false,
    ) = QueuedWrite(
        id = "queue-1",
        write = PendingWrite(item = item, metadata = metadata, body = body, timeZone = "Asia/Kolkata"),
        operation = WriteOperation.CREATE,
        attempts = attempts,
        lastError = lastError,
        givenUp = givenUp,
        queuedAt = Instant.parse("2026-08-26T14:03:25Z"),
    )

    // ----- round trip -----

    @Test
    fun `an event survives the round trip whole`() {
        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(entry())))

        assertEquals("queue-1", decoded.id)
        assertEquals(WriteOperation.CREATE, decoded.operation)
        assertEquals(Instant.parse("2026-08-26T14:03:25Z"), decoded.queuedAt)
        assertEquals("Asia/Kolkata", decoded.write.timeZone)
        assertEquals("Team sync 5 Sep at 3pm", decoded.write.body)

        val item = decoded.write.item
        assertEquals(ItemType.EVENT, item.type)
        assertEquals("Team sync", item.title)
        assertEquals(LocalDateTime.parse("2026-09-05T15:00"), item.start)
        assertEquals(LocalDateTime.parse("2026-09-05T16:00"), item.end)
        assertEquals("Room 4", item.location)
        assertEquals("latch-cal", item.calendarId)
        assertFalse(item.allDay)
    }

    @Test
    fun `the §7 2 metadata survives byte for byte`() {
        // The point of the queue is that the write happens later; if any of this shifted in
        // storage, FR-803, FR-804 and FR-807 would all be reading a different item than the
        // one the user captured, and §7.2 says that cannot be corrected after the write.
        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(entry())))
        assertEquals(metadata, decoded.write.metadata)
    }

    @Test
    fun `a task survives, and carries a due date and no start`() {
        val task = Item(
            id = "chain-2#0",
            captureId = "cap-2",
            chainId = "chain-2",
            type = ItemType.TASK,
            title = "Submit the form",
            dueDate = LocalDate.parse("2026-09-12"),
            taskListId = "list-1",
        )
        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(entry(item = task))))

        assertEquals(ItemType.TASK, decoded.write.item.type)
        assertEquals(LocalDate.parse("2026-09-12"), decoded.write.item.dueDate)
        assertEquals("list-1", decoded.write.item.taskListId)
        assertNull(decoded.write.item.start)
    }

    @Test
    fun `a body with newlines and quotes survives`() {
        // This is why the record is JSON and not the unit-separated format next door: an
        // FR-805 body is the user's own text, and it arrives with whatever is in it.
        val awkward = "Dear all,\n\n\"Team sync\" — 5 Sep\tat 3pm\r\nRegards,\nA"
        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(entry(body = awkward))))
        assertEquals(awkward, decoded.write.body)
    }

    @Test
    fun `a retry count and its reason survive, so the user can be told`() {
        val decoded = assertNotNull(
            decodeQueuedWrite(encodeQueuedWrite(entry(attempts = 3, lastError = "Gone", givenUp = true)))
        )
        assertEquals(3, decoded.attempts)
        assertEquals("Gone", decoded.lastError)
        assertTrue(decoded.givenUp)
    }

    @Test
    fun `a decoded entry reads as queued, not as a draft`() {
        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(entry())))
        assertEquals(SyncState.QUEUED, decoded.write.item.syncState)
    }

    // ----- refusing what it cannot trust -----

    @Test
    fun `a record from a later version decodes to null rather than being guessed at`() {
        val newer = JSONObject(encodeQueuedWrite(entry())).put("v", QUEUE_RECORD_VERSION + 1)
        assertNull(decodeQueuedWrite(newer.toString()))
    }

    @Test
    fun `a mangled record decodes to null and does not throw`() {
        // One unreadable entry must not take out a drain that would have written the others.
        assertNull(decodeQueuedWrite("not json at all"))
        assertNull(decodeQueuedWrite("{}"))
        assertNull(decodeQueuedWrite(JSONObject(encodeQueuedWrite(entry())).remove("item").toString()))

        val badTime = JSONObject(encodeQueuedWrite(entry())).put("queued_at", "half past three")
        assertNull(decodeQueuedWrite(badTime.toString()))

        val badType = JSONObject(encodeQueuedWrite(entry()))
        badType.getJSONObject("item").put("type", "REMINDER")
        assertNull(decodeQueuedWrite(badType.toString()))
    }

    @Test
    fun `a record that violates Item's own rules decodes to null`() {
        // A task carrying a start: Item's init refuses it (§8.1), and that refusal must
        // surface as an unreadable record rather than as a crash inside the worker.
        val impossible = JSONObject(encodeQueuedWrite(entry()))
        impossible.getJSONObject("item").put("type", "TASK")
        assertNull(decodeQueuedWrite(impossible.toString()))
    }

    // ----- FR-806: come back, or stop and say so -----

    @Test
    fun `an unreachable network is always worth retrying`() {
        assertTrue(isWorthRetrying(GoogleUnreachable("no network", IOException())))
    }

    @Test
    fun `a server-side or throttled failure is worth retrying`() {
        assertTrue(isWorthRetrying(GoogleRejected(429, "rateLimitExceeded", "Slow down")))
        assertTrue(isWorthRetrying(GoogleRejected(500, null, "Internal Error")))
        assertTrue(isWorthRetrying(GoogleRejected(503, null, "Unavailable")))
    }

    @Test
    fun `a failure that will never come right is not retried`() {
        // Retrying these spends battery on a request that cannot succeed, and leaves the
        // count on the home screen with nothing said about why it never moves.
        assertFalse(isWorthRetrying(GoogleRejected(400, "badRequest", "Bad Request")))
        assertFalse(isWorthRetrying(GoogleRejected(403, "ACCESS_TOKEN_SCOPE_INSUFFICIENT", "Forbidden")))
        assertFalse(isWorthRetrying(GoogleRejected(404, "notFound", "Not Found")))
        // A 401 has already been retried once with a fresh token inside GoogleHttp. A second
        // one means the grant is gone, and only the user can supply a new one.
        assertFalse(isWorthRetrying(GoogleRejected(401, null, "Unauthorized")))
        // Not one of ours: a bug in the mapping. Retrying a bug repeats it.
        assertFalse(isWorthRetrying(IllegalStateException("boom")))
    }
}
