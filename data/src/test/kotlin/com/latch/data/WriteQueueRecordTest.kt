package com.latch.data

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.core.model.SyncState
import com.latch.google.GoogleHttp
import com.latch.google.GoogleRejected
import com.latch.google.GoogleUnreachable
import com.latch.google.ItemDates
import com.latch.google.isWorthRetrying
import com.latch.wire.RemoteMetadata
import com.latch.wire.itemKeyOf
import com.latch.wire.sourceHashOf
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
        write = PendingWrite(items = listOf(item), metadata = metadata, body = body, timeZone = "Asia/Kolkata"),
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

    // ----- FR-804: an UPDATE, which the record could not express before v1.16 -----

    @Test
    fun `an update carries the item it targets and what that item held before`() {
        val prior = ItemDates.Event(
            start = LocalDateTime.parse("2030-03-05T21:30"),
            end = LocalDateTime.parse("2030-03-05T22:30"),
            timeZone = "Asia/Kolkata",
        )
        val update = entry().copy(
            operation = WriteOperation.UPDATE,
            write = entry().write.copy(targetRemoteId = "ev_42", priorState = prior),
        )

        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(update)))

        assertEquals(WriteOperation.UPDATE, decoded.operation)
        // Without the target, an entry that outlived the process would wake with new dates
        // and no way to say which item they belonged to.
        assertEquals("ev_42", decoded.write.targetRemoteId)
        // Without the prior state, FR-807's undo of this update would have nothing to write
        // back — and deleting the item instead would destroy one the user already had.
        assertEquals(prior, decoded.write.priorState)
    }

    @Test
    fun `a task update carries its previous due date, including none at all`() {
        val task = Item(
            id = "chain-2#0",
            captureId = "cap-2",
            chainId = "chain-2",
            type = ItemType.TASK,
            title = "Project sync",
            dueDate = LocalDate.parse("2030-03-07"),
            taskListId = "list-1",
        )

        fun roundTrip(prior: ItemDates.Task): ItemDates? {
            val base = entry(item = task)
            val update = base.copy(
                operation = WriteOperation.UPDATE,
                write = base.write.copy(targetRemoteId = "t_9", priorState = prior),
            )
            return decodeQueuedWrite(encodeQueuedWrite(update))?.write?.priorState
        }

        assertEquals(ItemDates.Task(LocalDate.parse("2030-03-05")), roundTrip(ItemDates.Task(LocalDate.parse("2030-03-05"))))
        // An undated item that an update gave a date to is undone by taking the date away
        // again, so "there was no due date" has to survive as a value.
        assertEquals(ItemDates.Task(null), roundTrip(ItemDates.Task(null)))
    }

    @Test
    fun `a create carries neither, and omits them rather than writing them empty`() {
        val json = JSONObject(encodeQueuedWrite(entry()))

        // The rule §7.2 applies to its own optional keys: an empty value cannot be told from
        // a value that is genuinely empty.
        assertFalse(json.has("target"), "a CREATE has no target")
        assertFalse(json.has("prior"), "a CREATE is undone by deleting what it made")

        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(entry())))
        assertNull(decoded.write.targetRemoteId)
        assertNull(decoded.write.priorState)
    }

    @Test
    fun `a version 1 record still decodes, because dropping it would lose a capture`() {
        // The account-defaults store drops a v1 record and lets setup run again. This one
        // cannot: the entry is the only copy of the capture, and NFR-302 forbids losing it.
        // Nothing has to be inferred either — an UPDATE was never issued at v1, so every v1
        // record is a CREATE, which carries neither new field anyway.
        val v1 = JSONObject(encodeQueuedWrite(entry()))
        v1.remove("target")
        v1.remove("prior")
        v1.put("v", 1)

        val decoded = assertNotNull(decodeQueuedWrite(v1.toString()), "a v1 entry must still drain")
        assertEquals(WriteOperation.CREATE, decoded.operation)
        assertEquals("Team sync", decoded.write.item.title)
        assertNull(decoded.write.targetRemoteId)
        assertNull(decoded.write.priorState)
    }

    @Test
    fun `a prior state of a kind this version does not know decodes to null`() {
        val update = JSONObject(
            encodeQueuedWrite(
                entry().copy(
                    operation = WriteOperation.UPDATE,
                    write = entry().write.copy(
                        targetRemoteId = "ev_42",
                        priorState = ItemDates.Event(
                            start = LocalDateTime.parse("2030-03-05T21:30"),
                            end = LocalDateTime.parse("2030-03-05T22:30"),
                        ),
                    ),
                ),
            ),
        )
        update.getJSONObject("prior").put("kind", "SOMETHING_LATER")

        // Null rather than a throw: one entry a later version wrote must not take out a
        // drain that would have written the others.
        assertNull(decodeQueuedWrite(update.toString())?.write?.priorState)
    }

    // ----- FR-511: a chain is one entry (SRS §7.1 at v1.23) -----

    @Test
    fun `a chain of items survives the round trip in order`() {
        val second = event.copy(
            id = "chain-1#1",
            title = "Team sync follow-up",
            start = LocalDateTime.parse("2026-09-12T15:00"),
            end = LocalDateTime.parse("2026-09-12T16:00"),
        )
        val chain = entry().let { it.copy(write = it.write.copy(items = listOf(event, second))) }

        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(chain)))

        // Order matters: the items are written in it, and #0 is the one FR-803 is asked of.
        assertEquals(listOf("chain-1#0", "chain-1#1"), decoded.write.items.map { it.id })
        assertEquals(LocalDateTime.parse("2026-09-12T15:00"), decoded.write.items[1].start)
        // One source_hash for the whole capture, so one entry carries one metadata block.
        assertEquals(metadata.sourceHash, decoded.write.metadata.sourceHash)
    }

    @Test
    fun `a version 2 record decodes as a chain of one, which is what it was`() {
        // v1 and v2 held a single item under "item". Nothing has to be inferred to read one:
        // a capture that produced one item is a chain of one.
        val v2 = JSONObject(encodeQueuedWrite(entry()))
        val items = v2.getJSONArray("items")
        v2.remove("items")
        v2.put("item", items.getJSONObject(0))
        v2.put("v", 2)

        val decoded = assertNotNull(decodeQueuedWrite(v2.toString()), "a v2 entry must still drain")
        assertEquals(1, decoded.write.items.size)
        assertEquals("Team sync", decoded.write.item.title)
    }

    @Test
    fun `an entry with no items at all is refused rather than drained to nothing`() {
        val empty = JSONObject(encodeQueuedWrite(entry()))
        empty.put("items", org.json.JSONArray())
        assertNull(decodeQueuedWrite(empty.toString()))
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
        // JSONObject.remove returns the value it removed, not the object — stringifying the
        // call was testing the item rather than the record it had been taken out of.
        val noItems = JSONObject(encodeQueuedWrite(entry()))
        noItems.remove("items")
        assertNull(decodeQueuedWrite(noItems.toString()))

        val badTime = JSONObject(encodeQueuedWrite(entry())).put("queued_at", "half past three")
        assertNull(decodeQueuedWrite(badTime.toString()))

        val badType = JSONObject(encodeQueuedWrite(entry()))
        badType.getJSONArray("items").getJSONObject(0).put("type", "REMINDER")
        assertNull(decodeQueuedWrite(badType.toString()))
    }

    @Test
    fun `a record that violates Item's own rules decodes to null`() {
        // A task carrying a start: Item's init refuses it (§8.1), and that refusal must
        // surface as an unreadable record rather than as a crash inside the worker.
        val impossible = JSONObject(encodeQueuedWrite(entry()))
        impossible.getJSONArray("items").getJSONObject(0).put("type", "TASK")
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

    // ----- FR-806a: the queue has to be able to say why it is not moving -----

    @Test
    fun `a sign-in requirement is not the same as having been given up on`() {
        // The distinction the home screen rests on. An entry held for a sign-in is still
        // waiting and still retried; one given up on is not going to move whatever the user
        // does. Collapsing them would either nag about a stuck queue that is merely offline,
        // or hide the one stuck state a user can actually clear.
        val held = QueueStatus(waiting = 2, givenUp = 0, needsSignIn = true)
        val offline = QueueStatus(waiting = 2, givenUp = 0)
        val stuck = QueueStatus(waiting = 0, givenUp = 1)

        assertTrue(held.needsSignIn)
        assertFalse(offline.needsSignIn)
        assertFalse(stuck.needsSignIn)
        // Held entries are still counted as waiting: nothing has been lost, which is the
        // half of the message that reassures rather than alarms.
        assertEquals(2, held.waiting)
    }

    @Test
    fun `the sign-in reason survives the encrypted round trip`() {
        // It is persisted rather than held in memory because the process that learned it -
        // a WorkManager drain - is not the process that has to display it.
        val held = entry(attempts = 1, lastError = "needs consent").copy(needsSignIn = true)
        val restored = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(held)))

        assertTrue(restored.needsSignIn)
        assertFalse(restored.givenUp, "a held entry must not be recorded as given up on")
    }

    @Test
    fun `FR-510's past-date note survives the record`() {
        // The note is the whole of what FR-510 requires be kept — "with the past date recorded
        // in the notes" — so a queued follow-up that lost it on the way to disk would satisfy
        // the requirement on screen and not in the user's account.
        val withNote = entry().let { queued ->
            queued.copy(
                write = queued.write.copy(
                    items = listOf(queued.write.item.copy(notes = "Originally dated 12 March 2026.")),
                ),
            )
        }
        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(withNote)))
        assertEquals("Originally dated 12 March 2026.", decoded.write.item.notes)
    }

    @Test
    fun `an item with no note decodes to none rather than to an empty string`() {
        val decoded = assertNotNull(decodeQueuedWrite(encodeQueuedWrite(entry())))
        assertNull(decoded.write.item.notes)
    }
}
