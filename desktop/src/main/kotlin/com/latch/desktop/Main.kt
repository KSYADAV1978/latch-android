package com.latch.desktop

import com.latch.desktop.auth.ClientConfig
import com.latch.desktop.auth.DesktopAuth
import com.latch.desktop.auth.SignInFailure
import com.latch.desktop.auth.SignInOutcome
import com.latch.desktop.capture.CaptureOutcome
import com.latch.desktop.capture.ClipboardCapture
import com.latch.desktop.capture.GlobalHotkey
import com.latch.desktop.capture.HotkeyEvent
import com.latch.desktop.capture.HotkeyParse
import com.latch.desktop.capture.HotkeySpec
import com.latch.desktop.capture.buildCapture
import com.latch.desktop.capture.parseHotkey
import com.latch.desktop.capture.writeForRecognition
import com.latch.desktop.ocr.WindowsOcr
import com.latch.desktop.store.SecretFile
import com.latch.desktop.store.latchDataDirectory
import com.latch.desktop.ui.CaptureWindow
import com.latch.desktop.ui.DesktopStrings
import com.latch.desktop.ui.LatchTray
import com.latch.desktop.ui.TrayAction
import com.latch.desktop.ui.TrayModel
import com.latch.desktop.ui.messageFor
import com.latch.desktop.ui.popupModel
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.awt.Desktop
import java.awt.TrayIcon
import java.io.File
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * FR-301: the Windows client, as a tray application.
 *
 * This file is wiring and nothing else. Every decision it appears to make is made by a pure
 * function somewhere a test can reach: `parseHotkey`, `buildCapture`, `popupModel`, `trayMenu`.
 * That is deliberate and is the same arrangement `:app` uses — `CLAUDE.md` records that twice
 * in this project the untested thing turned out to be the load-bearing thing, both times
 * because it was inside something a test could not construct.
 */
object Latch {
    private val secrets by lazy { SecretFile(File(latchDataDirectory(), "secrets.dat")) }
    private val auth by lazy { DesktopAuth(secrets, ClientConfig.load()) }
    private val clipboard = ClipboardCapture()
    private val recogniser = WindowsOcr()

    private var tray: LatchTray? = null
    private var hotkey: GlobalHotkey? = null
    private var window: CaptureWindow? = null

    private val spec: HotkeySpec =
        (parseHotkey(HotkeySpec.DEFAULT) as HotkeyParse.Parsed).spec

    fun start() {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

        val trayIcon = LatchTray(::onTrayAction)
        if (!trayIcon.install(model())) {
            System.err.println("This desktop has no system tray, so Latch has nowhere to live.")
            exitProcess(1)
        }
        tray = trayIcon

        val listener = GlobalHotkey(spec)
        listener.start { event ->
            when (event) {
                HotkeyEvent.Pressed -> SwingUtilities.invokeLater(::capture)
                HotkeyEvent.Ready -> Unit
                is HotkeyEvent.Refused -> trayIcon.say(
                    "Latch",
                    "Another application already uses " + spec.display +
                        ". Choose a different shortcut in Settings.",
                    TrayIcon.MessageType.WARNING,
                )
                is HotkeyEvent.Broken -> trayIcon.say(
                    "Latch", "The keyboard shortcut stopped working.", TrayIcon.MessageType.ERROR,
                )
            }
        }
        hotkey = listener

        Runtime.getRuntime().addShutdownHook(Thread { shutDown() })
    }

    private fun model() = TrayModel(
        hotkeyLabel = spec.display,
        signedInAs = if (auth.isSignedIn) "your Google account" else null,
        configured = auth.isConfigured,
        pending = 0,
    )

    private fun onTrayAction(action: TrayAction) = when (action) {
        TrayAction.CAPTURE -> SwingUtilities.invokeLater(::capture)
        TrayAction.SIGN_IN -> signIn()
        TrayAction.SIGN_OUT -> {
            auth.forget()
            tray?.update(model())
        }
        TrayAction.SETTINGS -> tray?.say("Latch", "Settings are not built yet.") ?: Unit
        TrayAction.QUIT -> {
            shutDown()
            exitProcess(0)
        }
    }

