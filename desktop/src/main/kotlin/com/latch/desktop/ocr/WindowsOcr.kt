package com.latch.desktop.ocr

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * FR-303, on the platform's own recogniser.
 *
 * **A PowerShell child process rather than a native binding**, which is the decision worth
 * reading before changing anything here. `Windows.Media.Ocr` is a WinRT API, and the three ways
 * to reach it from a JVM are a JNI shim needing a C toolchain, a foreign-function binding
 * needing either a preview API or a library under NFR-501, or the WinRT projection PowerShell
 * already carries. The third needs nothing installed, on any Windows this app supports. The
 * price is process startup, measured rather than assumed, and it is paid once per capture
 * against a 2.5-second NFR-101 budget that the recognition itself uses about a tenth of.
 *
 * The process is the only impure thing in this file. Everything that decides anything is in
 * `OcrOutput.kt`, where a JVM test reaches it without a Windows.
 */
class WindowsOcr(
    private val timeout: java.time.Duration = java.time.Duration.ofSeconds(30),
    private val launch: (File) -> BridgeOutput = ::runPowerShell,
) {
    fun recognise(image: File): OcrOutcome {
        if (!image.isFile) return OcrOutcome.Failed(OcrFailure.UNREADABLE_SOURCE, image.path)
        val output = runCatching { launch(image) }.getOrElse {
            return OcrOutcome.Failed(OcrFailure.BRIDGE_FAILED, it.message.orEmpty())
        }
        if (output.timedOut) {
            return OcrOutcome.Failed(OcrFailure.BRIDGE_FAILED, "recogniser did not answer within $timeout")
        }
        if (output.stdout.isBlank()) {
            // stderr rather than stdout means the script died before its own error handling —
            // which is a bridge failure and not a recognition one, and the distinction matters
            // because the first is a defect here and the second is a fact about the image.
            return OcrOutcome.Failed(OcrFailure.BRIDGE_FAILED, output.stderr.take(400))
        }
        return parseRecogniserOutput(output.stdout)
    }

    companion object {
        /** Whether this machine can recognise at all. Cheap enough to ask before offering to. */
        fun isAvailable(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
    }
}

/** What the child process said. */
data class BridgeOutput(val stdout: String, val stderr: String, val timedOut: Boolean = false)

/**
 * The script itself, read from this module's resources.
 *
 * **Passed with `-EncodedCommand` rather than written to a temp file**, because a script on
 * disk is a script something else can rewrite between the moment it is written and the moment
 * it is run, and this one is executed with the user's own privileges. Encoding it into the
 * command line leaves nothing on disk and nothing to clean up. The limit that buys is the
 * command line's own 32,767 characters, which the guard below names rather than discovers.
 */
internal fun runPowerShell(image: File): BridgeOutput {
    val script = WindowsOcr::class.java.getResourceAsStream("/ocr/recognise.ps1")
        ?.readBytes()?.toString(StandardCharsets.UTF_8)
        ?: error("recognise.ps1 is missing from the build")

    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
    check(encoded.length < 30_000) { "recognise.ps1 has outgrown -EncodedCommand at ${encoded.length} chars" }

    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-EncodedCommand", encoded,
    ).apply {
        // Not on the command line: see recognise.ps1. A file name is user data.
        environment()["LATCH_OCR_PATH"] = image.absolutePath
    }.start()

    process.outputStream.close()
    val out = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
    val err = process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
    val finished = process.waitFor(30, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return BridgeOutput(out, err, timedOut = true)
    }
    return BridgeOutput(out, err)
}
