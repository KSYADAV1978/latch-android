package com.latch.desktop.store

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * NFR-203, on Windows: secrets encrypted at rest under a key the operating system holds.
 *
 * **Why a bridge rather than a library.** The Android client gets this from the Keystore
 * through `KeystoreCipher`; the equivalent on Windows is DPAPI, which is a Win32 API with no
 * JDK binding. The same three routes were available as for FR-303's recogniser and the same
 * one is taken — the .NET Framework that ships inside Windows, reached through PowerShell —
 * so there is nothing to install and nothing to justify under NFR-501.
 *
 * **Nothing but base64 crosses the process boundary.** The first version passed text, and a
 * Devanagari secret came back as twelve characters instead of four — PowerShell decodes a
 * redirected stdin with the console's code page rather than UTF-8. Carrying bytes removes the
 * question rather than answering it, and keeps the plaintext from ever existing as a string
 * inside a process this code does not control.
 *
 * **The payload never touches a command line.** A process's arguments are readable by any
 * other process the same user can run, so a refresh token passed as an argument would be
 * readable by anything on the machine for as long as the child lived. It goes over stdin and
 * comes back over stdout, both of which are private pipes between parent and child.
 *
 * **What this inherits from DPAPI, stated because it is a limitation and not a feature.** The
 * key is derived from the user's own credentials, so an encrypted file copied to another
 * machine or opened under another account is unreadable. That is the point. It also means a
 * machine transfer loses whatever is stored — the same limit `CLAUDE.md` already records for
 * a queued capture on Android, and it applies here to the refresh token: after a transfer the
 * user signs in again.
 */
class WindowsSecrets(
    private val run: (Mode, List<String>) -> BridgeReply = ::runDpapi,
) {
    enum class Mode { PROTECT, UNPROTECT }

    /** Ciphertext as base64, or null where the operating system refused. */
    fun protect(plaintext: String): String? = protectAll(listOf(plaintext)).single()

    /**
     * The plaintext, or null where it could not be decrypted.
     *
     * Null is the answer for a record written by another user, restored from another machine,
     * or corrupted — and the caller treats all three alike, which is the discipline every
     * store in this project already follows: an unreadable record is dropped rather than
     * guessed at.
     */
    fun unprotect(ciphertext: String): String? = unprotectAll(listOf(ciphertext)).single()

    /**
     * The same operation over many values, in **one** crossing of the process boundary.
     *
     * **This is what makes a per-record store affordable**, and it was added for FR-704 rather
     * than for tidiness. Each call to this bridge costs a PowerShell process — measured at
     * roughly 700–900 ms — so a store that unprotected one record at a time cost the user a
     * second per row every time the Inbox count was read. A fortnight's worth of held captures
     * (FR-705) would have made an "unobtrusive count" take ten seconds to compute.
     *
     * **The isolation it exists for is preserved exactly**: the child answers one line per
     * input line, so one value the operating system refuses comes back null and its neighbours
     * still decrypt. Batching the transport does not batch the failure.
     *
     * A reply with the wrong number of lines is **all** null, deliberately. A bridge that
     * answered a different question from the one asked cannot have its answers matched to
     * inputs by position, and guessing which record each line belonged to is how a secret comes
     * back under the wrong name.
     */
    fun protectAll(plaintexts: List<String>): List<String?> =
        answer(Mode.PROTECT, plaintexts.map { base64(it.toByteArray(StandardCharsets.UTF_8)) })

    fun unprotectAll(ciphertexts: List<String>): List<String?> =
        answer(Mode.UNPROTECT, ciphertexts).map { encoded ->
            encoded?.let { runCatching { String(unbase64(it), StandardCharsets.UTF_8) }.getOrNull() }
        }

    private fun answer(mode: Mode, payloads: List<String>): List<String?> {
        if (payloads.isEmpty()) return emptyList()
        val reply = runCatching { run(mode, payloads) }.getOrNull()
            ?: return List(payloads.size) { null }
        return readReplies(reply, payloads.size)
    }

    companion object {
        fun isAvailable(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
    }
}

/** What the child process said, before it is interpreted. */
data class BridgeReply(val stdout: String, val timedOut: Boolean = false)

internal fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

internal fun unbase64(text: String): ByteArray = Base64.getDecoder().decode(text)

/**
 * The bridge's line protocol, pulled out so a test can reach every branch of it without a
 * Windows — the rule this project keeps relearning about where decisions have to live.
 *
 * One reply line per request line, in order. Anything else — a timeout, a short reply, a long
 * one — is every value null rather than a best guess at which line meant what.
 */
internal fun readReplies(reply: BridgeReply, expected: Int): List<String?> {
    if (reply.timedOut) return List(expected) { null }
    val lines = reply.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.size != expected) return List(expected) { null }
    return lines.map { line ->
        when {
            line.startsWith("OK ") -> line.removePrefix("OK ")
            // `OK` with nothing after it is an empty secret, which is a legitimate value to
            // have stored and must not come back as a failure.
            line == "OK" -> ""
            else -> null
        }
    }
}

internal fun readReply(reply: BridgeReply): String? = readReplies(reply, 1).single()

/** One request per line, and the child answers one line per request. */
private const val LINE = "\n"

private fun runDpapi(mode: WindowsSecrets.Mode, payloads: List<String>): BridgeReply {
    val script = WindowsSecrets::class.java.getResourceAsStream("/store/dpapi.ps1")
        ?.readBytes()?.toString(StandardCharsets.UTF_8)
        ?: error("dpapi.ps1 is missing from the build")
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))

    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-EncodedCommand", encoded,
    ).apply { environment()["LATCH_DPAPI_MODE"] = mode.name }.start()

    process.outputStream.use {
        it.write(payloads.joinToString(LINE).toByteArray(StandardCharsets.UTF_8))
    }
    val out = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
    // The timeout scales with the work asked for, because the whole point of the batch is that
    // one call may now carry a fortnight of held captures.
    val seconds = 20L + payloads.size
    if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return BridgeReply(out, timedOut = true)
    }
    return BridgeReply(out)
}
