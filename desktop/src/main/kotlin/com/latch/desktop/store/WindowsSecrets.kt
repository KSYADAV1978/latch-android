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
    private val run: (Mode, String) -> BridgeReply = ::runDpapi,
) {
    enum class Mode { PROTECT, UNPROTECT }

    /** Ciphertext as base64, or null where the operating system refused. */
    fun protect(plaintext: String): String? =
        answer(Mode.PROTECT, base64(plaintext.toByteArray(StandardCharsets.UTF_8)))

    /**
     * The plaintext, or null where it could not be decrypted.
     *
     * Null is the answer for a record written by another user, restored from another machine,
     * or corrupted — and the caller treats all three alike, which is the discipline every
     * store in this project already follows: an unreadable record is dropped rather than
     * guessed at.
     */
    fun unprotect(ciphertext: String): String? = answer(Mode.UNPROTECT, ciphertext)
        ?.let { runCatching { String(unbase64(it), StandardCharsets.UTF_8) }.getOrNull() }

    private fun answer(mode: Mode, payload: String): String? {
        val reply = runCatching { run(mode, payload) }.getOrNull() ?: return null
        return readReply(reply)
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
 * The bridge's one-line protocol, pulled out so a test can reach every branch of it without a
 * Windows — the rule this project keeps relearning about where decisions have to live.
 */
internal fun readReply(reply: BridgeReply): String? {
    if (reply.timedOut) return null
    val line = reply.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
    return when {
        line.startsWith("OK ") -> line.removePrefix("OK ")
        // `OK` with nothing after it is an empty secret, which is a legitimate value to have
        // stored and must not come back as a failure.
        line == "OK" -> ""
        else -> null
    }
}

private fun runDpapi(mode: WindowsSecrets.Mode, payload: String): BridgeReply {
    val script = WindowsSecrets::class.java.getResourceAsStream("/store/dpapi.ps1")
        ?.readBytes()?.toString(StandardCharsets.UTF_8)
        ?: error("dpapi.ps1 is missing from the build")
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))

    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-EncodedCommand", encoded,
    ).apply { environment()["LATCH_DPAPI_MODE"] = mode.name }.start()

    process.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
    val out = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
    if (!process.waitFor(20, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return BridgeReply(out, timedOut = true)
    }
    return BridgeReply(out)
}
