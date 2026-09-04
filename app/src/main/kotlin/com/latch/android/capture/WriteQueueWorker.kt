package com.latch.android.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.latch.android.LatchApplication
import com.latch.android.cards.drainHeldCards
import com.latch.google.CalendarApi
import com.latch.data.QueuedWrite
import com.latch.google.SignInRequiredException
import com.latch.google.TasksApi
import com.latch.data.WriteQueue
import com.latch.google.failureClassOf
import com.latch.google.isWorthRetrying
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * FR-806: drains the write queue when there is a network again. NFR-302 is its contract —
 * nothing lost to network failure, app termination or device restart.
 *
 * **It carries no payload.** The work request holds nothing but the instruction to drain.
 * WorkManager's own database is not encrypted and its input `Data` is capped at roughly
 * 10 KB, so putting a capture in it would both put the user's text in plaintext storage and
 * fail on a long one. `EncryptedWriteQueueStore` holds the entries; this reads them.
 *
 * **FR-803 runs again here, per entry, immediately before the insert.** The check the saver
 * would have run could not run at all — there was no network, which is why the entry exists.
 * Without repeating it, capturing the same message twice offline would produce two items on
 * reconnection, and AC-07 would fail in precisely the situation AC-10 creates.
 */
class WriteQueueWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as LatchApplication
        val result = drain(
            queue = app.writeQueue,
            calendarApi = app.calendarApi,
            tasksApi = app.tasksApi,
        )
        // FR-1212 rides the same worker, the same backoff and the same triggers — see
        // `CardQueueStore`'s note on why it does not ride the same *record*. Its outcome does not
        // change this worker's Result: a card that could not be written is retried on the next
        // pass like anything else, and a date capture must not be held back by one.
        runCatching { drainHeldCards(app.cardQueue, app.contactsApi) }.getOrNull()
        // FR-806: whatever happened, the count on the home screen is now wrong.
        app.refreshQueueStatus()
        return result
    }

    private suspend fun drain(
        queue: WriteQueue,
        calendarApi: CalendarApi,
        tasksApi: TasksApi,
    ): Result {
        val entries = queue.pending()
        val now = Instant.now()
        val ready = drainable(entries, now)

        // Something is there but not yet ours to touch — an entry still inside its FR-807
        // undo window. Come back rather than report the queue drained.
        if (ready.isEmpty()) {
            return if (entries.any { !it.givenUp }) retryResult() else Result.success()
        }

        var retryable = false

        for (entry in ready) {
            try {
                drainEntry(entry, queue, calendarApi, tasksApi)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val worthRetrying = isWorthRetrying(failure)
                // NFR-303: the reason is kept, because a queue entry the user can see with
                // no reason attached is a silent failure with a number next to it.
                queue.markFailed(
                    queueId = entry.id,
                    error = failure.message ?: failure::class.simpleName.orEmpty(),
                    permanent = !worthRetrying,
                    // FR-806a: a drain runs online by construction, so a resolution required
                    // here is not the network — it is Google asking for a sign-in, and the
                    // queue has to be able to say so rather than showing a silent wait.
                    needsSignIn = failure is SignInRequiredException,
                    // FR-806's immediate drain reads this: only a transport failure is cured
                    // by learning that the network is back.
                    failureClass = failureClassOf(failure),
                )
                // One entry's permanent failure does not stop the rest: the next capture in
                // the queue is a different item and may write perfectly well.
                if (worthRetrying) retryable = true
            }
        }

        return if (retryable) retryResult() else Result.success()
    }

    /**
     * `Result.retry()` while the wait is inside the ceiling, and a fresh delayed request once
     * it is not.
     *
     * WorkManager's exponential backoff doubles to `MAX_BACKOFF_MILLIS` — five hours — which is
     * how a queue that failed overnight is still waiting at breakfast. Enqueueing a new request
     * at the ceiling resets the attempt counter, so the wait pins there instead of continuing
     * to double. See [retryPlan], which is where the arithmetic is and where it is tested.
     *
     * **Enqueueing under this worker's own unique name while it is running cancels this run.**
     * That is intended and is why the plan is chosen *after* everything else has been done:
     * `doWork` has no work left, the replacement is enqueued regardless, and a cancelled
     * `Result` that nobody reads is the correct outcome. It is called out because it looks like
     * a mistake, and because it is the one thing here a device pass has to watch rather than
     * reason about.
     */
    private fun retryResult(): Result = when (val plan = retryPlan(runAttemptCount)) {
        RetryPlan.Backoff -> Result.retry()
        is RetryPlan.Reschedule -> {
            schedule(applicationContext, after = plan.delay, replaceExisting = true)
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "latch.write-queue"

        /**
         * Asks for a drain.
         *
         * `KEEP` by default, so a burst of offline captures schedules one drain and not one per
         * capture — the worker reads the whole queue anyway. [replaceExisting] is the override
         * FR-806's immediate drain needs: `KEEP` correctly refuses to reset a backoff timer,
         * which is right for a new capture and wrong when the reason for the backoff has
         * measurably gone away. `shouldDrainNow` is what decides that, and it is pure.
         */
        fun schedule(
            context: Context,
            after: Duration = Duration.ZERO,
            replaceExisting: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<WriteQueueWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_FLOOR)
                .apply { if (!after.isZero) setInitialDelay(after) }
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Which queue entries the worker may write now. Pure, so FR-806's policy is testable without
 * WorkManager, a device or a clock.
 *
 * Two exclusions, and the first is the interesting one.
 *
 * **An entry inside its FR-807 undo window is not touched.** The user has ten seconds to take
 * a save back, and for a queued save taking it back means dropping the entry. If the worker
 * could drain inside that window, an undo would sometimes be a dequeue and sometimes a chase
 * into the user's account after an item that had just been written — a race whose losing side
 * is a delete the user did not watch happen. Skipping young entries removes the race instead of
 * trying to win it, at a cost of at most ten seconds on a write that is already late.
 *
 * **An entry that has been given up on is not retried.** It stays, because dropping it would
 * lose the capture, and it is counted separately so the user is told it is stuck. FR-806's
 * "Retry now" is how it comes back — `reviveGivenUp` clears the flag, and then this admits it.
 */
internal fun drainable(entries: List<QueuedWrite>, now: Instant): List<QueuedWrite> =
    entries.filter { entry ->
        !entry.givenUp && !entry.queuedAt.plus(UNDO_WINDOW).isAfter(now)
    }
