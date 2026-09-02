package com.latch.desktop.auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * FR-002's grant, composed. Everything here is pure, so every decision about the flow is
 * reachable from a JVM test rather than only from a browser and a live Google.
 *
 * **The desktop flow is not the Android one, and the difference is the whole design.** On
 * Android, `play-services-auth` performs the grant and caches the access token itself, which
 * is why `CLAUDE.md` records that no token is persisted and NFR-203 is satisfied by there
 * being nothing at rest. There is no Play services on Windows: this application performs the
 * authorization-code exchange itself, and it must keep the refresh token or ask the user to
 * sign in on every launch. **So the Windows client stores a secret where the Android client
 * stores none**, and that is a genuine asymmetry rather than an oversight — see
 * `WindowsSecrets` for where it goes.
 */
object OAuthRequest {

    /** FR-002, and nothing beyond it. FR-003 forbids adding to this list without an SRS change. */
    val SCOPES: List<String> = listOf(
        "https://www.googleapis.com/auth/calendar.events",
        "https://www.googleapis.com/auth/calendar.calendarlist",
        "https://www.googleapis.com/auth/calendar.calendars",
        "https://www.googleapis.com/auth/tasks",
    )

    const val AUTHORIZATION_ENDPOINT: String = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT: String = "https://oauth2.googleapis.com/token"

    /**
     * The URL the user's browser is sent to.
     *
     * **`access_type=offline` and `prompt=consent` together**, because without both Google
     * returns a refresh token on the first grant only. A user who signed in, was signed out by
     * a token revocation, and signed in again would otherwise get an access token good for an
     * hour and nothing to renew it with — which presents as the app working until lunchtime.
     */
    fun authorizationUrl(clientId: String, redirectUri: String, challenge: PkceChallenge, state: String): String {
        val parameters = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to SCOPES.joinToString(" "),
            "code_challenge" to challenge.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return AUTHORIZATION_ENDPOINT + "?" + parameters.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }
    }

    /**
     * The body that turns a code into tokens.
     *
     * A `client_secret` is included where one is configured. Google issues one for a Desktop
     * client and it is **not** a secret in any meaningful sense — it ships inside every copy of
     * the application, which is exactly why RFC 8252 requires PKCE for this client type and why
     * PKCE rather than the secret is what actually protects the exchange here.
     */
    fun tokenExchangeBody(
        clientId: String,
        clientSecret: String?,
        code: String,
        redirectUri: String,
        verifier: String,
    ): String = form(
        buildMap {
            put("client_id", clientId)
            if (!clientSecret.isNullOrBlank()) put("client_secret", clientSecret)
            put("code", code)
            put("code_verifier", verifier)
            put("grant_type", "authorization_code")
            put("redirect_uri", redirectUri)
        }
    )

    fun refreshBody(clientId: String, clientSecret: String?, refreshToken: String): String = form(
        buildMap {
            put("client_id", clientId)
            if (!clientSecret.isNullOrBlank()) put("client_secret", clientSecret)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }
    )

    private fun form(fields: Map<String, String>) =
        fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

/** A PKCE verifier and the challenge derived from it (RFC 7636 S256). */
data class PkceChallenge(val verifier: String, val challenge: String)

/**
 * A fresh PKCE pair.
 *
 * The verifier is 32 bytes of `SecureRandom` in base64url, which lands inside RFC 7636's
 * 43-to-128-character range. S256 rather than `plain`: `plain` puts the verifier in the
 * authorization URL, and that URL passes through the user's browser history and any corporate
 * proxy on the way.
 */
fun newPkce(random: SecureRandom = SecureRandom()): PkceChallenge {
    val bytes = ByteArray(32).also(random::nextBytes)
    val verifier = base64Url(bytes)
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
    return PkceChallenge(verifier, base64Url(digest))
}

/**
 * The value that ties the browser's answer back to the request this process made.
 *
 * Without it, anything able to reach the loopback port could hand this application a code from
 * a different authorization — an account the user did not choose, or an attacker's. The
 * receiver refuses any callback whose state does not match, which is the one check that makes
 * a loopback redirect safe to accept at all.
 */
fun newState(random: SecureRandom = SecureRandom()): String = base64Url(ByteArray(24).also(random::nextBytes))

private fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
