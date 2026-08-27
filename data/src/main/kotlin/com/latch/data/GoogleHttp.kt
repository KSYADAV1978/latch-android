package com.latch.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONException
import org.json.JSONObject

/**
 * Supplies the OAuth access token, and is told when one has stopped working.
 *
 * Not a `fun interface`: fetching and invalidating are two obligations, and an implementation
 * that could only do the first would loop forever on a revoked grant.
 *
 * No token is persisted anywhere. Play services holds its own cache, tokens last an hour and
 * are evicted on expiry, and a repeat authorization of a grant the user has already given
 * returns a fresh one without UI. So NFR-203 is satisfied by there being nothing at rest to
 * protect — which is a stronger position than encrypting it would be.
 */
interface TokenProvider {
    suspend fun accessToken(): String

    /** Discard [token] so the next [accessToken] call fetches a new one. */
    suspend fun invalidate(token: String)
}

/** Something went wrong talking to Google. NFR-303: none of these may be swallowed. */
sealed class GoogleFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The request never completed — no network, DNS, TLS, timeout. */
class GoogleUnreachable(message: String, cause: Throwable? = null) : GoogleFailure(message, cause)

/**
 * Google answered with an error. [reason] is the machine-readable string out of the error
 * envelope — `ACCESS_TOKEN_SCOPE_INSUFFICIENT`, `rateLimitExceeded` and so on — which is what
 * separates cases that a retry could fix from cases where it cannot.
 */
class GoogleRejected(
    val status: Int,
    val reason: String?,
    message: String,
) : GoogleFailure(message)

/** A 2xx whose body was not the JSON this code expected. */
class GoogleUnreadable(message: String, cause: Throwable? = null) : GoogleFailure(message, cause)

/**
 * The hosts this application is permitted to contact. **This set is the AC-17 mechanism.**
 *
 * AC-17 is a sign-off test — a network monitor watching a full capture cycle, expecting no
 * request to any non-Google endpoint. Before the app held the INTERNET permission that was
 * guaranteed by the platform. Now it is guaranteed by this, so it is deliberately one small
 * set in one file rather than a base URL per client, and [requireGoogleEndpoint] is unit
 * tested by name.
 *
 * `oauth2.googleapis.com` is here for NFR-205's revoke, which is not built. Listing it now
 * costs nothing and means the guard does not have to be reopened by someone in a hurry.
 */
internal val ALLOWED_HOSTS = setOf(
    "www.googleapis.com",
    "tasks.googleapis.com",
    "oauth2.googleapis.com",
)

/**
 * Throws unless [rawUrl] is one of ours. Checks the parsed host for equality rather than the
 * string for a prefix, because `https://www.googleapis.com.example.invalid/` passes a prefix
 * test on the text and fails it on the host — which is the whole class of mistake this guard
 * exists to catch.
 */
internal fun requireGoogleEndpoint(rawUrl: String): URL {
    val url = try {
        URL(rawUrl)
    } catch (malformed: MalformedURLException) {
        throw GoogleUnreachable("Not a URL: $rawUrl", malformed)
    }
    if (url.protocol != "https") throw GoogleUnreachable("Refusing non-HTTPS request: $rawUrl")
    if (url.host !in ALLOWED_HOSTS) throw GoogleUnreachable("Refusing non-Google host: ${url.host}")
    // Credentials in the authority, or an unexpected port, mean the URL was built by
    // something other than the code below.
    if (url.userInfo != null) throw GoogleUnreachable("Refusing URL carrying credentials")
    if (url.port != -1) throw GoogleUnreachable("Refusing non-default port: ${url.port}")
    return url
}

/**
 * Every outbound request in the app. Hand-written over [HttpURLConnection] — see
 * docs/DEPENDENCIES.md for why there is no HTTP client here.
 *
 * Blocking work runs under `runInterruptible(Dispatchers.IO)`. `withContext` alone would move
 * the work off the caller's thread but leave cancellation a lie: a socket blocked in `read`
 * does not notice a cancelled coroutine. `runInterruptible` turns cancellation into a thread
 * interrupt, which Android's connection does respond to. Timeouts are set regardless, because
 * both default to infinite and a hung setup screen has no retry on it.
 */
