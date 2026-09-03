package com.latch.webhook

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * FR-1004b's delivery: what a test can actually reach, and what it deliberately cannot.
 *
 * **Reachable, and asserted below**: that nothing leaves this machine for an address the rules
 * refuse — checked against a *running* server that is never hit, which is the only way to tell
 * "refused" apart from "sent and failed"; that an unreachable endpoint is reported rather than
 * thrown, which is FR-1004b's "shall not raise a blocking error"; and the mapping from an
 * answered status to an outcome, which is the decision and is therefore a pure function rather
 * than something buried in a socket.
 *
 * **Not reachable, and recorded rather than faked**: an accepted delivery over the wire. The
 * sender refuses anything that is not HTTPS, and standing up a TLS listener here would need a
 * certificate this project has no library to mint. A fake that answered over plaintext would be
 * testing a path the real one refuses to take — the shape `CLAUDE.md` records as worse than no
 * test. The accepted path is in the Windows human backlog with an endpoint to point it at.
 *
 * Everything binds to the loopback address explicitly: a listener on every interface would put
 * one on the network for the length of a build.
 */
class WebhookSenderTest {

    private fun server(): Pair<HttpServer, AtomicInteger> {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.requestBody.readBytes()
            val bytes = "ok".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to hits
    }

    private fun HttpServer.url() = "http://127.0.0.1:" + address.port + "/hook"

    @Test
    fun `a plaintext endpoint sends nothing at all, with a server listening`() = runTest {
        // The fixture is what makes this a real check rather than a restatement of
        // `validateEndpoint`: the server is up and answering, so a sender that opened the
        // connection would succeed and the hit count would say so.
        val (server, hits) = server()
        try {
            val delivery = WebhookSender().deliver(server.url(), "{\"items\":[]}")
            assertEquals(WebhookResult.REFUSED_BEFORE_SENDING, delivery.result)
            assertFalse(delivery.ok)
            assertEquals(0, hits.get(), "captured content went over plaintext")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an endpoint with credentials sends nothing, with a server listening`() = runTest {
        val (server, hits) = server()
        try {
            val credentialled = server.url().replace("http://", "http://user:pass@")
            assertEquals(
                WebhookResult.REFUSED_BEFORE_SENDING,
                WebhookSender().deliver(credentialled, "{}").result,
            )
            assertEquals(0, hits.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an unreachable endpoint is reported and never throws`() = runTest {
        // FR-1004b: "shall not raise a blocking error". Port 9 is discard and nothing listens.
        val delivery = WebhookSender().deliver("https://127.0.0.1:9/hook", "{}")
        assertEquals(WebhookResult.UNREACHABLE, delivery.result)
        assertFalse(delivery.ok)
        assertEquals(null, delivery.status)
    }

    @Test
    fun `the record carries the moment the attempt was made`() = runTest {
        val at = Instant.parse("2026-09-03T09:15:00Z")
        assertEquals(at, WebhookSender { at }.deliver("http://example.invalid/hook", "{}").at)
    }

    @Test
    fun `a status the endpoint gave decides the outcome, and is kept`() {
        val at = Instant.parse("2026-09-03T09:15:00Z")
        assertTrue(deliveryFor(at, 200).ok)
        assertTrue(deliveryFor(at, 204).ok)
        assertTrue(deliveryFor(at, 299).ok)

        // 404 against 401 is the difference between a wrong path and a wrong token, which is
        // why the status is kept rather than reduced to a boolean.
        assertEquals(WebhookResult.ENDPOINT_REFUSED, deliveryFor(at, 404).result)
        assertEquals(404, deliveryFor(at, 404).status)
        assertEquals(WebhookResult.ENDPOINT_REFUSED, deliveryFor(at, 500).result)
    }

    @Test
    fun `a redirect is a refusal rather than a move`() {
        // `instanceFollowRedirects` is off because a redirect is the one way an address
        // `validateEndpoint` approved could still deliver somewhere else — including over
        // plaintext. So a 30x is the endpoint saying no.
        assertEquals(WebhookResult.ENDPOINT_REFUSED, deliveryFor(Instant.EPOCH, 301).result)
        assertEquals(WebhookResult.ENDPOINT_REFUSED, deliveryFor(Instant.EPOCH, 302).result)
        assertEquals(WebhookResult.ENDPOINT_REFUSED, deliveryFor(Instant.EPOCH, 307).result)
    }

    @Test
    fun `nothing in this module reaches for the Google guard`() {
        // The separation AC-17 rests on, asserted rather than assumed: a webhook is the single
        // documented exception to `ALLOWED_HOSTS`, and it is sent by a client that does not
        // consult it. If this file ever did, the allowlist would have to grow to carry a user
        // endpoint, and the property the guard exists to hold would be gone.
        val source = java.io.File("src/main/kotlin/com/latch/webhook/Webhook.kt").readText()
        // The imports rather than the prose: this file explains at length *why* it does not use
        // the guard, so a text search for the name finds the explanation. What it must not have
        // is a way to call it.
        val googleImports = source.lineSequence()
            .filter { it.startsWith("import com.latch.google") }
            .toList()
        assertEquals(
            listOf("import com.latch.google.json.JSONArray", "import com.latch.google.json.JSONObject"),
            googleImports,
            "the webhook reached into :google for something other than the JSON writer",
        )
        assertTrue("instanceFollowRedirects = false" in source, "a redirect could reach elsewhere")
        // FR-1004b: one attempt. A loop or a retry would be visible as one of these.
        assertFalse("while (" in source, "the sender grew a loop")
        assertFalse("repeat(" in source, "the sender grew a retry")
    }
}
