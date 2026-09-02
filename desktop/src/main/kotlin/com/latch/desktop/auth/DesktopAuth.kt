package com.latch.desktop.auth

import com.latch.desktop.store.SecretFile
import com.latch.google.json.JSONObject
import com.latch.google.requireGoogleEndpoint
import java.io.File
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/** Where the OAuth client's identity comes from. */
data class ClientConfig(val clientId: String, val clientSecret: String?) {
    companion object {
        const val ENVIRONMENT_ID: String = "LATCH_GOOGLE_CLIENT_ID"
        const val ENVIRONMENT_SECRET: String = "LATCH_GOOGLE_CLIENT_SECRET"

        /**
         * FR-001's Desktop client, from the environment or from a properties file beside the
         * application.
         *
         * **Not committed, and absent is not a build failure**, which is the same shape
         * `keystore.properties` already has for FR-1108: NFR-503 requires a clean checkout to
         * build, and a machine that has never had a client id must still be able to run the
         * tests. What it cannot do is sign in, and it says so by name rather than by failing
         * somewhere inside the flow.
         */
        fun load(directory: File = com.latch.desktop.store.latchDataDirectory()): ClientConfig? {
            System.getenv(ENVIRONMENT_ID)?.takeIf { it.isNotBlank() }?.let {
                return ClientConfig(it.trim(), System.getenv(ENVIRONMENT_SECRET)?.trim())
            }
            val file = File(directory, "client.properties")
            if (!file.isFile) return null
            val properties = java.util.Properties().apply {
                file.inputStream().use { load(it) }
            }
            val id = properties.getProperty("client.id")?.trim()
            if (id.isNullOrBlank()) return null
            return ClientConfig(id, properties.getProperty("client.secret")?.trim())
        }
    }
}

/** What a sign-in produced. */
sealed interface SignInOutcome {
    data object Succeeded : SignInOutcome

    /** Named, never phrased — the UI decides the words (NFR-402). */
    data class Failed(val reason: SignInFailure, val detail: String = "") : SignInOutcome
}

enum class SignInFailure {
    /** FR-001's Desktop OAuth client has not been configured on this machine. */
    NOT_CONFIGURED,
    CANCELLED,
    NO_REFRESH_TOKEN,
    NETWORK,
    REFUSED_BY_GOOGLE,
    BROWSER,
}

/** The token response, once it has been read. */
data class TokenSet(val accessToken: String, val refreshToken: String?, val expiresAt: Instant)

/**
 * Reads Google's token response.
 *
 * Pure and separate from the request, so the shapes that matter — a refresh token present, a
 * refresh token absent, an error body — are all reachable from a JVM test. The absent case is
 * the one worth having a test for: Google returns a refresh token only when it feels it has
 * not already given you one, and an application that assumed otherwise would store null over
 * a good token and log the user out on the next launch.
 */
fun readTokenResponse(body: String, now: Instant, previousRefresh: String? = null): TokenSet? {
    val json = runCatching { JSONObject(body) }.getOrElse { return null }
    val access = json.optString("access_token")
    if (access.isBlank()) return null
    val expiresIn = json.optInt("expires_in", 3600).toLong()
    return TokenSet(
        accessToken = access,
        // A response with no refresh token leaves the stored one alone rather than clearing it.
        refreshToken = json.optString("refresh_token").ifBlank { null } ?: previousRefresh,
        expiresAt = now.plusSeconds(expiresIn),
    )
}

/** Why Google refused, as far as it says. */
fun readTokenError(body: String): String =
    runCatching { JSONObject(body).optString("error_description").ifBlank { JSONObject(body).optString("error") } }
        .getOrDefault("")

/**
 * FR-002's grant on Windows, and the one place a token lives.
 *
 * **The access token is held in memory and never written down**, exactly as on Android. The
 * refresh token is written, because it has to be: there is no Play services here to hold a
 * grant, so the alternative is a sign-in on every launch. It goes into [SecretFile] under
 * DPAPI, which is NFR-203's requirement met by the platform's own key store.
 */
