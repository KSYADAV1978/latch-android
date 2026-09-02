package com.latch.desktop.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** What came back from the browser. */
sealed interface CallbackResult {
    data class Code(val code: String) : CallbackResult

    /**
     * Named rather than phrased, as everything outside the UI in this project is.
     * [DENIED] is the user pressing Cancel and is not an error to apologise for.
     */
    data class Refused(val reason: CallbackFailure, val detail: String = "") : CallbackResult
}

enum class CallbackFailure { DENIED, STATE_MISMATCH, NO_CODE, TIMED_OUT, PROVIDER_ERROR }

/**
 * Decides what a callback's query string means. Pure, so every branch — including the two
 * that matter for security — is reachable without a browser.
 */
fun readCallback(query: String?, expectedState: String): CallbackResult {
    val parameters = parseQuery(query)

    // Checked before anything else is even looked at. A callback that did not come from the
    // request this process made is not evidence of anything, and reading its `error` or its
    // `code` first would mean acting on an attacker's parameters before noticing.
    val state = parameters["state"]
    if (state == null || !constantTimeEquals(state, expectedState)) {
        return CallbackResult.Refused(CallbackFailure.STATE_MISMATCH)
    }

    parameters["error"]?.let { error ->
        val failure = if (error == "access_denied") CallbackFailure.DENIED else CallbackFailure.PROVIDER_ERROR
        return CallbackResult.Refused(failure, error)
    }

    val code = parameters["code"]
    if (code.isNullOrBlank()) return CallbackResult.Refused(CallbackFailure.NO_CODE)
    return CallbackResult.Code(code)
}

internal fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    query.split('&').forEach { pair ->
        if (pair.isBlank()) return@forEach
        val equals = pair.indexOf('=')
        val name = if (equals < 0) pair else pair.substring(0, equals)
        val value = if (equals < 0) "" else pair.substring(equals + 1)
        val decoded = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrElse { return@forEach }
        // First wins. A duplicated parameter is a smuggling attempt, not a typo, and taking
        // the last one is how a second `state` overrides a checked first.
        out.putIfAbsent(runCatching { URLDecoder.decode(name, StandardCharsets.UTF_8) }.getOrElse { name }, decoded)
    }
    return out
}

/**
 * Compares without leaking, through timing, how much of the value matched.
 *
 * The state is short-lived and the window is one request, so this is belt and braces — but it
 * costs one line and the alternative is reasoning about whether an attacker can retry, which
 * is a worse thing to be right about by luck.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var difference = 0
    for (index in a.indices) difference = difference or (a[index].code xor b[index].code)
    return difference == 0
}

/**
 * RFC 8252's loopback redirect: a one-shot HTTP server the browser hands the code back to.
 *
 * **Bound to the loopback address explicitly, on an ephemeral port.** Binding to all
 * interfaces would put an authorization endpoint on the network for as long as a sign-in took;
 * the loopback address means only this machine can reach it. The port is whatever the OS
 * gives, because a fixed one is both a collision with whatever else wants it and a thing an
 * attacker can be waiting on.
 *
 * **It serves exactly one request and then stops.** A server that stayed up would be a second
 * way into this process for no benefit — the code is single-use and the flow is over.
 */
class LoopbackReceiver(private val path: String = "/callback") : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val answers = ArrayBlockingQueue<CallbackResult>(1)

    val redirectUri: String get() = "http://127.0.0.1:" + server.address.port + path

    fun start(expectedState: String) {
        server.createContext(path) { exchange ->
            val result = readCallback(exchange.requestURI.rawQuery, expectedState)
            val page = pageFor(result).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            // The browser is told nothing it could pass on. A referrer or a cached page
            // carrying the code would outlive the exchange that consumed it.
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
            exchange.sendResponseHeaders(if (result is CallbackResult.Code) 200 else 400, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
            answers.offer(result)
        }
        server.start()
    }

    /** Waits for the browser, or gives up. Never blocks for ever: the user may simply walk away. */
    fun await(timeoutSeconds: Long = 300): CallbackResult =
        answers.poll(timeoutSeconds, TimeUnit.SECONDS) ?: CallbackResult.Refused(CallbackFailure.TIMED_OUT)

    override fun close() {
        server.stop(0)
    }
}

/**
 * What the browser shows.
 *
 * Deliberately plain and self-contained: no script, no external stylesheet, no font. A page
 * that fetched anything would be this application causing a request to a third party during
 * sign-in, which is the one thing NFR-201 and AC-17 are about.
 */
internal fun pageFor(result: CallbackResult): String {
    val message = when {
        result is CallbackResult.Code -> "Signed in. You can close this tab and go back to Latch."
        result is CallbackResult.Refused && result.reason == CallbackFailure.DENIED ->
            "Sign-in was cancelled. Nothing was changed."
        else -> "Sign-in could not be completed. Go back to Latch and try again."
    }
    return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
        "<title>Latch</title></head><body style=\"font-family:system-ui;margin:3rem\">" +
        "<h1>Latch</h1><p>" + message + "</p></body></html>"
}
