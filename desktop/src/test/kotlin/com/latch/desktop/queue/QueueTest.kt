package com.latch.desktop.queue

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.desktop.store.BridgeReply
import com.latch.desktop.store.WindowsSecrets
import com.latch.desktop.store.base64
import com.latch.desktop.store.unbase64
import com.latch.google.FailureClass
import com.latch.google.RetryPolicy
import com.latch.wire.RemoteMetadata
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

internal fun metadata(hash: String = "h1") = RemoteMetadata(
    sourceHash = hash,
    itemKey = "k1",
    chainId = "ch1",
    capturedAt = Instant.parse("2026-09-02T10:00:00Z"),
)

internal fun anEvent(id: String = "i1") = Item(
    id = id,
    captureId = "c1",
    chainId = "ch1",
    type = ItemType.EVENT,
    title = "Kickoff",
    start = LocalDateTime.of(2027, 9, 8, 9, 0),
    end = LocalDateTime.of(2027, 9, 8, 10, 0),
    location = "Room 2",
    reminderMinutes = listOf(30, 10),
)

internal fun aTask(id: String = "i2") = Item(
    id = id,
    captureId = "c1",
    chainId = "ch1",
    type = ItemType.TASK,
    title = "Fees",
    dueDate = LocalDate.of(2027, 9, 20),
    notes = "Originally dated 12 March 2026.",
)

internal fun anEntry(
    id: String = "q1",
    items: List<QueuedItem> = listOf(QueuedItem(anEvent())),
    queuedAt: Instant = Instant.parse("2026-09-02T10:00:00Z"),
    attempts: Int = 0,
    failureClass: FailureClass? = null,
    nextAttemptAt: Instant = queuedAt,
    givenUp: Boolean = false,
) = QueuedWrite(
    id = id,
    metadata = metadata(),
    body = "Kickoff 8 September 2027 at 9am",
    calendarId = "cal-1",
    taskListId = "list-1",
    timeZone = "Asia/Kolkata",
    items = items,
    queuedAt = queuedAt,
    attempts = attempts,
    failureClass = failureClass,
    nextAttemptAt = nextAttemptAt,
    givenUp = givenUp,
)

class QueueRecordTest {

    @Test
    fun `a record round trips with everything a write needs`() {
        val entry = anEntry(
            items = listOf(QueuedItem(anEvent(), writtenId = "ev1"), QueuedItem(aTask())),
            attempts = 3,
            failureClass = FailureClass.TRANSPORT,
        )
        val decoded = decodeQueuedWrite(entry.encode())
        assertEquals(entry, decoded)
    }

    @Test
    fun `the metadata survives byte for byte, because it is written once at insert`() {
        // §7.2 forbids recomputing metadata, so a queued write must carry the values the save
        // derived. A round trip that lost the item key would make the drained item
        // unmatchable by FR-804 for ever after.
        val decoded = decodeQueuedWrite(anEntry().encode())!!
        assertEquals("h1", decoded.metadata.sourceHash)
        assertEquals("k1", decoded.metadata.itemKey)
        assertEquals("ch1", decoded.metadata.chainId)
        assertEquals(Instant.parse("2026-09-02T10:00:00Z"), decoded.metadata.capturedAt)
    }

    @Test
    fun `an absent recipe stays absent rather than becoming an empty string`() {
        // §7.2 is strict about this: `latch.recipe` must not exist on an item that had no
        // recipe, and an empty string is a value.
        val decoded = decodeQueuedWrite(anEntry().encode())!!
        assertNull(decoded.metadata.recipeId)
        assertNull(decoded.metadata.sourceApp)
    }

    @Test
    fun `reminders and the FR-510 note survive`() {
        val decoded = decodeQueuedWrite(
            anEntry(items = listOf(QueuedItem(anEvent()), QueuedItem(aTask()))).encode()
        )!!
        assertEquals(listOf(30, 10), decoded.items[0].item.reminderMinutes)
        assertEquals("Originally dated 12 March 2026.", decoded.items[1].item.notes)
        assertEquals(LocalDate.of(2027, 9, 20), decoded.items[1].item.dueDate)
    }