    /**
     * FR-302's whole path: copy, read, recognise where it is a picture, parse, show.
     *
     * The recognition and the parse happen off the event thread. NFR-101 budgets a capture
     * 800 ms for text and 2.5 s for an image, and the bridge costs about 570 ms of that on
     * its own — doing it on the event thread would freeze whatever the user was looking at.
     */
    private fun capture() {
        clipboard.copySelection()
        val clip = clipboard.read()

        Thread({
            val outcome = buildCapture(clip) { image ->
                val file = writeForRecognition(image)
                try {
                    recogniser.recognise(file)
                } finally {
                    // A capture is often a screenshot of a private message. Leaving it in the
                    // temp directory would put on disk, in the clear, the content NFR-201
                    // keeps off the network.
                    file.delete()
                }
            }
            SwingUtilities.invokeLater { present(outcome) }
        }, "latch-capture").apply { isDaemon = true }.start()
    }

    private fun present(outcome: CaptureOutcome) {
        when (outcome) {
            is CaptureOutcome.Nothing -> tray?.say("Latch", messageFor(outcome.reason))
            is CaptureOutcome.Ready -> {
                val context = ParseContext(now = LocalDateTime.now())
                val result = DateParser.parse(outcome.capture.text, context)
                val selected = result.candidates.indices.toSet()

                window?.close()
                val opened = CaptureWindow(
                    onSave = { ticked, titles -> save(ticked, titles) },
                    onClose = { window = null },
                )
                window = opened
                opened.show(popupModel(outcome.capture, result, selected, LocalDate.now()))
            }
        }
    }

    /**
     * Not built past this point, and it says so rather than appearing to work.
     *
     * The write path is `:google`'s and is shared with Android; what is missing here is the
     * destination — FR-901's calendar list and the FR-100 setup that chooses from it — and the
     * FR-806 queue that holds a save made offline. Both are owed, and a Save that silently did
     * nothing would be worse than a Save that explains itself.
     */
    private fun save(selected: Set<Int>, titleOverrides: Map<Int, String>) {
        val message = when {
            !auth.isConfigured -> DesktopStrings.NOT_CONFIGURED
            !auth.isSignedIn -> DesktopStrings.NOT_SIGNED_IN
            else -> "Saving is not wired up yet: this build has no calendar chosen."
        }
        tray?.say("Latch", message, TrayIcon.MessageType.WARNING)
        window?.close()
    }

    private fun signIn() {
        if (!auth.isConfigured) {
            tray?.say("Latch", DesktopStrings.NOT_CONFIGURED, TrayIcon.MessageType.WARNING)
            return
        }
        Thread({
            val outcome = auth.signIn(::openInBrowser)
            SwingUtilities.invokeLater {
                tray?.update(model())
                when (outcome) {
                    SignInOutcome.Succeeded -> tray?.say("Latch", "Signed in.")
                    is SignInOutcome.Failed -> tray?.say(
                        "Latch", signInMessage(outcome.reason), TrayIcon.MessageType.WARNING,
                    )
                }
            }
        }, "latch-signin").apply { isDaemon = true }.start()
    }

    private fun signInMessage(reason: SignInFailure) = when (reason) {
        SignInFailure.NOT_CONFIGURED -> DesktopStrings.NOT_CONFIGURED
        SignInFailure.CANCELLED -> "Sign-in was cancelled. Nothing was changed."
        SignInFailure.NO_REFRESH_TOKEN ->
            "Google did not return a lasting sign-in. Try again, and allow access when asked."
        SignInFailure.NETWORK -> "Could not reach Google. Check the connection and try again."
        SignInFailure.REFUSED_BY_GOOGLE -> "Google refused the sign-in."
        SignInFailure.BROWSER -> "Latch could not open a browser to sign in."
    }

    private fun openInBrowser(url: String): Boolean = runCatching {
        if (!Desktop.isDesktopSupported()) return false
        Desktop.getDesktop().browse(URI(url))
        true
    }.getOrDefault(false)

    private fun shutDown() {
        // The hotkey registration lives as long as its child process, so this is what gives
        // the combination back to the rest of the desktop.
        hotkey?.close()
        window?.close()
        tray?.close()
    }
}

fun main() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        System.err.println("Latch for Windows needs Windows: FR-302's hotkey and FR-303's recogniser are both Win32.")
        exitProcess(1)
    }
    SwingUtilities.invokeLater { Latch.start() }
}
