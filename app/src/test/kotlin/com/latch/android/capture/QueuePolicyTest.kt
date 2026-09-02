package com.latch.android.capture

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.FailureClass
import com.latch.data.PendingWrite
import com.latch.data.QueuedWrite
import com.latch.wire.RemoteMetadata
import com.latch.data.WriteOperation
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FR-806's two new policies, both pure: when a backoff may be thrown away, and how long a
 * backoff is allowed to get.
 *
 * Pure for the reason `drainable` is, and for the reason the drain's own FR-803 check was not:
 * a decision that needs a `Context` to reach is a decision with no test, and this project has
 * paid a day for that twice.
 */
class QueuePolicyTest {

    private val now: Instant = Instant.parse("2026-09-02T09:00:00Z")

    private fun entry(
        id: String = "q1",
        attempts: Int = 1,
        failureClass: FailureClass = FailureClass.TRANSPORT,
        givenUp: Boolean = false,
        writtenItemIds: Set<String> = emptySet(),
    ) = QueuedWrite(
        id = id,
        write = PendingWrite(
            items = listOf(item("chain#0")),
            metadata = metadata,
            body = "body",
            timeZone = "Asia/Kolkata",
        ),
        operation = WriteOperation.CREATE,
        attempts = attempts,
        lastError = null,
        givenUp = givenUp,
        queuedAt = now.minusSeconds(600),
        failureClass = failureClass,
        writtenItemIds = writtenItemIds,
    )

    private val metadata = RemoteMetadata(
        sourceHash = "a".repeat(64),
        itemKey = "b".repeat(64),
        chainId = "chain",
        capturedAt = Instant.parse("2026-09-02T08:00:00Z"),
    )

    private fun item(id: String) = Item(
        id = id,
        captureId = "capture",
        chainId = "chain",
        type = ItemType.TASK,
        title = "Fees",
        dueDate = LocalDate.parse("2027-09-20"),
        taskListId = "list-1",
    )

    // ----- shouldDrainNow -----

    @Test
    fun `a tap always drains, and is never rate-limited`() {
        // Refusing because a drain happened forty seconds ago would look exactly like a broken
        // button, which is worse than a wasted request.
        assertTrue(
            shouldDrainNow(
                entries = listOf(entry()),
                trigger = DrainTrigger.USER_ASKED,
                lastImmediateDrain = now.minusSeconds(1),
                now = now,
            )
        )
    }

    @Test
    fun `a tap drains even with nothing waiting, because the user asked`() {
        assertTrue(shouldDrainNow(emptyList(), DrainTrigger.USER_ASKED, null, now))
    }

    @Test
    fun `connectivity returning drains a queue held up by the transport`() {
        assertTrue(
            shouldDrainNow(listOf(entry()), DrainTrigger.CONNECTIVITY_RESTORED, null, now)
        )
    }

    @Test
    fun `a successful foreground request drains it too`() {
        assertTrue(
            shouldDrainNow(listOf(entry()), DrainTrigger.FOREGROUND_REQUEST_SUCCEEDED, null, now)
        )
    }

    @Test
    fun `a server failure is not cured by news about the network`() {
        // The failure this pins: a 429 retried the instant connectivity is reported is exactly
        // the hammering a rate limit exists to describe.
        assertFalse(
            shouldDrainNow(
                listOf(entry(failureClass = FailureClass.SERVER)),
                DrainTrigger.CONNECTIVITY_RESTORED,
                null,
                now,
            )
        )
    }

    @Test
    fun `a sign-in hold is not cured by the network either`() {
        // FR-806a: it is waiting on a tap, and the home screen already says so.
        assertFalse(
            shouldDrainNow(
                listOf(entry(failureClass = FailureClass.SIGN_IN)),
                DrainTrigger.CONNECTIVITY_RESTORED,
                null,
                now,
            )
        )
    }

    @Test
    fun `an entry that has never been attempted needs no bypass`() {
        // It is already scheduled; replacing the request for it would mean every connectivity
        // blip cancelling work that was about to run anyway.
        assertFalse(
            shouldDrainNow(
                listOf(entry(attempts = 0)),
                DrainTrigger.CONNECTIVITY_RESTORED,
                null,
                now,
            )
        )
    }

