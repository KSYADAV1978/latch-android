package com.latch.android.capture

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.PendingWrite
import com.latch.data.QueuedWrite
import com.latch.wire.RemoteMetadata
import com.latch.data.WriteOperation
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-806's drain policy, which is where it meets FR-807.
 *
 * Pure, so it runs without WorkManager, a device or a clock — the same reason the FR-105
 * reducer and the parser corpus do. What WorkManager itself does with the answer (backoff,
 * the connectivity constraint, rescheduling after a restart) is its contract and not ours to
 * re-test; which entries it is handed is entirely ours.
 */
class WriteQueueDrainTest {

    private val now = Instant.parse("2026-08-27T10:00:00Z")

    private fun entry(
        id: String,
        queuedAt: Instant,
        givenUp: Boolean = false,
    ) = QueuedWrite(
        id = id,
        write = PendingWrite(
            items = listOf(
                Item(
                    id = "$id#0",
                    captureId = "cap-$id",
                    type = ItemType.EVENT,
                    title = "Team sync",
                    start = LocalDateTime.parse("2026-09-05T15:00"),
                    end = LocalDateTime.parse("2026-09-05T16:00"),
                    calendarId = "latch-cal",
                ),
            ),
            metadata = RemoteMetadata(
                sourceHash = "a".repeat(64),
                itemKey = "b".repeat(64),
                chainId = id,
                capturedAt = queuedAt,
            ),
            body = "Team sync 5 Sep at 3pm",
            timeZone = "Asia/Kolkata",
        ),
        operation = WriteOperation.CREATE,
        attempts = 0,
        lastError = null,
        givenUp = givenUp,
        queuedAt = queuedAt,
    )

    @Test
    fun `an entry inside its undo window is left alone`() {
        // FR-807 promised the user ten seconds to take this back, and for a queued save
        // taking it back means dropping the entry. Draining inside the window would turn
        // some undos into a chase after an item that had just been written.
        val young = entry("young", now.minusSeconds(3))
        assertTrue(drainable(listOf(young), now).isEmpty())
    }

    @Test
    fun `an entry becomes drainable the moment its undo window closes`() {
        val entry = entry("e", now.minus(UNDO_WINDOW))
        assertEquals(listOf("e"), drainable(listOf(entry), now).map { it.id })
    }

    @Test
    fun `an entry given up on is not retried`() {
        // It stays in the queue — dropping it would lose the capture, which is the one thing
        // NFR-302 forbids — but nothing sends it again.
        val stuck = entry("stuck", now.minusSeconds(600), givenUp = true)
        assertTrue(drainable(listOf(stuck), now).isEmpty())
    }

    @Test
    fun `a young entry does not hold back an older one`() {
        val old = entry("old", now.minusSeconds(600))
        val young = entry("young", now.minusSeconds(1))
        assertEquals(listOf("old"), drainable(listOf(old, young), now).map { it.id })
    }

    @Test
    fun `the undo window the drain waits out is FR-807's own`() {
        // Not a separate constant that could drift from it: the two are the same promise,
        // read from the same place.
        assertEquals(10, UNDO_WINDOW.seconds)
    }
}
