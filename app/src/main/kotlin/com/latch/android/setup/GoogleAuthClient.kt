package com.latch.android.setup

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.latch.android.BuildConfig
import com.latch.google.AuthClient
import com.latch.google.GoogleAccount
import com.latch.google.GoogleUnreachable
import com.latch.google.SignInCancelledException
import com.latch.google.SignInRequiredException
import com.latch.google.TokenProvider
import com.latch.google.fetchPrimaryAccount
import com.latch.google.ALLOWED_HOSTS
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The OAuth grant, and the only place in the app that talks to Play services.
 *
 * It is both the [AuthClient] setup signs in with and the [TokenProvider] the REST clients
 * draw tokens from, because those are the same grant seen from two sides.
 *
 * **No token is persisted.** Play services keeps its own cache, tokens last an hour and are
 * evicted when they expire, and re-authorizing a grant the user has already given returns a
 * fresh one with no UI. NFR-203 asks that tokens live in the platform secure store rather
 * than plain preferences; holding none at all is the stronger form of that, and it is why
 * this class needs no `SecretStore`.
 *
 * The Android OAuth client id appears nowhere here. `AuthorizationRequest` takes scopes only;
 * Play services resolves the client from the package name and signing certificate registered
 * in the Cloud console. A client id is an argument solely to `requestOfflineAccess`, which
 * takes a *Web* client and which this app must never call — there is no backend to exchange
 * the resulting code at (design principle 3).
 */
/**
 * What to do with an authorization result — FR-806a's decision, extracted so it can be tested.
 *
 * The rest of this file is Play services and therefore unreachable from a JVM test, which is
 * exactly how the defect this exists to prevent survived: `authorize()` always resolved, and
 * nothing could call it to find out. See the testing conventions in CLAUDE.md.
 */
internal sealed interface AuthNextStep {
    /** The grant is good and carried a token. */
    data object UseToken : AuthNextStep

    /** Consent is needed and this caller may ask for it — setup, with an Activity attached. */
    data object AskForConsent : AuthNextStep

    /**
     * Consent is needed and this caller may **not** ask. FR-806a: fail retryably so the
     * capture is queued, rather than suspending on a screen nothing can present.
     */
    data object FailNeedsSignIn : AuthNextStep
}

/**
 * FR-806a. [interactive] is true only for the setup flow, which runs behind an Activity that
 * has attached the resolution bridge. A capture is never interactive: it may have no Activity
 * at all by the time the token is wanted, and the sheet it started from may be long gone.
 */
internal fun authNextStep(hasResolution: Boolean, interactive: Boolean): AuthNextStep = when {
    !hasResolution -> AuthNextStep.UseToken
    interactive -> AuthNextStep.AskForConsent
    else -> AuthNextStep.FailNeedsSignIn
}