    @Test
    fun `a given-up entry is not grounds for a drain`() {
        assertFalse(
            shouldDrainNow(
                listOf(entry(givenUp = true)),
                DrainTrigger.CONNECTIVITY_RESTORED,
                null,
                now,
            )
        )
    }

    @Test
    fun `an ordinary enqueue does not bypass the backoff`() {
        // A new capture is new work; it says nothing about why the old work stopped.
        assertFalse(shouldDrainNow(listOf(entry()), DrainTrigger.CAPTURE_QUEUED, null, now))
        assertFalse(shouldDrainNow(listOf(entry()), DrainTrigger.PROCESS_START, null, now))
    }

    @Test
    fun `the rate limit holds off a burst of connectivity callbacks`() {
        // A train, or a lift: onAvailable fires repeatedly and a burst of REPLACEd requests
        // would be the hammering KEEP was there to prevent, arrived at from the other side.
        assertFalse(
            shouldDrainNow(
                listOf(entry()),
                DrainTrigger.CONNECTIVITY_RESTORED,
                lastImmediateDrain = now.minusSeconds(30),
                now = now,
            )
        )
        assertTrue(
            shouldDrainNow(
                listOf(entry()),
                DrainTrigger.CONNECTIVITY_RESTORED,
                lastImmediateDrain = now.minus(IMMEDIATE_DRAIN_RATE_LIMIT),
                now = now,
            )
        )
    }

    // ----- retryPlan -----

    @Test
    fun `the first attempts use WorkManager's own backoff`() {
        (0..7).forEach { attempt ->
            assertIs<RetryPlan.Backoff>(retryPlan(attempt), "attempt $attempt should back off")
        }
    }

    @Test
    fun `the ceiling bites at the attempt whose next wait would exceed it`() {
        // 10s doubled eight times is 2560s, past the half-hour ceiling. Left alone WorkManager
        // would go on doubling to five hours, which is how a queue that failed overnight is
        // still waiting at breakfast.
        val plan = assertIs<RetryPlan.Reschedule>(retryPlan(8))
        assertEquals(BACKOFF_CEILING, plan.delay)
    }

    @Test
    fun `the wait pins at the ceiling rather than continuing to double`() {
        listOf(9, 20, 100).forEach { attempt ->
            assertEquals(BACKOFF_CEILING, assertIs<RetryPlan.Reschedule>(retryPlan(attempt)).delay)
        }
    }

    @Test
    fun `an absurd attempt count does not turn the shift into nonsense`() {
        // runAttemptCount comes from WorkManager and a very long-lived entry should not be
        // able to overflow the doubling into a negative delay.
        assertEquals(BACKOFF_CEILING, assertIs<RetryPlan.Reschedule>(retryPlan(Int.MAX_VALUE)).delay)
    }

    @Test
    fun `the floor and ceiling are parameters, so the policy is a reading and not a constant`() {
        assertIs<RetryPlan.Backoff>(
            retryPlan(8, floor = Duration.ofSeconds(1), ceiling = Duration.ofHours(1))
        )
    }

    // ----- SRS 1.24's resume -----

    @Test
    fun `a fresh entry has every item left to write`() {
        val entry = entry().let {
            it.copy(write = it.write.copy(items = listOf(item("chain#0"), item("chain#1"))))
        }
        assertEquals(listOf("chain#0", "chain#1"), itemsLeftToWrite(entry).map { it.id })
    }

    @Test
    fun `a chain that failed part-way resumes at the first unwritten item`() {
        // The defect this cures, from SRS 1.24: the entry stays queued, FR-803 at the head of
        // the retry finds the items that *did* get written, answers "yes, this message was
        // saved", and the drain retires the entry with the rest of the chain never written.
        val entry = entry(writtenItemIds = setOf("chain#0")).let {
            it.copy(
                write = it.write.copy(
                    items = listOf(item("chain#0"), item("chain#1"), item("chain#2")),
                ),
            )
        }
        assertEquals(listOf("chain#1", "chain#2"), itemsLeftToWrite(entry).map { it.id })
    }

    @Test
    fun `a chain whose every item is marked has nothing left to write`() {
        val entry = entry(writtenItemIds = setOf("chain#0")).let {
            it.copy(write = it.write.copy(items = listOf(item("chain#0"))))
        }
        assertTrue(itemsLeftToWrite(entry).isEmpty())
    }
}
