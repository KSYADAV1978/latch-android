package com.latch.android.settings

/**
 * FR-806b: what a silent authorization check on foreground is allowed to conclude.
 *
 * **The decision is pure and lives here; the Play services call is the caller's.** That is the
 * same split [DestinationCheck] takes, for the same reason — the cases that matter are hard to
 * arrange on a device and trivial to arrange here.
 *
 * **FR-806a is not weakened by this and the type is what keeps it that way.** Nothing in this
 * file can present anything: it turns the *outcome* of a check that showed no UI into one of
 * three conclusions. The interactive grant stays behind a tap, in FR-1007's Settings action and
 * in the home screen's button.
 */
sealed interface GrantCheck {

    /** The grant covers what the app asks for. Any standing prompt is cleared. */
    data object Sufficient : GrantCheck

    /**
     * Google wants consent again — typically because the requested scope set has grown since the
     * grant was given, which is exactly what §5.11's A2 does to every existing user.
     *
     * This raises the prompt. It does **not** ask for anything.
     */
    data object NeedsConsent : GrantCheck

    /**
     * The check could not be completed: no network, a server error, Play services unavailable.
     *
     * **Nothing changes, and that is the load-bearing case.** A failure here says nothing about
     * the grant, and turning it into "sign in again" would put that sentence in front of a user
     * whose only problem is aeroplane mode — sending them to re-consent a grant that is perfectly
     * good, and teaching them that the banner means nothing. It does not clear a standing prompt
     * either: a grant that was insufficient a minute ago is still insufficient now, and a flaky
     * network is not evidence to the contrary.
     *
     * This is [DestinationCheck.Unreadable]'s reasoning one requirement over.
     */
    data object Unknown : GrantCheck
}

/**
 * Read a completed silent check.
 *
 * [outcome] carries what `AuthorizationResult` reports: consent is required and here is the
 * intent that would ask for it. The intent is deliberately discarded by the caller — FR-806a
 * says nothing interactive happens without a tap, and this check has had no tap.
 *
 * **[online] is the fix for SRS 1.198 and it guards *both* answers, not just the positive one.**
 * This function used to take the result alone, so [GrantCheck.Unknown] was reachable only when
 * the Play services call *failed*. Offline it does not fail: `authorize()` **succeeds** and
 * offers a resolution, because it cannot confirm a grant without reaching Google. So
 * `hasResolution` came back true, [GrantCheck.NeedsConsent] followed, and a user in aeroplane
 * mode was told their grant was bad and shown a button that could not work.
 *
 * A check made with no network has **not been made**, whichever way it came back. The guard is
 * in front of the fold rather than inside the `hasResolution` branch because the other direction
 * is the more damaging one: a `false` reached without a network would mean [GrantCheck.Sufficient]
 * and would **clear a standing prompt** on no evidence, which is a real warning lost rather than
 * a false one raised.
 *
 * [online] is a weak proxy — a captive portal looks connected and is not, so one can still
 * produce the false banner. What it cannot do is the damaging direction: it never suppresses a
 * real [GrantCheck.NeedsConsent], because a phone that is genuinely online says so.
 */
fun grantCheck(outcome: Result<Boolean>, online: Boolean): GrantCheck {
    if (!online) return GrantCheck.Unknown
    return outcome.fold(
        onSuccess = { hasResolution ->
            if (hasResolution) GrantCheck.NeedsConsent else GrantCheck.Sufficient
        },
        onFailure = { GrantCheck.Unknown },
    )
}

/**
 * Whether a [GrantCheck] changes the standing prompt, and to what.
 *
 * Returns null where the prompt is to be left exactly as it is, which is the whole of
 * [GrantCheck.Unknown]'s behaviour and the reason this is a separate function rather than a
 * boolean: "set false" and "do not touch" are different instructions, and collapsing them is how
 * a network blip would silently clear a prompt the user still needs.
 */
fun promptAfter(check: GrantCheck): Boolean? = when (check) {
    GrantCheck.NeedsConsent -> true
    GrantCheck.Sufficient -> false
    GrantCheck.Unknown -> null
}

/**
 * FR-806b's rate limit.
 *
 * The check is one Play services call, and `onResume` fires on every return to the app — every
 * task switch, every dismissed dialog, every rotation. Unlimited, that is a call per gesture for
 * information that changes about once a year.
 *
 * A pure function for the same reason `shouldDrainNow` is: the alternative is a timestamp
 * comparison buried in a lifecycle callback, where the off-by-one nobody notices is that it
 * never runs at all.
 *
 * [lastCheckedAtMillis] is null when no check has been made in this process, which always runs.
 */
fun shouldCheckGrant(
    lastCheckedAtMillis: Long?,
    nowMillis: Long,
    minimumIntervalMillis: Long = GRANT_CHECK_INTERVAL_MILLIS,
): Boolean {
    if (lastCheckedAtMillis == null) return true
    // A clock that has gone backwards — a manual change, or a time-zone database update — must
    // not lock the check out until it catches up.
    if (nowMillis < lastCheckedAtMillis) return true
    return nowMillis - lastCheckedAtMillis >= minimumIntervalMillis
}

/** Fifteen minutes: often enough to catch a revocation in the session it happens in. */
const val GRANT_CHECK_INTERVAL_MILLIS: Long = 15 * 60 * 1000L

/**
 * What the home screen says about signing in, if anything.
 *
 * **Two different facts reach one banner and they are not the same sentence.** FR-806a's is
 * "captures are held and this is why"; FR-806b's is "this will happen to your next capture".
 * Saying the first when nothing is queued would refer to captures that do not exist; saying the
 * second when captures *are* held would understate it.
 */
enum class SignInPrompt {
    /** Nothing to say. */
    NONE,

    /** FR-806a: entries are waiting because Google wants consent. */
    QUEUE_HELD,

    /** FR-806b: the grant is insufficient and nothing has been lost yet. */
    GRANT_ONLY,
}

fun signInPrompt(
    queueNeedsSignIn: Boolean,
    queueWaiting: Int,
    grantNeedsConsent: Boolean,
): SignInPrompt = when {
    // Held captures outrank the general case: something has already not happened.
    queueNeedsSignIn && queueWaiting > 0 -> SignInPrompt.QUEUE_HELD
    grantNeedsConsent -> SignInPrompt.GRANT_ONLY
    // A queue flagged for sign-in with nothing left in it is a stale flag, not a prompt. It
    // happens when the entries drained after a re-consent and the status has not been re-read.
    else -> SignInPrompt.NONE
}
