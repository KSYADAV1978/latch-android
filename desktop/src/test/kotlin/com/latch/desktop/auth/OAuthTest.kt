package com.latch.desktop.auth

import com.latch.desktop.store.reversingSecrets
import com.latch.desktop.store.BridgeReply
import com.latch.desktop.store.SecretFile
import com.latch.desktop.store.WindowsSecrets
import com.latch.desktop.store.base64
import com.latch.desktop.store.unbase64
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthTest {

    private val directory: File = File.createTempFile("latch-auth", "").let {
        it.delete(); it.mkdirs(); it
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store() = SecretFile(
        File(directory, "secrets.dat"),
        reversingSecrets(),
    )

    private val client = ClientConfig("1234.apps.googleusercontent.com", "not-really-secret")

    // ---- PKCE -------------------------------------------------------------------------------

    @Test
    fun `the verifier is inside RFC 7636's length range and is base64url`() {
        val pkce = newPkce()
        assertTrue(pkce.verifier.length in 43..128, "length was ${pkce.verifier.length}")
        assertTrue(pkce.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' }, pkce.verifier)
        assertFalse('=' in pkce.verifier, "padding would be rejected as an unreserved character")
    }

    @Test
    fun `the challenge is the S256 of the verifier and not the verifier itself`() {
        // The failure this guards: sending `plain`, which puts the verifier into a URL that
        // passes through the browser's history and any corporate proxy on the way.
        val pkce = newPkce()
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray(Charsets.US_ASCII))
        )
        assertEquals(expected, pkce.challenge)
        assertNotEquals(pkce.verifier, pkce.challenge)
    }

    @Test
    fun `two sign-ins do not share a verifier or a state`() {
        assertNotEquals(newPkce().verifier, newPkce().verifier)
        assertNotEquals(newState(), newState())
    }

    // ---- the authorization URL ---------------------------------------------------------------

    @Test
    fun `the authorization url carries exactly FR-002's scopes and nothing more`() {
        val url = OAuthRequest.authorizationUrl(client.clientId, "http://127.0.0.1:5000/callback", newPkce(), "st")
        val scope = URI(url).query.split('&').first { it.startsWith("scope=") }.removePrefix("scope=")
        val decoded = java.net.URLDecoder.decode(scope, Charsets.UTF_8)
        assertEquals(OAuthRequest.SCOPES.toSet(), decoded.split(' ').toSet())
        assertEquals(4, decoded.split(' ').size, "FR-003: no scope beyond the four")
    }

    @Test
    fun `the authorization url asks for a refresh token in the only way that returns one`() {
        // access_type=offline without prompt=consent returns a refresh token on the first
        // grant only. A user who signed in again after a revocation would get an access token
        // good for an hour and nothing to renew it with, which presents as the app working
        // until lunchtime and then not.
        val url = OAuthRequest.authorizationUrl(client.clientId, "http://127.0.0.1:5000/callback", newPkce(), "st")
        assertTrue("access_type=offline" in url, url)
        assertTrue("prompt=consent" in url, url)
        assertTrue("code_challenge_method=S256" in url, url)
        assertTrue("response_type=code" in url, url)
    }

    @Test
    fun `a redirect uri with a port is encoded rather than pasted in`() {
        val url = OAuthRequest.authorizationUrl(client.clientId, "http://127.0.0.1:51234/callback", newPkce(), "st")
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A51234%2Fcallback" in url, url)
    }

    // ---- the callback, which is where the security lives -------------------------------------

    @Test
    fun `a callback whose state does not match is refused before anything else is read`() {
        // The check this test exists for. Without it, anything able to reach the loopback port
        // could hand this process a code from an authorization it did not request — a
        // different account, or an attacker's. The fixture supplies a perfectly good code, so
        // an implementation that read `code` first would return it and this would be red.
        val result = readCallback("code=good-code&state=wrong", "right")
        assertEquals(CallbackResult.Refused(CallbackFailure.STATE_MISMATCH), result)
    }

    @Test
    fun `a callback with no state at all is refused`() {
        assertEquals(
            CallbackResult.Refused(CallbackFailure.STATE_MISMATCH),
            readCallback("code=good-code", "right"),
        )
    }

    @Test
    fun `a smuggled second state does not override the first`() {
        // Taking the last value of a repeated parameter is how a checked first `state` gets
        // replaced by an attacker's. First wins, deliberately.
        assertEquals(
            CallbackResult.Refused(CallbackFailure.STATE_MISMATCH),
            readCallback("state=wrong&state=right&code=c", "right"),
        )
    }

    @Test
    fun `the user pressing cancel is not an error`() {
        val result = readCallback("error=access_denied&state=st", "st")
        assertEquals(CallbackResult.Refused(CallbackFailure.DENIED, "access_denied"), result)
    }

    @Test
    fun `an error from Google is told apart from a cancellation`() {
        val result = readCallback("error=invalid_scope&state=st", "st")
        assertEquals(CallbackResult.Refused(CallbackFailure.PROVIDER_ERROR, "invalid_scope"), result)
    }

    @Test
    fun `a matching state with no code is not treated as success`() {
        assertEquals(CallbackResult.Refused(CallbackFailure.NO_CODE), readCallback("state=st", "st"))
        assertEquals(CallbackResult.Refused(CallbackFailure.NO_CODE), readCallback("state=st&code=", "st"))
    }

    @Test
    fun `a percent-encoded code comes back decoded`() {
        val result = readCallback("state=st&code=4%2F0Ab%2Bc", "st")
        assertEquals(CallbackResult.Code("4/0Ab+c"), result)
    }

    @Test
    fun `the state comparison does not short-circuit on length`() {
        assertFalse(constantTimeEquals("abc", "abcd"))
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
    }

    // ---- the loopback server, for real -------------------------------------------------------

    @Test
    fun `the receiver binds to loopback only and answers one real request`() {
        LoopbackReceiver().use { receiver ->
            receiver.start("the-state")
            assertTrue(receiver.redirectUri.startsWith("http://127.0.0.1:"), receiver.redirectUri)

            val connection = URI(receiver.redirectUri + "?code=abc123&state=the-state")
                .toURL().openConnection() as HttpURLConnection
            val status = connection.responseCode
            val page = connection.inputStream.use { String(it.readBytes()) }
            connection.disconnect()

            assertEquals(200, status)
            assertTrue("Latch" in page)
            assertFalse("abc123" in page, "the code must not be echoed into a page the browser keeps")
            assertEquals(CallbackResult.Code("abc123"), receiver.await(timeoutSeconds = 5))
        }
    }

    @Test
    fun `a real request with the wrong state is answered 400 and yields nothing`() {
        LoopbackReceiver().use { receiver ->
            receiver.start("the-state")
            val connection = URI(receiver.redirectUri + "?code=abc123&state=forged")
                .toURL().openConnection() as HttpURLConnection
            val status = connection.responseCode
            connection.errorStream?.use { it.readBytes() }
            connection.disconnect()

            assertEquals(400, status)
            val result = receiver.await(timeoutSeconds = 5)
            assertIs<CallbackResult.Refused>(result)
            assertEquals(CallbackFailure.STATE_MISMATCH, result.reason)
        }
    }

    @Test
    fun `waiting for a browser that never comes gives up rather than hanging`() {
        LoopbackReceiver().use { receiver ->
            receiver.start("st")
            assertEquals(
                CallbackResult.Refused(CallbackFailure.TIMED_OUT),
                receiver.await(timeoutSeconds = 1),
            )
        }
    }

    // ---- the token response ------------------------------------------------------------------

    @Test
    fun `a token response with no refresh token keeps the one already stored`() {
        // Google returns a refresh token only when it thinks it has not already given you one.
        // Storing null over a good one logs the user out on the next launch, which is the
        // defect this asserts against.
        val now = Instant.parse("2026-09-02T10:00:00Z")
        val tokens = readTokenResponse("""{"access_token":"at","expires_in":3599}""", now, previousRefresh = "kept")
        assertEquals("kept", tokens?.refreshToken)
        assertEquals("at", tokens?.accessToken)
        assertEquals(now.plusSeconds(3599), tokens?.expiresAt)
    }

    @Test
    fun `a response with no access token is not a token set`() {
        assertNull(readTokenResponse("""{"error":"invalid_grant"}""", Instant.EPOCH))
        assertNull(readTokenResponse("not json at all", Instant.EPOCH))
        assertNull(readTokenResponse("", Instant.EPOCH))
    }

    @Test
    fun `an error body is read for something a person could act on`() {
        assertEquals(
            "Token has been expired or revoked.",
            readTokenError("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""),
        )
        assertEquals("invalid_grant", readTokenError("""{"error":"invalid_grant"}"""))
        assertEquals("", readTokenError("<html>502</html>"))
    }

    // ---- the flow ----------------------------------------------------------------------------

    @Test
    fun `with no client configured, sign-in says so rather than failing somewhere inside`() {
        val auth = DesktopAuth(store(), config = null)
        assertFalse(auth.isConfigured)
        assertEquals(
            SignInOutcome.Failed(SignInFailure.NOT_CONFIGURED),
            auth.signIn { true },
        )
    }

    @Test
    fun `a cached access token is reused until it is nearly spent`() {
        var calls = 0
        val now = Instant.parse("2026-09-02T10:00:00Z")
        val auth = DesktopAuth(
            store().apply { put(DesktopAuth.REFRESH_TOKEN_KEY, "rt") },
            client,
            post = { _, _ -> calls++; HttpReply(200, """{"access_token":"at","expires_in":3600}""") },
            clock = { now },
        )
        assertEquals("at", auth.accessToken())
        assertEquals("at", auth.accessToken())
        assertEquals(1, calls, "the second call refreshed a token that had not expired")
    }

    @Test
    fun `a refresh Google rejects clears the stored token instead of retrying it for ever`() {
        // This is FR-806a's condition arriving: the grant was revoked in the account, or has
        // expired after long disuse. Keeping it would mean every later call retrying a token
        // that can never work.
        val secrets = store().apply { put(DesktopAuth.REFRESH_TOKEN_KEY, "rt") }
        val auth = DesktopAuth(
            secrets, client,
            post = { _, _ -> HttpReply(400, """{"error":"invalid_grant"}""") },
        )
        assertNull(auth.accessToken())
        assertNull(secrets.get(DesktopAuth.REFRESH_TOKEN_KEY))
        assertFalse(auth.isSignedIn)
    }

    @Test
    fun `a server error leaves the stored token alone`() {
        // The distinction that matters: a 500 is Google having a bad day, and throwing the
        // user's grant away over it would make them sign in again for nothing.
        val secrets = store().apply { put(DesktopAuth.REFRESH_TOKEN_KEY, "rt") }
        val auth = DesktopAuth(secrets, client, post = { _, _ -> HttpReply(503, "unavailable") })
        assertNull(auth.accessToken())
        assertEquals("rt", secrets.get(DesktopAuth.REFRESH_TOKEN_KEY))
    }

    @Test
    fun `a rotated refresh token replaces the stored one`() {
        val secrets = store().apply { put(DesktopAuth.REFRESH_TOKEN_KEY, "old") }
        val auth = DesktopAuth(
            secrets, client,
            post = { _, _ -> HttpReply(200, """{"access_token":"at","expires_in":3600,"refresh_token":"new"}""") },
        )
        assertEquals("at", auth.accessToken())
        assertEquals("new", secrets.get(DesktopAuth.REFRESH_TOKEN_KEY))
    }

    @Test
    fun `NFR-205 forget leaves no refresh token behind`() {
        val secrets = store().apply { put(DesktopAuth.REFRESH_TOKEN_KEY, "rt") }
        val auth = DesktopAuth(secrets, client)
        auth.forget()
        assertNull(secrets.get(DesktopAuth.REFRESH_TOKEN_KEY))
    }

    // ---- FR-806a: which failure it is decides whether the capture survives ------------------

    @Test
    fun `a refresh that cannot be sent is retryable, so the queue holds the capture`() {
        // The defect this was written against, found by running an offline save on 3 Sep 2026:
        // `accessToken()` answering null cannot say whether the network is down or the grant is
        // gone, so the save path reported REFUSED and **the capture was lost**. That is SRS
        // 1.39's shape on Android, one client over.
        val auth = DesktopAuth(
            store().apply { put(DesktopAuth.REFRESH_TOKEN_KEY, "rt") },
            client,
            post = { _, _ -> throw java.io.IOException("no route to host") },
        )
        val failure = assertFailsWith<com.latch.google.GoogleUnreachable> { auth.accessTokenOrThrow() }
        assertTrue(com.latch.google.isWorthRetrying(failure), "an offline refresh must be retryable")
    }

    @Test
    fun `a revoked grant is permanent, so it is reported instead of queued for ever`() {
        // The other direction, and getting it wrong puts an entry in the queue that can never
        // drain. A revoked grant is not cured by waiting.
        val secrets = store().apply { put(DesktopAuth.REFRESH_TOKEN_KEY, "rt") }
        val auth = DesktopAuth(secrets, client, post = { _, _ -> HttpReply(400, """{"error":"invalid_grant"}""") })
        val failure = assertFailsWith<com.latch.google.GoogleRejected> { auth.accessTokenOrThrow() }
        assertEquals(400, failure.status)
        assertFalse(com.latch.google.isWorthRetrying(failure))
        assertNull(secrets.get(DesktopAuth.REFRESH_TOKEN_KEY), "a dead grant must not be kept")
    }

    @Test
    fun `a server having a bad day is retryable and leaves the grant alone`() {
        val secrets = store().apply { put(DesktopAuth.REFRESH_TOKEN_KEY, "rt") }
        val auth = DesktopAuth(secrets, client, post = { _, _ -> HttpReply(503, "unavailable") })
        assertFailsWith<com.latch.google.GoogleUnreachable> { auth.accessTokenOrThrow() }
        assertEquals("rt", secrets.get(DesktopAuth.REFRESH_TOKEN_KEY))
    }

    @Test
    fun `being signed out is permanent rather than a network problem`() {
        val auth = DesktopAuth(store(), client, post = { _, _ -> HttpReply(200, "{}") })
        val failure = assertFailsWith<com.latch.google.GoogleRejected> { auth.accessTokenOrThrow() }
        assertEquals(401, failure.status)
    }

    @Test
    fun `a cached token is returned without asking Google at all`() {
        var calls = 0
        val now = Instant.parse("2026-09-03T10:00:00Z")
        val auth = DesktopAuth(
            store().apply { put(DesktopAuth.REFRESH_TOKEN_KEY, "rt") }, client,
            post = { _, _ -> calls++; HttpReply(200, """{"access_token":"at","expires_in":3600}""") },
            clock = { now },
        )
        assertEquals("at", auth.accessTokenOrThrow())
        assertEquals("at", auth.accessTokenOrThrow())
        assertEquals(1, calls)
    }

    @Test
    fun `the token endpoint is one AC-17 already allows`() {
        // Not a new host: oauth2.googleapis.com has been in ALLOWED_HOSTS since the guard was
        // written, against NFR-205's revoke. A sign-in must not be the thing that widens it.
        assertTrue(com.latch.google.ALLOWED_HOSTS.contains(URI(OAuthRequest.TOKEN_ENDPOINT).host))
    }
}