    @Test
    fun `SRS 1_24's written marker survives to disk`() {
        val decoded = decodeQueuedWrite(
            anEntry(items = listOf(QueuedItem(anEvent(), "ev1"), QueuedItem(aTask()))).encode()
        )!!
        assertEquals("ev1", decoded.items[0].writtenId)
        assertNull(decoded.items[1].writtenId)
        assertEquals(1, decoded.remaining.size)
    }

    @Test
    fun `a record from another version is not read as this one`() {
        val other = anEntry().encode().replace("\"v\":1", "\"v\":2")
        assertNull(decodeQueuedWrite(other))
    }

    @Test
    fun `a record missing something a write needs is refused rather than half-built`() {
        // Better to report a record as unreadable — the queue keeps it — than to write an item
        // to a calendar called the empty string.
        listOf("calendar_id", "task_list_id", "id").forEach { field ->
            val broken = anEntry().encode().replace("\"" + field + "\":", "\"x_" + field + "\":")
            assertNull(decodeQueuedWrite(broken), field + " should have made this undecodable")
        }
        assertNull(decodeQueuedWrite("not json"))
        assertNull(decodeQueuedWrite("{}"))
    }

    @Test
    fun `an entry with no items is not a queue entry`() {
        val empty = anEntry().encode().replace(Regex("\"items\":\\[.*?\\]"), "\"items\":[]")
        assertNull(decodeQueuedWrite(empty))
    }
}

class WriteQueueTest {

    private val directory: File = File.createTempFile("latch-queue", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val file = File(directory, "queue.dat")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** Speaks the real bridge's protocol — base64 in, base64 out — and is its own inverse. */
    private fun cipher() = WindowsSecrets { _, payload ->
        BridgeReply("OK " + base64(unbase64(payload).reversedArray()))
    }

    private fun queue() = WriteQueue(file, cipher())

    @Test
    fun `an entry survives being written and read back`() {
        val queue = queue()
        queue.add(anEntry())
        assertEquals(listOf(anEntry()), queue.entries())
    }

    @Test
    fun `the captured text is not in the file in the clear`() {
        // The body is FR-805's description: the user's own message. It is encrypted for the
        // same reason the Inbox is on the phone.
        queue().add(anEntry())
        val onDisk = file.readText()
        assertFalse("Kickoff 8 September 2027" in onDisk, onDisk.take(200))
    }

    @Test
    fun `an entry this build cannot decrypt is KEPT, not dropped`() {
        // The difference between this store and every other one here. Elsewhere an unreadable
        // record is dropped, and that is right when dropping means "setup runs again". Here it
        // means losing a capture the user believes they have saved, which NFR-302 forbids —
        // so it stays in the file and is counted, waiting for a build that understands it.
        val refusing = WindowsSecrets { mode, payload ->
            if (mode == WindowsSecrets.Mode.UNPROTECT && payload == "QkFE") BridgeReply("ERR no")
            else BridgeReply("OK " + base64(unbase64(payload).reversedArray()))
        }
        queue().add(anEntry())
        file.appendText("\nQkFE")

        val queue = WriteQueue(file, refusing)
        assertEquals(1, queue.entries().size, "the readable entry was lost")
        assertEquals(1, queue.status().unreadable)

        queue.add(anEntry(id = "q2"))
        assertTrue("QkFE" in file.readText(), "the unreadable record was discarded on rewrite")
    }

    @Test
    fun `replacing an entry keeps the others`() {
        val queue = queue()
        queue.add(anEntry(id = "q1"))
        queue.add(anEntry(id = "q2"))
        queue.replace(anEntry(id = "q1", attempts = 4))
        assertEquals(4, queue.entries().single { it.id == "q1" }.attempts)
        assertEquals(0, queue.entries().single { it.id == "q2" }.attempts)
    }

    @Test
    fun `removing the last entry deletes the file rather than leaving a stub`() {
        val queue = queue()
        queue.add(anEntry())
        queue.remove("q1")
        assertFalse(file.exists())
    }

    @Test
    fun `Retry now revives a given-up entry and resets its wait`() {
        // Leaving the attempt count would put the revived entry straight back at the half-hour
        // ceiling, so the button would do nothing the user could see.
        val now = Instant.parse("2026-09-02T12:00:00Z")
        val queue = queue()
        queue.add(anEntry(attempts = 12, givenUp = true, nextAttemptAt = now.plusSeconds(9999)))
        queue.reviveAll(now)

        val revived = queue.entries().single()
        assertFalse(revived.givenUp)
        assertEquals(0, revived.attempts)
        assertEquals(now, revived.nextAttemptAt)
    }

    @Test
    fun `the status counts waiting and stuck apart`() {
        val queue = queue()
        queue.add(anEntry(id = "a"))
        queue.add(anEntry(id = "b", givenUp = true))
        assertEquals(QueueStatus(waiting = 1, givenUp = 1, unreadable = 0), queue.status())
        assertEquals(2, queue.status().total)
    }

    @Test
    fun `a file from another version reads as empty rather than as records`() {
        queue().add(anEntry())
        val body = file.readLines()
        file.writeText((listOf("latch-queue/2") + body.drop(1)).joinToString("\n"))
        assertTrue(queue().entries().isEmpty())
    }

    @Test
    fun `NFR-205 clear leaves nothing`() {
        val queue = queue()
        queue.add(anEntry())
        queue.clear()
        assertFalse(file.exists())
    }
}

class DrainPolicyTest {