class GoogleAuthClient(
    context: Context,
    private val bridge: AuthResolutionBridge,
) : AuthClient, TokenProvider {

    private val appContext = context.applicationContext

    /** Built from the application context: holding an Activity here would leak it. */
    private val client get() = Identity.getAuthorizationClient(appContext)

    @Volatile
    private var cachedToken: String? = null

    override suspend fun signIn(): GoogleAccount {
        val token = authorize(interactive = true)
        // AuthorizationClient grants authorization, not identity. The primary calendar's id
        // is the account's email address, which is the identity this app can read without a
        // second sign-in library and within the FR-002 scopes it already holds.
        return fetchPrimaryAccount(token)
    }

    /**
     * NFR-205, the half this class owns.
     *
     * **Play services has no revoke to call.** `AuthorizationClient` grants authorization and
     * offers nothing to take it back; `GoogleSignIn.revokeAccess` belonged to the sign-in API
     * this app deliberately does not use. So the revoke is the OAuth2 endpoint, which
     * `revokeGrant` in `:data` issues through the same `ALLOWED_HOSTS` guard as every other
     * request — `oauth2.googleapis.com` has been on that list since the guard was written,
     * against this call.
     *
     * What happens here is the local half of it: the cached token is dropped and Play services
     * is told to forget its own. Without that a token revoked at Google would still be handed
     * back from a cache for as long as it had left to live, and the next request would fail in
     * a way that looks like a network fault rather than like a revoked grant.
     *
     * The account-wide deletion is `LatchApplication.revokeAndDeleteEverything`, because it
     * reaches every store in the app and this class knows about none of them.
     */
    override suspend fun signOut(accountId: String) {
        cachedToken?.let { invalidate(it) }
    }

    // FR-806a: the write path never shows UI. A capture that needs consent is queued.
    override suspend fun accessToken(): String = cachedToken ?: authorize(interactive = false)

    override suspend fun invalidate(token: String) {
        if (cachedToken == token) cachedToken = null
        // Belt and braces. A fresh authorize would return a new token anyway; this stops
        // Play services handing the dead one back from its cache first.
        runCatching {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        }
    }

    private suspend fun authorize(interactive: Boolean): String {
        val request = AuthorizationRequest.builder().setRequestedScopes(SCOPES).build()

        var result = try {
            client.authorize(request).await()
        } catch (failure: ApiException) {
            throw failure.asReadableFailure()
        }

        when (authNextStep(result.hasResolution(), interactive)) {
            AuthNextStep.UseToken -> Unit

            // FR-806a. Nothing here waits: only the main screen attaches the bridge, so a
            // capture asking for consent would suspend until the user happened to open the
            // app — which is what it did, for thirteen minutes, on 1 Sep 2026.
            AuthNextStep.FailNeedsSignIn ->
                throw SignInRequiredException("Authorization needs consent and nothing can present it")

            AuthNextStep.AskForConsent -> {
                val consent = result.pendingIntent
                    ?: throw GoogleUnreachable("Authorization needs consent but supplied no intent")
                // Null means the user dismissed the consent screen.
                val answer = bridge.resolve(consent) ?: throw SignInCancelledException()
                result = try {
                    client.getAuthorizationResultFromIntent(answer)
                } catch (failure: ApiException) {
                    throw failure.asReadableFailure()
                }
            }
        }

        val token = result.accessToken
            ?: throw GoogleUnreachable("Authorization returned no access token")
        cachedToken = token
        return token
    }

    private fun ApiException.asReadableFailure(): Exception {
        // Status 10 is DEVELOPER_ERROR and carries no message. During bring-up it means one
        // of: the signing certificate does not match the SHA-1 registered against the OAuth
        // client, the client is not of type Android, or the consent screen is unconfigured.
        // It would otherwise reach the user as "Could not sign in to Google" and reach the
        // developer as nothing at all.
        if (BuildConfig.DEBUG) {
            Log.w("GoogleAuthClient", "authorize failed, statusCode=$statusCode", this)
        }
        return GoogleUnreachable("Authorization failed (status $statusCode)", this)
    }

    private companion object {
        /**
         * FR-002, and nothing else. FR-003 forbids widening this without re-verification.
         *
         * **The contacts scope is here for FR-1241 and for nothing else yet** (SRS 1.86). No
         * code in this build calls the People API; the scope is requested so the consent screen
         * Google actually shows for it can be read off a device and recorded, which FR-1241
         * requires *before* any contact-write code ships. §8.6's reason for taking it now rather
         * than later is that verification is performed once, against the final scope set.
         *
         * It is deliberately last in the list. The consent screen renders these in order, and
         * the line under examination is easier to identify at the end of one than in the middle.
         */
        val SCOPES = LATCH_OAUTH_SCOPES.map(::Scope)
    }
}

/**
 * `Task` to `suspend`, by hand.
 *
 * `kotlinx-coroutines-play-services` exists and does exactly this, and is deliberately not
 * taken: ten lines is not worth a fourth runtime dependency under NFR-501. Listeners fire on
 * the main looper, which is fine to await from any dispatcher.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

/**
 * FR-002's scope set, as strings a JVM test can read (SRS 1.86).
 *
 * **It is separated from the `Scope` objects deliberately.** FR-002 is a `[MUST]` that names an
 * exact list and FR-003 forbids widening it without re-verification, yet until now nothing
 * asserted what the application actually requests — the list lived inside a private companion of
 * a class that touches Play services, so no test could reach it. That is the same reachability
 * argument this project has already applied to the drain's FR-803 check and to `ALLOWED_HOSTS`,
 * and the same remedy: a fifth scope cannot now arrive without a diff that says so.
 */
internal val LATCH_OAUTH_SCOPES = listOf(
    "https://www.googleapis.com/auth/calendar.events",
    "https://www.googleapis.com/auth/calendar.calendarlist",
    "https://www.googleapis.com/auth/calendar.calendars",
    "https://www.googleapis.com/auth/tasks",
    "https://www.googleapis.com/auth/contacts",
)
