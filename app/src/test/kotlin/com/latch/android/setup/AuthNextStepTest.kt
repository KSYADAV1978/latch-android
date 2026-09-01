package com.latch.android.setup

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FR-806a: a capture shall never block on an interactive authorization.
 *
 * This is the whole of the decision that was missing on 1 Sep 2026, when a save tapped with no
 * network suspended for thirteen minutes — Play services could not refresh an expired token
 * silently, returned a result requiring consent, and the client waited for an Activity to
 * present it. Only the main screen attaches that bridge, and a capture is not the main screen,
 * so the wait had no end: no error, no queue entry, nothing on screen.
 *
 * It is a pure function for the reason CLAUDE.md's testing conventions give. The rest of
 * `GoogleAuthClient` is Play services and unreachable from a JVM test, which is exactly how a
 * path that always resolved survived to a device — there was nothing a test could call.
 */
class AuthNextStepTest {

    @Test
    fun `a capture that needs consent fails rather than waiting for a screen`() {
        // The device case. Retryable by classification, so FR-806 queues the capture and the
        // user gets it back; what must never happen is the suspension.
        assertEquals(
            AuthNextStep.FailNeedsSignIn,
            authNextStep(hasResolution = true, interactive = false),
        )
    }

    @Test
    fun `setup may still ask for consent`() {
        // The interactive path is unchanged: FR-101's sign-in has an Activity behind it, and
        // the bridge exists so that screen survives the rotation that destroys the Activity
        // which launched it. Narrowing the capture path must not narrow this one.
        assertEquals(
            AuthNextStep.AskForConsent,
            authNextStep(hasResolution = true, interactive = true),
        )
    }

    @Test
    fun `a grant that needs nothing uses its token, interactive or not`() {
        // The overwhelmingly common case, and the reason this defect lay unseen: a warm token
        // never reaches the branch at all. AC-10 passed on 28 Aug inside a session whose token
        // was still cached.
        assertEquals(AuthNextStep.UseToken, authNextStep(hasResolution = false, interactive = false))
        assertEquals(AuthNextStep.UseToken, authNextStep(hasResolution = false, interactive = true))
    }
}
