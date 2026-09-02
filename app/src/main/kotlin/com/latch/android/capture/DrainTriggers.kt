package com.latch.android.capture

import com.latch.data.FailureClass
import com.latch.data.QueuedWrite
import java.time.Duration
import java.time.Instant

/**
 * What has just happened that might mean the queue could drain now (FR-806).
 *
 * The distinction that matters is between a trigger that merely says *there is work* and one
 * that says *the reason the work stopped may have gone away*. Only the second is grounds for
 * throwing away a backoff the worker is already sitting out.
 */
enum class DrainTrigger {
    /** A capture was just queued. There is new work; nothing about the old work has changed. */
    CAPTURE_QUEUED,

    /** The process started, and a queue may have outlived the one that made it. */
    PROCESS_START,

    /** The OS says a network is available again. This is real news about a transport failure. */
    CONNECTIVITY_RESTORED,

    /**
     * A foreground request to Google succeeded. Stronger news than [CONNECTIVITY_RESTORED] —
     * not "there is a network" but "we just reached Google over it".
     */
    FOREGROUND_REQUEST_SUCCEEDED,

    /** FR-806's "Retry now". The user tapped, and a tap is not rate-limited. */
    USER_ASKED,
}

/**
 * Whether to discard the worker's backoff and drain immediately.
 *
 * **The problem this solves was measured on a device.** `ExistingWorkPolicy.KEEP` correctly
 * refuses to reset a backoff timer — you do not want every launch hammering the API — so a
 * drain that failed during a Wi-Fi outage pushed its next attempt out exponentially, to about
 * seventy minutes, and stayed there long after the network came back. WorkManager's own
 * connectivity constraint does not help: the work is not waiting on connectivity, it is waiting
 * out a timer.
 *
 * Three rules, and the second is the one worth reading twice.
 *
 * **A tap is never rate-limited and never questioned.** The user pressed Retry now; refusing
 * because a drain happened forty seconds ago would look exactly like a broken button.
 *
 * **Only a transport-class failure is cured by news about the network.** A 429 or a 500 says
 * the server is busy or broken, and reaching it faster is the opposite of the answer; retrying
 * one the instant connectivity is reported is the behaviour a rate limit exists to describe.
 * FR-806a's sign-in hold is likewise not a network problem — it is waiting on a tap, and the
 * home screen already says so.
 *
 * **An entry that has never failed is already scheduled** and needs no bypass. Bypassing for it
 * would mean every connectivity blip replacing a request that was about to run anyway.
 *
 * Pure, so FR-806's policy is testable without WorkManager, a device or a clock — the
 * arrangement `drainable` beside it already has, and for the reason the drain's own untested
 * duplicate check cost a day.
 */
fun shouldDrainNow(
    entries: List<QueuedWrite>,
    trigger: DrainTrigger,
    lastImmediateDrain: Instant?,
    now: Instant,
    rateLimit: Duration = IMMEDIATE_DRAIN_RATE_LIMIT,
): Boolean {
    val waiting = entries.filter { !it.givenUp }
    if (trigger == DrainTrigger.USER_ASKED) return true
    if (waiting.isEmpty()) return false
    if (trigger != DrainTrigger.CONNECTIVITY_RESTORED &&
        trigger != DrainTrigger.FOREGROUND_REQUEST_SUCCEEDED
    ) {
        return false
    }
    if (waiting.none { it.attempts > 0 && it.failureClass == FailureClass.TRANSPORT }) return false
    return lastImmediateDrain == null || !now.isBefore(lastImmediateDrain.plus(rateLimit))
}

/**
 * How often an immediate drain may be provoked by something other than a tap.
 *
 * A minute. Connectivity callbacks arrive in bursts on a flaky network — a train, a lift — and
 * a burst of `REPLACE`d work requests would be the hammering `KEEP` was there to prevent,
 * arrived at from the other direction.
 */
val IMMEDIATE_DRAIN_RATE_LIMIT: Duration = Duration.ofMinutes(1)

/**
 * What the worker should do after a failure that is worth retrying.
 *
 * WorkManager's exponential backoff is right for the first few attempts and wrong for the long
 * tail: left alone it doubles to `MAX_BACKOFF_MILLIS`, five hours, so a queue that failed
 * overnight is still waiting at breakfast. A ceiling is what FR-806 was recorded as lacking.
 */
sealed interface RetryPlan {
    /** Inside the ceiling. `Result.retry()`, and WorkManager's own timer is the right one. */
    data object Backoff : RetryPlan

    /**
     * The ceiling is reached. The worker enqueues a fresh request at exactly [delay] instead,
     * which resets the attempt counter and pins the wait at the ceiling from then on.
     */
    data class Reschedule(val delay: Duration) : RetryPlan
}

/**
 * [runAttemptCount] is the count of the run that has just finished — 0 for the first. Under
 * exponential backoff from [floor], the delay WorkManager would choose next is
 * `floor * 2^runAttemptCount`, so the ceiling bites at the first attempt whose next wait would
 * exceed it.
 *
 * At the floor of 10 s and a ceiling of 30 minutes that is the eighth attempt: 10 s, 20, 40,
 * 80, 160, 320, 640, 1280 — and then 1800 for ever rather than 2560, 5120 and on to five hours.
 */
fun retryPlan(
    runAttemptCount: Int,
    floor: Duration = BACKOFF_FLOOR,
    ceiling: Duration = BACKOFF_CEILING,
): RetryPlan {
    // Doubling in millis overflows nothing here, but the attempt count comes from WorkManager
    // and a very long-lived entry should not be able to turn the shift into nonsense.
    val exponent = runAttemptCount.coerceIn(0, 30)
    val next = floor.toMillis() shl exponent
    return if (next > ceiling.toMillis()) RetryPlan.Reschedule(ceiling) else RetryPlan.Backoff
}

/**
 * WorkManager's own minimum, named here so [retryPlan] and the request builder cannot disagree.
 * It is also [UNDO_WINDOW], which is not a coincidence to rely on but does mean the retry after
 * a skipped young entry lands about when that entry becomes eligible.
 */
val BACKOFF_FLOOR: Duration = Duration.ofSeconds(10)

/**
 * The ceiling FR-806 was recorded as lacking. Half an hour is chosen against what the wait
 * costs rather than against what the network costs: a capture the user made is sitting on the
 * phone and not in their calendar, and thirty minutes is the longest that is tolerable for
 * something they believe they have saved.
 */
val BACKOFF_CEILING: Duration = Duration.ofMinutes(30)