    private val now = Instant.parse("2026-09-02T12:00:00Z")

    @Test
    fun `an entry inside the undo window is not drained`() {
        // FR-807's undo is not built on this client yet, and the rule is here anyway: a queue
        // that drained instantly would make the undo unimplementable rather than merely absent.
        val fresh = anEntry(queuedAt = now.minusSeconds(3))
        assertTrue(drainable(listOf(fresh), now).isEmpty())

        val settled = anEntry(queuedAt = now.minusSeconds(11))
        assertEquals(1, drainable(listOf(settled), now).size)
    }

    @Test
    fun `an entry waiting out a backoff is not drained early`() {
        val waiting = anEntry(
            queuedAt = now.minusSeconds(600),
            attempts = 3,
            nextAttemptAt = now.plusSeconds(60),
        )
        assertTrue(drainable(listOf(waiting), now).isEmpty())
        assertEquals(1, drainable(listOf(waiting), now.plusSeconds(61)).size)
    }

    @Test
    fun `a given-up entry is never drained by the timer`() {
        val stuck = anEntry(queuedAt = now.minusSeconds(600), givenUp = true)
        assertTrue(drainable(listOf(stuck), now).isEmpty())
    }

    @Test
    fun `connectivity restored bypasses a backoff, but only for a transport failure`() {
        // A 429 is not cured by learning that the socket works. Retrying it early is asking a
        // server that has said it is overloaded to say so again.
        val transport = anEntry(
            queuedAt = now.minusSeconds(600), attempts = 2,
            failureClass = FailureClass.TRANSPORT, nextAttemptAt = now.plusSeconds(600),
        )
        val server = transport.copy(failureClass = FailureClass.SERVER)

        assertTrue(shouldDrainNow(listOf(transport), DrainTrigger.CONNECTIVITY_RESTORED, null, now))
        assertFalse(shouldDrainNow(listOf(server), DrainTrigger.CONNECTIVITY_RESTORED, null, now))
    }