class DesktopAuth(
    private val secrets: SecretFile,
    private val config: ClientConfig?,
    private val post: (String, String) -> HttpReply = ::formPost,
    private val clock: () -> Instant = Instant::now,
) {
    private var cached: TokenSet? = null

    val isConfigured: Boolean get() = config != null

    val isSignedIn: Boolean get() = secrets.get(REFRESH_TOKEN_KEY) != null

    /**
     * Runs the whole flow. [openBrowser] is passed in rather than called directly so that the
     * decision to launch a browser — the one part of this that touches the desktop — stays out
     * of the flow's own logic and a test can drive the rest of it.
     */
    fun signIn(openBrowser: (String) -> Boolean): SignInOutcome {
        val client = config ?: return SignInOutcome.Failed(SignInFailure.NOT_CONFIGURED)
        val pkce = newPkce()
        val state = newState()

        LoopbackReceiver().use { receiver ->
            receiver.start(state)
            val url = OAuthRequest.authorizationUrl(client.clientId, receiver.redirectUri, pkce, state)
            if (!openBrowser(url)) return SignInOutcome.Failed(SignInFailure.BROWSER)

            return when (val callback = receiver.await()) {
                is CallbackResult.Refused -> SignInOutcome.Failed(
                    if (callback.reason == CallbackFailure.DENIED) SignInFailure.CANCELLED
                    else SignInFailure.REFUSED_BY_GOOGLE,
                    callback.reason.name,
                )
                is CallbackResult.Code -> exchange(client, callback.code, receiver.redirectUri, pkce.verifier)
            }
        }
    }

    private fun exchange(client: ClientConfig, code: String, redirectUri: String, verifier: String): SignInOutcome {
        val body = OAuthRequest.tokenExchangeBody(client.clientId, client.clientSecret, code, redirectUri, verifier)
        val reply = runCatching { post(OAuthRequest.TOKEN_ENDPOINT, body) }
            .getOrElse { return SignInOutcome.Failed(SignInFailure.NETWORK, it.message.orEmpty()) }

        if (reply.status !in 200..299) {
            return SignInOutcome.Failed(SignInFailure.REFUSED_BY_GOOGLE, readTokenError(reply.body))
        }
        val tokens = readTokenResponse(reply.body, clock())
            ?: return SignInOutcome.Failed(SignInFailure.REFUSED_BY_GOOGLE, "no access token in the response")

        val refresh = tokens.refreshToken
            // Without one, this session works for an hour and then silently stops. Better to
            // say so now than to look signed in and stop working at lunchtime.
            ?: return SignInOutcome.Failed(SignInFailure.NO_REFRESH_TOKEN)

        secrets.put(REFRESH_TOKEN_KEY, refresh)
        cached = tokens
        return SignInOutcome.Succeeded
    }

    /**
     * A usable access token, refreshing where the cached one is spent.
     *
     * The margin is a minute: a token that expires while a request is in flight fails the
     * request, and a minute is longer than any call this application makes.
     */
    fun accessToken(): String? {
        val client = config ?: return null
        cached?.let { if (it.expiresAt.isAfter(clock().plus(REFRESH_MARGIN))) return it.accessToken }

        val refresh = secrets.get(REFRESH_TOKEN_KEY) ?: return null
        val reply = runCatching { post(OAuthRequest.TOKEN_ENDPOINT, OAuthRequest.refreshBody(client.clientId, client.clientSecret, refresh)) }
            .getOrNull() ?: return null

        if (reply.status == 400 || reply.status == 401) {
            // Google has retired the grant — revoked in the account, or expired after long
            // disuse. The stored token is now worthless and keeping it would mean retrying it
            // for ever; FR-806a's "Sign in needed" is the state this produces.
            secrets.remove(REFRESH_TOKEN_KEY)
            cached = null
            return null
        }
        if (reply.status !in 200..299) return null

        val tokens = readTokenResponse(reply.body, clock(), previousRefresh = refresh) ?: return null
        tokens.refreshToken?.takeIf { it != refresh }?.let { secrets.put(REFRESH_TOKEN_KEY, it) }
        cached = tokens
        return tokens.accessToken
    }

    /** NFR-205's local half. The revoke itself is `:google`'s, and runs first. */
    fun forget() {
        cached = null
        secrets.remove(REFRESH_TOKEN_KEY)
    }

    companion object {
        const val REFRESH_TOKEN_KEY: String = "google.refresh_token"
        private val REFRESH_MARGIN: Duration = Duration.ofMinutes(1)
    }
}

data class HttpReply(val status: Int, val body: String)

/**
 * A form POST, through AC-17's guard.
 *
 * `requireGoogleEndpoint` is `:google`'s and is used here rather than reimplemented, because
 * NFR-201 is "every outbound request goes through one check" and a sign-in is a request like
 * any other. `oauth2.googleapis.com` has been on that list since the guard was written.
 */
internal fun formPost(url: String, body: String): HttpReply {
    val endpoint = requireGoogleEndpoint(url)
    val connection = (endpoint.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
    }
    connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
    val status = connection.responseCode
    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
    val text = stream?.use { String(it.readBytes(), StandardCharsets.UTF_8) }.orEmpty()
    connection.disconnect()
    return HttpReply(status, text)
}