internal class GoogleHttp(private val tokens: TokenProvider) {

    suspend fun get(url: String): JSONObject = authorised(url, "GET", body = null)

    suspend fun post(url: String, body: JSONObject): JSONObject = authorised(url, "POST", body)

    /**
     * `HttpURLConnection.setRequestMethod("PATCH")` throws on the JDK implementation. Android's
     * is OkHttp-backed and would accept it, but relying on that makes FR-104's colour and
     * FR-903's visibility depend on which HTTP stack is underneath. Google's JSON APIs honour
     * the override header, so this works on both.
     */
    suspend fun patch(url: String, body: JSONObject): JSONObject =
        authorised(url, "POST", body, methodOverride = "PATCH")

    /**
     * FR-807's undo. Unlike `PATCH`, `DELETE` needs no override header — every
     * [HttpURLConnection] implementation accepts it — and both APIs answer with a 204 and an
     * empty body, which [execute] already reads as a success with nothing in it.
     */
    suspend fun delete(url: String): JSONObject = authorised(url, "DELETE", body = null)

    /**
     * One retry, on 401 only. A 401 means the token died; anything else means retrying with
     * a fresh token changes nothing — 403 `ACCESS_TOKEN_SCOPE_INSUFFICIENT` would loop
     * forever, and 403 `rateLimitExceeded` needs backoff, not immediacy. If a revoked grant
     * returns 401 twice, the second one is reported.
     */
    private suspend fun authorised(
        url: String,
        method: String,
        body: JSONObject?,
        methodOverride: String? = null,
    ): JSONObject {
        val token = tokens.accessToken()
        return try {
            execute(url, method, body, methodOverride, token)
        } catch (rejected: GoogleRejected) {
            if (rejected.status != HttpURLConnection.HTTP_UNAUTHORIZED) throw rejected
            tokens.invalidate(token)
            execute(url, method, body, methodOverride, tokens.accessToken())
        }
    }

    private suspend fun execute(
        url: String,
        method: String,
        body: JSONObject?,
        methodOverride: String?,
        token: String,
    ): JSONObject {
        val endpoint = requireGoogleEndpoint(url)
        val payload = body?.toString()?.toByteArray(Charsets.UTF_8)

        return runInterruptible(Dispatchers.IO) {
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                // A redirect is the one way a request approved by requireGoogleEndpoint could
                // still reach somewhere else, so it is refused rather than followed. These
                // endpoints do not redirect.
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                methodOverride?.let { setRequestProperty("X-HTTP-Method-Override", it) }
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setFixedLengthStreamingMode(payload.size)
                }
            }

            try {
                if (payload != null) connection.outputStream.use { it.write(payload) }

                val status = connection.responseCode
                if (status !in 200..299) {
                    // inputStream throws once the status is an error; the body is on the
                    // error stream, and it is the only place the machine-readable reason is.
                    val errorBody = connection.errorStream?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8).orEmpty()
                    throw rejection(status, errorBody)
                }

                val text = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                // A 204 or an empty body is a success with nothing to read — patch and insert
                // callers that ignore the result must not fail here.
                if (text.isBlank()) JSONObject() else parse(text)
            } catch (failure: GoogleFailure) {
                throw failure
            } catch (io: IOException) {
                throw GoogleUnreachable("Could not reach ${endpoint.host}", io)
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

private fun parse(text: String): JSONObject = try {
    JSONObject(text)
} catch (malformed: JSONException) {
    throw GoogleUnreadable("Response was not a JSON object", malformed)
}

/**
 * Google's error envelope is `{"error":{"code":…,"message":…,"status":…,"details":[…]}}`.
 * Parsed for the reason, but never trusted to be present — an error body can be an HTML
 * proxy page, and losing the status code to a parse failure would be worse than losing the
 * reason.
 */
internal fun rejection(status: Int, body: String): GoogleRejected {
    val error = try {
        JSONObject(body).optJSONObject("error")
    } catch (malformed: JSONException) {
        null
    }
    val reason = error?.optString("status")?.takeIf { it.isNotBlank() }
        ?: error?.optJSONArray("details")?.optJSONObject(0)?.optString("reason")?.takeIf { it.isNotBlank() }
    val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP $status"
    return GoogleRejected(status, reason, message)
}
