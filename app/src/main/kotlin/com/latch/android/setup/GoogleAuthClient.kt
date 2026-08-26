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
import com.latch.data.AuthClient
import com.latch.data.GoogleAccount
import com.latch.data.GoogleUnreachable
import com.latch.data.SignInCancelledException
import com.latch.data.TokenProvider
import com.latch.data.fetchPrimaryAccount
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
        val token = authorize()
        // AuthorizationClient grants authorization, not identity. The primary calendar's id
        // is the account's email address, which is the identity this app can read without a
        // second sign-in library and within the FR-002 scopes it already holds.
        return fetchPrimaryAccount(token)
    }

    /** NFR-205. Revocation is not built; the requirement owns a Settings action that does not exist yet. */
    override suspend fun signOut(accountId: String) = Unit

    override suspend fun accessToken(): String = cachedToken ?: authorize()

    override suspend fun invalidate(token: String) {
        if (cachedToken == token) cachedToken = null
        // Belt and braces. A fresh authorize would return a new token anyway; this stops
        // Play services handing the dead one back from its cache first.
        runCatching {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        }
    }

    private suspend fun authorize(): String {
        val request = AuthorizationRequest.builder().setRequestedScopes(SCOPES).build()

        var result = try {
            client.authorize(request).await()
        } catch (failure: ApiException) {
            throw failure.asReadableFailure()
        }

        if (result.hasResolution()) {
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
        /** FR-002, and nothing else. FR-003 forbids widening this without re-verification. */
        val SCOPES = listOf(
            Scope("https://www.googleapis.com/auth/calendar.events"),
            Scope("https://www.googleapis.com/auth/calendar.calendarlist"),
            Scope("https://www.googleapis.com/auth/calendar.calendars"),
            Scope("https://www.googleapis.com/auth/tasks"),
        )
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
