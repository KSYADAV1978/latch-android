package com.latch.google

import java.time.Duration

/**
 * FR-806's retry policy, shared by both clients of §4.1.
 *
 * **The numbers move here rather than being written twice.** Each of them was chosen against a
 * reason rather than picked, and a second client that quietly used a different ceiling would
 * give one set of users a queue that catches up in half an hour and another a queue that waits
 * until lunchtime — with nothing to see and no way to tell the two apart from the outside.
 *
 * What deliberately does **not** move is the *shape* of the answer. Android hands its schedule
 * to WorkManager and can only choose between accepting its exponential backoff and replacing
 * the request; the desktop owns its own timer and computes an instant. Same policy, different
 * mechanism, so the mechanism stays with each client and only [backoffDelay] is shared.
 */
object RetryPolicy {

    /**
     * The shortest wait after a failure.
     *
     * WorkManager's own minimum, which is what fixed it on Android; ten seconds is also long
     * enough on any platform that a burst of failures cannot become a burst of requests.
     */
    val FLOOR: Duration = Duration.ofSeconds(10)

    /**
     * The longest wait, and the one FR-806 was recorded as lacking.
     *
     * Half an hour, chosen against what the wait costs rather than against what the network
     * costs: a capture the user made is sitting on their machine and not in their calendar,
     * and thirty minutes is the longest that is tolerable for something they believe they have
     * saved. Left to double freely, exponential backoff reaches five hours, so a queue that
     * failed overnight would still be waiting at breakfast.
     */
    val CEILING: Duration = Duration.ofMinutes(30)

    /**
     * How often something other than a tap may provoke an immediate drain.
     *
     * Connectivity comes and goes in bursts on a flaky network — a train, a lift — and a burst
     * of drains is the hammering the backoff exists to prevent, arrived at from the other
     * direction.
     */
    val IMMEDIATE_DRAIN_RATE_LIMIT: Duration = Duration.ofMinutes(1)

    /**
     * How long to wait after [attempts] failures.
     *
     * `FLOOR * 2^attempts`, capped at [CEILING]. At ten seconds and thirty minutes the cap
     * bites on the eighth attempt: 10 s, 20, 40, 80, 160, 320, 640, 1280, and then 1800 for
     * ever rather than 2560, 5120 and on.
     */
    fun backoffDelay(attempts: Int, floor: Duration = FLOOR, ceiling: Duration = CEILING): Duration {
        // The count comes from a stored record that may have been failing for weeks, and a
        // shift wide enough to overflow would turn a long wait into an immediate retry — the
        // opposite of what a long-failing entry deserves.
        val exponent = attempts.coerceIn(0, 30)
        val millis = floor.toMillis() shl exponent
        return if (millis > ceiling.toMillis() || millis < 0) ceiling else Duration.ofMillis(millis)
    }

    /**
     * After how many failures an entry is given up on and shown as stuck.
     *
     * It is **not** deleted, and that is design principle 1 rather than politeness: the entry
     * holds a capture that exists nowhere else, so giving up means stopping the retries and
     * saying so, never discarding it. Both clients offer a manual retry that revives one.
     */
    const val GIVE_UP_AFTER: Int = 12
}
