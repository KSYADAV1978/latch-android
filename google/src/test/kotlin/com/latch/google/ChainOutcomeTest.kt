package com.latch.google

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SRS 1.191: what a chain interrupted part-way through its inserts is.
 *
 * One test per row, the way `WriteDecisionTest` covers §7.2's table, because the value of this
 * function is that both clients read one interrupted chain the same way — and the two of them
 * had been reading it in *opposite* directions before it existed.
 */
class ChainOutcomeTest {

    @Test
    fun `a whole chain is written`() {
        assertEquals(ChainOutcome.WRITTEN, chainOutcome(written = 3, total = 3, retryable = false))
        assertEquals(ChainOutcome.WRITTEN, chainOutcome(written = 1, total = 1, retryable = false))
    }

    @Test
    fun `nothing written and nothing to wait for is a failure`() {
        assertEquals(ChainOutcome.FAILED, chainOutcome(written = 0, total = 3, retryable = false))
    }

    @Test
    fun `nothing written and worth retrying is queued`() {
        // FR-806's ordinary offline save, which is every case this rule had before it existed.
        assertEquals(ChainOutcome.QUEUED, chainOutcome(written = 0, total = 3, retryable = true))
    }

    @Test
    fun `part written and worth retrying is queued, not partly written`() {
        // The remainder is held rather than reported as lost: waiting can still fix it. What
        // makes this safe is SRS 1.24's per-item marker on the queue entry — without it the
        // drain asks FR-803 about the message and finds the item this chain itself wrote.
        assertEquals(ChainOutcome.QUEUED, chainOutcome(written = 1, total = 3, retryable = true))
    }

    @Test
    fun `part written and nothing to wait for is partly written`() {
        // The row the enum exists for, and the one both clients used to collapse into an end:
        // the desktop reported it as a success and Android as a failure.
        assertEquals(
            ChainOutcome.PARTLY_WRITTEN,
            chainOutcome(written = 2, total = 3, retryable = false),
        )
    }

    @Test
    fun `a single item never reaches the partial row`() {
        // There is no middle for a chain of one, and a rule that produced one would be saying
        // something impossible about a single write.
        assertEquals(ChainOutcome.FAILED, chainOutcome(written = 0, total = 1, retryable = false))
        assertEquals(ChainOutcome.QUEUED, chainOutcome(written = 0, total = 1, retryable = true))
    }
}
