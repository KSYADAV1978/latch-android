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
import com.latch.desktop.save.DesktopSaver
import com.latch.desktop.save.IcsFile
import com.latch.desktop.save.DesktopSetup
import com.latch.desktop.save.SaveFailure
import com.latch.desktop.save.SaveResult
import com.latch.desktop.save.SetupResult
import com.latch.desktop.save.toWireDestination
import com.latch.desktop.store.DefaultsStore
import com.latch.desktop.store.SecretFile
import com.latch.desktop.store.latchDataDirectory
import com.latch.desktop.ui.CaptureWindow
import com.latch.desktop.ui.DesktopStrings
import com.latch.desktop.ui.LatchTray
import com.latch.desktop.ui.TrayAction
import com.latch.desktop.ui.TrayModel
import com.latch.desktop.ui.messageFor
import com.latch.desktop.ui.popupModel
import com.latch.google.TokenProvider
import com.latch.google.fetchPrimaryAccount
import com.latch.google.googleCalendarApi
import com.latch.google.googleTasksApi
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import com.latch.wire.DraftResult
import com.latch.wire.draftItems
import kotlinx.coroutines.runBlocking
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
    private val defaultsStore by lazy { DefaultsStore(secrets) }

    /**
     * The bridge from this client's sign-in to the shared Google client.
     *
     * `invalidate` drops the cached access token so the next call refreshes. It does not
     * discard the refresh token: only Google saying the grant is gone does that, and
     * `DesktopAuth` is where that decision lives.
     */
    private val tokens = object : TokenProvider {
        override suspend fun accessToken(): String =
            auth.accessToken() ?: throw IllegalStateException("not signed in")

        override suspend fun invalidate(token: String) = auth.dropCachedAccessToken()
    }

    private val calendarApi by lazy { googleCalendarApi(tokens) }
    private val tasksApi by lazy { googleTasksApi(tokens) }
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
        signedInAs = if (auth.isSignedIn) defaultsStore.read()?.email ?: "your Google account" else null,
        configured = auth.isConfigured,
        pending = 0,
    )

    private fun onTrayAction(action: TrayAction) = when (action) {
        TrayAction.CAPTURE -> SwingUtilities.invokeLater(::capture)
        TrayAction.SIGN_IN -> signIn()
        TrayAction.SIGN_OUT -> {
            // NFR-205's local half only: this forgets the sign-in on this machine and
            // leaves everything already in the user's Google account alone. Revoking the
            // grant is a different action and is owed.
            auth.forget()
            defaultsStore.clear()
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
                    onSave = { ticked, titles ->
                        save(outcome.capture, result, context, ticked, titles)
                    },
                    onExport = { ticked, titles ->
                        export(outcome.capture, result, context, ticked, titles)
                    },
                    onClose = { window = null },
                )
                window = opened
                opened.show(popupModel(outcome.capture, result, selected, LocalDate.now()))
            }
        }
    }

    /**
     * FR-801 to FR-803, on the client `:app` uses.
     *
     * **What is deliberately not here, and is named rather than silently missing**:
     * FR-806's queue, so a save made offline is reported instead of held; FR-804's
     * reschedule offer, which needs a surface to ask on; and FR-807's undo. Each is owed
     * and each is in the backlog. A Save that quietly did nothing would be worse than one
     * that explains itself.
     */
    private fun save(
        captured: com.latch.desktop.capture.DesktopCapture,
        result: ParseResult,
        context: ParseContext,
        selected: Set<Int>,
        titleOverrides: Map<Int, String>,
    ) {
        window?.close()
        if (!auth.isConfigured) {
            tray?.say("Latch", DesktopStrings.NOT_CONFIGURED, TrayIcon.MessageType.WARNING)
            return
        }
        if (!auth.isSignedIn) {
            tray?.say("Latch", DesktopStrings.NOT_SIGNED_IN, TrayIcon.MessageType.WARNING)
            return
        }

        Thread({
            val outcome = runCatching {
                runBlocking {
                    val defaults = defaultsStore.read() ?: return@runBlocking null
                    val chainId = java.util.UUID.randomUUID().toString()
                    val draft = draftItems(
                        captured = captured,
                        result = result,
                        context = context,
                        destination = defaults.toWireDestination(),
                        captureId = chainId,
                        chainId = chainId,
                        selected = selected,
                        titleOverrides = titleOverrides,
                    )
                    if (draft !is DraftResult.Ready) {
                        return@runBlocking SaveResult.Failed(SaveFailure.NOTHING_TO_WRITE)
                    }
                    DesktopSaver(calendarApi, tasksApi, context.zone.id)
                        .save(captured, result, draft.items, defaults, chainId)
                }
            }.getOrElse { SaveResult.Failed(SaveFailure.REFUSED, it.message.orEmpty()) }

            SwingUtilities.invokeLater {
                when (outcome) {
                    null -> tray?.say(
                        "Latch", "No calendar is chosen yet. Sign in again to set one up.",
                        TrayIcon.MessageType.WARNING,
                    )
                    is SaveResult.Written -> tray?.say(
                        "Latch",
                        if (outcome.count == 1) "Saved to Latch."
                        else outcome.count.toString() + " items saved to Latch.",
                    )
                    SaveResult.AlreadySaved ->
                        tray?.say("Latch", "Already saved. Nothing was written again.")
                    is SaveResult.Failed -> tray?.say(
                        "Latch", saveMessage(outcome.reason), TrayIcon.MessageType.WARNING,
                    )
                }
            }
        }, "latch-save").apply { isDaemon = true }.start()
    }

    /**
     * FR-1005, and it writes nothing to Google — which is the point of offering it beside Save
     * rather than after it. A user who wants the dates in their own calendar program, or who
     * has not signed in, still gets something out of a capture.
     */
    private fun export(
        captured: com.latch.desktop.capture.DesktopCapture,
        result: ParseResult,
        context: ParseContext,
        selected: Set<Int>,
        titleOverrides: Map<Int, String>,
    ) {
        window?.close()
        val chainId = java.util.UUID.randomUUID().toString()
        val draft = draftItems(
            captured = captured,
            result = result,
            context = context,
            // FR-1005 exports what was captured, not where it would have been filed, so the
            // ids here are placeholders and `exportItems` replaces them anyway.
            destination = com.latch.wire.WireDestination("export", "export"),
            captureId = chainId,
            chainId = chainId,
            selected = selected,
            titleOverrides = titleOverrides,
        )
        val file = (draft as? DraftResult.Ready)
            ?.let { IcsFile.write(it.items, chainId, context.zone.id) }

        if (file == null) {
            tray?.say("Latch", DesktopStrings.EXPORT_FAILED, TrayIcon.MessageType.WARNING)
            return
        }
        tray?.say("Latch", DesktopStrings.EXPORTED + " " + file.name)
    }

    private fun saveMessage(reason: SaveFailure) = when (reason) {
        SaveFailure.NOT_SIGNED_IN -> DesktopStrings.NOT_SIGNED_IN
        SaveFailure.NO_DESTINATION -> "No calendar is chosen yet."
        // FR-806's queue is owed on this client, so offline is reported rather than held —
        // and it says why, because a failure with no reason is what NFR-303 forbids.
        SaveFailure.OFFLINE -> "No connection, and this build cannot hold a capture until there is one."
        SaveFailure.REFUSED -> "Google refused the write."
        SaveFailure.NOTHING_TO_WRITE -> DesktopStrings.NOTHING_TICKED
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
                    SignInOutcome.Succeeded -> chooseDestination()
                    is SignInOutcome.Failed -> tray?.say(
                        "Latch", signInMessage(outcome.reason), TrayIcon.MessageType.WARNING,
                    )
                }
            }
        }, "latch-signin").apply { isDaemon = true }.start()
    }

    /**
     * FR-901 and FR-104, narrowed: reuse the account's Latch calendar or make one.
     *
     * Not FR-100's setup wizard, which asks which mode and which calendar. There is no
     * screen to ask on yet, so this takes the Option B default and says what it did. The
     * FR-900 destination picker is owed on this client.
     */
    private fun chooseDestination() {
        Thread({
            val outcome = runCatching {
                runBlocking {
                    val token = auth.accessToken() ?: return@runBlocking null
                    val account = fetchPrimaryAccount(token)
                    DesktopSetup(calendarApi, tasksApi).chooseDestination(account.email)
                }
            }.getOrNull()

            SwingUtilities.invokeLater {
                when (outcome) {
                    is SetupResult.Ready -> {
                        defaultsStore.write(outcome.defaults)
                        tray?.update(model())
                        tray?.say(
                            "Latch",
                            "Signed in. Captures go to your " + outcome.defaults.calendarName + " calendar.",
                        )
                    }
                    else -> tray?.say(
                        "Latch",
                        "Signed in, but Latch could not read your calendars. Try again later.",
                        TrayIcon.MessageType.WARNING,
                    )
                }
            }
        }, "latch-setup").apply { isDaemon = true }.start()
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
