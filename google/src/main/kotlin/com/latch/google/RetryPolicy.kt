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

/**
 * What became of a chain, when a write part-way through it did not succeed (SRS 1.191).
 *
 * A chain of N items is written one item at a time, and there is no transaction over the
 * three-or-four requests that puts them in the account. So "the save" has four outcomes and
 * not two, and until SRS 1.191 both clients collapsed the middle one into an end — the desktop
 * into [WRITTEN] and Android into [FAILED], in opposite directions and each wrong in its own
 * way.
 */
enum class ChainOutcome {
    /** Every item is in the account. */
    WRITTEN,

    /**
     * Some items are in the account and the rest never will be without being captured again.
     *
     * The one this enum exists for. It is neither a success nor a failure and must not be
     * reported as either: the items that landed are real and undoable, and the ones that did
     * not are lost unless the user is told the number.
     */
    PARTLY_WRITTEN,

    /**
     * The rest are held for FR-806's queue — including where some of the chain was already
     * written, which is the case the marker on the queue entry exists for (SRS 1.24).
     */
    QUEUED,

    /** Nothing reached the account, and waiting will not change that. */
    FAILED,
}

/**
 * The rule, as a pure function, so the two clients cannot read one interrupted chain two ways.
 *
 * It lives beside [isWorthRetrying] rather than in either client for the reason `writeDecision`
 * does: it is a decision about a write to Google that §4.1's clients both take, and a client
 * that took it differently would tell one user their capture was saved and another that it
 * failed, about the identical thing.
 *
 * [written] is how many of [total] reached the account before the failure — **not** how many
 * requests were attempted. [retryable] is [isWorthRetrying] of the failure that stopped it.
 */
fun chainOutcome(written: Int, total: Int, retryable: Boolean): ChainOutcome = when {
    written >= total -> ChainOutcome.WRITTEN
    // The queue takes the remainder whether or not any of the chain landed. SRS 1.24's
    // per-item marker is what makes the second case safe: without it the drain asks FR-803
    // about the message, finds the item this chain itself wrote, and retires the entry with
    // the rest never written.
    retryable -> ChainOutcome.QUEUED
    written > 0 -> ChainOutcome.PARTLY_WRITTEN
    else -> ChainOutcome.FAILED
}
