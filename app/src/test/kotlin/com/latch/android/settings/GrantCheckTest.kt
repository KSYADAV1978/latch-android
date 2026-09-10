package com.latch.android.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-806b and FR-1007's decisions.
 *
 * Everything Play services touches is unreachable from here — which is precisely how the defect
 * these requirements answer survived: `reauthorize()` existed and worked, and nothing could tell
 * you it was reachable from exactly one place. So what is decided is decided in `GrantCheck.kt`
 * and this file is what holds it.
 */
class GrantCheckTest {

    // ---- reading a completed check --------------------------------------------------------

    @Test
    fun `a check that reports a resolution needs consent`() {
        assertEquals(GrantCheck.NeedsConsent, grantCheck(Result.success(true), online = true))
    }

    @Test
    fun `a check that reports no resolution is sufficient`() {
        assertEquals(GrantCheck.Sufficient, grantCheck(Result.success(false), online = true))
    }

    @Test
    fun `a check that could not be completed concludes nothing`() {
        // The case the requirement calls out by name: no network, a server error, Play services
        // unavailable. None of it is evidence about the grant.
        assertEquals(GrantCheck.Unknown, grantCheck(Result.failure(RuntimeException("no network")), online = true))
    }

    // ---- what a check may change ----------------------------------------------------------

    @Test
    fun `an unknown check leaves the prompt exactly as it was`() {
        // The load-bearing assertion in this file. Null means "do not touch", and it is a
        // different instruction from false: a network blip must not clear a prompt the user
        // still needs, and must not raise one they do not.
        assertNull(promptAfter(GrantCheck.Unknown))
    }

    @Test
    fun `a sufficient check clears a standing prompt`() {
        // After the user re-consents, the banner has to go — and it has to go without waiting
        // for a capture to succeed.
        assertEquals(false, promptAfter(GrantCheck.Sufficient))
    }

    @Test
    fun `a needs-consent check raises the prompt`() {
        assertEquals(true, promptAfter(GrantCheck.NeedsConsent))
    }

    @Test
    fun `aeroplane mode cannot produce a sign-in prompt`() {
        // Stated as its own case because it is the failure this requirement was written to
        // avoid, and it would look exactly like working software: a banner that is wrong often
        // enough to be ignored is worse than no banner at all.
        val standing = false
        val after = promptAfter(grantCheck(Result.failure(java.io.IOException("offline")), online = true))
        assertNull(after)
        assertFalse(after ?: standing)
    }

    // ---- the rate limit -------------------------------------------------------------------

    @Test
    fun `the first check in a process always runs`() {
        assertTrue(shouldCheckGrant(lastCheckedAtMillis = null, nowMillis = 0L))
    }

    @Test
    fun `a second check inside the interval does not run`() {
        // onResume fires on every task switch, dismissed dialog and rotation. Unchecked, that is
        // a Play services call per gesture for information that changes about once a year.
        assertFalse(shouldCheckGrant(lastCheckedAtMillis = 1_000L, nowMillis = 1_000L + 60_000L))
    }

    @Test
    fun `a check after the interval runs`() {
        assertTrue(
            shouldCheckGrant(
                lastCheckedAtMillis = 1_000L,
                nowMillis = 1_000L + GRANT_CHECK_INTERVAL_MILLIS,
            )
        )
    }

    @Test
    fun `a clock that has gone backwards does not lock the check out`() {
        // A manual time change or a tzdata update, and otherwise the check is disabled until the
        // clock catches up — which for a backwards jump of a day means a day.
        assertTrue(shouldCheckGrant(lastCheckedAtMillis = 10_000L, nowMillis = 5_000L))
    }

    // ---- which sentence the home screen shows ---------------------------------------------

    @Test
    fun `held captures outrank the general case`() {
        assertEquals(
            SignInPrompt.QUEUE_HELD,
            signInPrompt(queueNeedsSignIn = true, queueWaiting = 2, grantNeedsConsent = true),
        )
    }

    @Test
    fun `an insufficient grant with an empty queue says so without mentioning captures`() {
        // FR-806b's own case, and the reason there are two sentences rather than one: the
        // existing string reads "to save these", which refers to captures that do not exist.
        assertEquals(
            SignInPrompt.GRANT_ONLY,
            signInPrompt(queueNeedsSignIn = false, queueWaiting = 0, grantNeedsConsent = true),
        )
    }

    @Test
    fun `a sign-in flag on an emptied queue is stale rather than a prompt`() {
        // The entries drained after a re-consent and the status has not been re-read. Showing
        // "captures are held" over an empty queue would be a lie in the reassuring direction.
        assertEquals(
            SignInPrompt.NONE,
            signInPrompt(queueNeedsSignIn = true, queueWaiting = 0, grantNeedsConsent = false),
        )
    }

    @Test
    fun `an ordinary launch says nothing at all`() {
        assertEquals(
            SignInPrompt.NONE,
            signInPrompt(queueNeedsSignIn = false, queueWaiting = 0, grantNeedsConsent = false),
        )
        assertEquals(
            SignInPrompt.NONE,
            signInPrompt(queueNeedsSignIn = false, queueWaiting = 3, grantNeedsConsent = false),
        )
    }

    // ---- SRS 1.198/1.199: a check made with no network has not been made -------------------

    @Test
    fun `offline, a reported resolution concludes nothing rather than needing consent`() {
        // **The defect, and the case this file did not have.** Offline `authorize()` does not
        // fail — it *succeeds* and offers a resolution, because it cannot confirm a grant
        // without reaching Google. So the failure branch below was never the one that ran, and
        // a user in aeroplane mode was told their grant was bad and shown a button that could
        // not work.
        assertEquals(GrantCheck.Unknown, grantCheck(Result.success(true), online = false))
    }

    @Test
    fun `offline, a reported absence of a resolution also concludes nothing`() {
        // The guard sits in front of **both** answers, and this is the more damaging direction:
        // `false` would mean Sufficient, which *clears a standing prompt* — a real warning lost
        // on no evidence, where the other mistake is only a false one raised.
        assertEquals(GrantCheck.Unknown, grantCheck(Result.success(false), online = false))
        assertNull(
            promptAfter(grantCheck(Result.success(false), online = false)),
            "an offline check must leave the prompt exactly as it is",
        )
    }

    @Test
    fun `offline never suppresses a prompt that is already standing`() {
        // FR-806b's whole purpose is that the banner precedes the loss. A flaky network must not
        // be able to take it down.
        assertNull(promptAfter(grantCheck(Result.success(true), online = false)))
        assertNull(promptAfter(grantCheck(Result.failure(java.io.IOException()), online = false)))
    }

    @Test
    fun `online, the three readings are unchanged`() {
        // The regression guard: the fix must not have moved the cases that were already right.
        assertEquals(GrantCheck.NeedsConsent, grantCheck(Result.success(true), online = true))
        assertEquals(GrantCheck.Sufficient, grantCheck(Result.success(false), online = true))
        assertEquals(GrantCheck.Unknown, grantCheck(Result.failure(RuntimeException()), online = true))
    }
}