    @Test
    fun `an entry that has never failed is already scheduled and needs no bypass`() {
        val fresh = anEntry(queuedAt = now.minusSeconds(600), attempts = 0)
        assertFalse(shouldDrainNow(listOf(fresh), DrainTrigger.CONNECTIVITY_RESTORED, null, now))
    }

    @Test
    fun `a burst of connectivity events does not become a burst of drains`() {
        val entry = anEntry(attempts = 1, failureClass = FailureClass.TRANSPORT)
        assertTrue(shouldDrainNow(listOf(entry), DrainTrigger.CONNECTIVITY_RESTORED, null, now))
        assertFalse(
            shouldDrainNow(listOf(entry), DrainTrigger.CONNECTIVITY_RESTORED, now.minusSeconds(5), now),
        )
        assertTrue(
            shouldDrainNow(listOf(entry), DrainTrigger.CONNECTIVITY_RESTORED, now.minusSeconds(61), now),
        )
    }

    @Test
    fun `the user asking always drains, even with nothing eligible`() {
        assertTrue(shouldDrainNow(emptyList(), DrainTrigger.USER_ASKED, now, now))
    }

    @Test
    fun `merely having queued something is not grounds to bypass a backoff`() {
        val entry = anEntry(attempts = 2, failureClass = FailureClass.TRANSPORT)
        assertFalse(shouldDrainNow(listOf(entry), DrainTrigger.CAPTURE_QUEUED, null, now))
        assertFalse(shouldDrainNow(listOf(entry), DrainTrigger.SCHEDULED, null, now))
    }

    @Test
    fun `backoff doubles and then stops at the ceiling`() {
        assertEquals(Duration.ofSeconds(10), RetryPolicy.backoffDelay(0))
        assertEquals(Duration.ofSeconds(20), RetryPolicy.backoffDelay(1))
        assertEquals(Duration.ofSeconds(1280), RetryPolicy.backoffDelay(7))
        assertEquals(RetryPolicy.CEILING, RetryPolicy.backoffDelay(8))
        // The number this exists for: left to double, the wait reaches five hours, and a queue
        // that failed overnight would still be waiting at breakfast.
        assertEquals(RetryPolicy.CEILING, RetryPolicy.backoffDelay(40))
        assertEquals(RetryPolicy.CEILING, RetryPolicy.backoffDelay(Int.MAX_VALUE))
    }

    @Test
    fun `a failure schedules the next attempt and eventually gives up`() {
        val failed = afterFailure(anEntry(), com.latch.google.GoogleUnreachable("no network"), now)
        assertEquals(1, failed.attempts)
        assertEquals(FailureClass.TRANSPORT, failed.failureClass)
        assertEquals(now.plus(RetryPolicy.backoffDelay(1)), failed.nextAttemptAt)
        assertFalse(failed.givenUp)

        val last = afterFailure(
            anEntry(attempts = RetryPolicy.GIVE_UP_AFTER - 1),
            com.latch.google.GoogleUnreachable("no network"), now,
        )
        assertTrue(last.givenUp, "an entry must stop retrying eventually")
    }

    @Test
    fun `failures are classified so only the curable ones bypass a backoff`() {
        assertEquals(FailureClass.TRANSPORT, classify(com.latch.google.GoogleUnreachable("x")))
        assertEquals(FailureClass.SIGN_IN, classify(com.latch.google.GoogleRejected(401, "r", "m")))
        assertEquals(FailureClass.SERVER, classify(com.latch.google.GoogleRejected(429, "r", "m")))
        assertEquals(FailureClass.SERVER, classify(com.latch.google.GoogleRejected(503, "r", "m")))
        assertEquals(FailureClass.PERMANENT, classify(com.latch.google.GoogleRejected(400, "r", "m")))
    }

    @Test
    fun `a network interface check answers the question it is asked`() {
        assertFalse(looksConnected(emptyList()))
        // The real interfaces on this machine: it built the project, so something is up.
        assertNotNull(readInterfaces())
    }
}
