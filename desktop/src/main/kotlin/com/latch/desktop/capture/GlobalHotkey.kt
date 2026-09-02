package com.latch.desktop.capture

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** What the sidecar said, line by line. */
sealed interface HotkeyEvent {
    data object Ready : HotkeyEvent
    data object Pressed : HotkeyEvent
    data class Refused(val code: Int) : HotkeyEvent
    data class Broken(val detail: String) : HotkeyEvent
}

/**
 * The sidecar's one-word protocol, pure so a test reaches every branch without a Windows.
 *
 * `ERR 1409` is `ERROR_HOTKEY_ALREADY_REGISTERED` and is the case worth telling apart: it
 * means another application holds the combination, which is a thing the user can fix by
 * choosing a different one — not a defect, and not something to retry.
 */
internal fun readHotkeyLine(line: String): HotkeyEvent? = when {
    line.isBlank() -> null
    line.trim() == "READY" -> HotkeyEvent.Ready
    line.trim() == "PRESSED" -> HotkeyEvent.Pressed
    line.startsWith("ERR ") -> line.removePrefix("ERR ").trim().toIntOrNull()
        ?.let { HotkeyEvent.Refused(it) } ?: HotkeyEvent.Broken(line)
    else -> HotkeyEvent.Broken(line)
}

const val ERROR_HOTKEY_ALREADY_REGISTERED: Int = 1409

/**
 * FR-302's registration, held by a child process.
 *
 * **Why a process and not a thread.** `RegisterHotKey` delivers `WM_HOTKEY` to a *thread's*
 * message queue, and a JVM thread has no such queue. The alternatives were a native binding
 * under NFR-501 or a preview foreign-function API; this uses the C# compiler that ships inside
 * Windows, so there is nothing to install.
 *
 * **What it costs, stated because it is the honest downside.** The registration lives as long
 * as the child does, so if this process is killed the hotkey goes with it — which is correct —
 * but a child left behind by a hard kill of the parent would hold the combination until it is
 * reaped. `destroyForcibly` on shutdown is what prevents that, and the sidecar's `finally`
 * unregisters on any ordinary exit.
 */
class GlobalHotkey(
    private val spec: HotkeySpec,
    private val launch: (HotkeySpec) -> Process = ::launchSidecar,
) : AutoCloseable {

    private var process: Process? = null
    private val running = AtomicBoolean(false)

    /**
     * Starts listening. [onEvent] is called on the reader thread, so a handler that wants to
     * touch a window must hop to the UI thread itself — which the caller does, because doing
     * it here would tie this class to a toolkit.
     */
    fun start(onEvent: (HotkeyEvent) -> Unit) {
        check(process == null) { "already started" }
        val child = launch(spec)
        process = child
        running.set(true)

        Thread({
            BufferedReader(InputStreamReader(child.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    readHotkeyLine(line)?.let(onEvent)
                }
            }
            if (running.get()) onEvent(HotkeyEvent.Broken("the hotkey listener stopped"))
        }, "latch-hotkey").apply { isDaemon = true }.start()
    }

    override fun close() {
        running.set(false)
        process?.destroyForcibly()
        process = null
    }
}

private fun launchSidecar(spec: HotkeySpec): Process {
    val script = GlobalHotkey::class.java.getResourceAsStream("/capture/hotkey.ps1")
        ?.readBytes()?.toString(StandardCharsets.UTF_8)
        ?: error("hotkey.ps1 is missing from the build")
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))

    return ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-EncodedCommand", encoded,
    ).apply {
        environment()["LATCH_HOTKEY_MODIFIERS"] = spec.modifiers.toString()
        environment()["LATCH_HOTKEY_VK"] = spec.virtualKey.toString()
        redirectErrorStream(false)
    }.start()
}
