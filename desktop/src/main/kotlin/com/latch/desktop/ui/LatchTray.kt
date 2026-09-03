package com.latch.desktop.ui

import com.latch.desktop.inbox.InboxStatus
import java.awt.Color
import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/** What the tray menu offers, decided here so the menu only renders it. */
data class TrayModel(
    val hotkeyLabel: String,
    val signedInAs: String?,
    val configured: Boolean,
    /** FR-806: captures held locally and not yet in the account. */
    val pending: Int,
    /** FR-806: entries retries have stopped for. They stay, and a tap revives them. */
    val givenUp: Int = 0,
    /**
     * FR-704: captures held in the Inbox and waiting on the user, snoozed rows excluded.
     *
     * Zero shows **nothing at all** rather than "0 waiting" — the requirement's "shall not nag"
     * in the one place it can be got wrong for free. `inboxCountLabel` is that rule.
     */
    val inbox: Int = 0,
)

/** One entry of the tray menu. */
data class TrayEntry(val label: String, val id: TrayAction, val enabled: Boolean = true)

enum class TrayAction { CAPTURE, INBOX, RECIPES, SIGN_IN, SIGN_OUT, RETRY, SETTINGS, QUIT }

/**
 * The menu, as a pure function.
 *
 * The tray is the only surface this application has when no capture is open, so what it says
 * is the whole of the user's information about whether Latch is working. That makes it worth
 * testing, and testing it means deciding it here rather than inside an AWT callback.
 */
fun trayMenu(model: TrayModel): List<TrayEntry> = buildList {
    add(TrayEntry("Capture now (" + model.hotkeyLabel + ")", TrayAction.CAPTURE))
    when {
        !model.configured ->
            // Not "Sign in", which would offer something that cannot work. FR-001's Desktop
            // client is missing and the menu says so rather than failing on the tap.
            add(TrayEntry("No Google client configured", TrayAction.SIGN_IN, enabled = false))
        model.signedInAs == null -> add(TrayEntry("Sign in to Google…", TrayAction.SIGN_IN))
        else -> add(TrayEntry("Signed in as " + model.signedInAs, TrayAction.SIGN_OUT))
    }
    if (model.pending > 0 || model.givenUp > 0) {
        // FR-806's count, where the Android home screen shows one. A queue working through
        // items silently is indistinguishable from one that has stopped, so it says which.
        //
        // A given-up entry is counted separately and named, because it is the one state the
        // user has to do something about: it has stopped retrying and will stay stopped
        // until they ask. It is never deleted — the entry holds a capture that exists
        // nowhere else.
        val label = when {
            model.givenUp > 0 && model.pending > 0 ->
                model.pending.toString() + " waiting, " + model.givenUp + " stuck — retry now"
            model.givenUp > 0 -> model.givenUp.toString() + " stuck — retry now"
            else -> model.pending.toString() + " waiting to be written — retry now"
        }
        add(TrayEntry(label, TrayAction.RETRY))
    }
    // FR-704, and it is deliberately below FR-806's line: a queued capture is on its way to
    // Google and a held one is waiting on the user, so the one the user can do nothing about
    // comes first and neither is ever shown at zero.
    inboxCountLabel(InboxStatus(due = model.inbox, snoozed = 0, unreadable = 0))
        ?.let { add(TrayEntry(it, TrayAction.INBOX)) }
    add(TrayEntry("Recipes…", TrayAction.RECIPES))
    add(TrayEntry("Settings…", TrayAction.SETTINGS))
    add(TrayEntry("Quit Latch", TrayAction.QUIT))
}

/**
 * FR-301: Latch as a tray application.
 *
 * There is no main window, deliberately. The application is a hotkey and a popup; a window
 * that existed only to be minimised would be a second thing to close.
 */
class LatchTray(private val onAction: (TrayAction) -> Unit) : AutoCloseable {

    private var icon: TrayIcon? = null

    val isSupported: Boolean get() = SystemTray.isSupported()

    fun install(model: TrayModel): Boolean {
        if (!SystemTray.isSupported()) return false
        val tray = SystemTray.getSystemTray()
        val image = latchIcon(tray.trayIconSize.width.coerceAtLeast(16))

        val trayIcon = TrayIcon(image, "Latch — " + model.hotkeyLabel).apply {
            isImageAutoSize = true
            // A double-click on the icon captures, which is the one thing anybody wants from
            // it often enough to reach for without a menu.
            addActionListener { onAction(TrayAction.CAPTURE) }
        }
        trayIcon.popupMenu = buildMenu(model)
        tray.add(trayIcon)
        icon = trayIcon
        return true
    }

    fun update(model: TrayModel) {
        icon?.let {
            it.popupMenu = buildMenu(model)
            it.toolTip = "Latch — " + model.hotkeyLabel
        }
    }

    /** NFR-303: a failure the user can act on, where there is no window to put it in. */
    fun say(title: String, message: String, kind: TrayIcon.MessageType = TrayIcon.MessageType.INFO) {
        icon?.displayMessage(title, message, kind)
    }

    private fun buildMenu(model: TrayModel) = PopupMenu().apply {
        trayMenu(model).forEach { entry ->
            add(
                MenuItem(entry.label).apply {
                    isEnabled = entry.enabled
                    addActionListener { onAction(entry.id) }
                }
            )
        }
    }

    override fun close() {
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
    }
}

/**
 * The tray icon, drawn rather than shipped as a file.
 *
 * A tray icon has to be sharp at whatever size Windows asks for, which on a mixed-DPI desktop
 * is not knowable at build time, and shipping one bitmap per size is four files to keep in
 * step. Drawing it means one function and no resources — and this is a latch: a rounded
 * rectangle with a bar across it.
 */
internal fun latchIcon(size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

    val inset = size / 8
    graphics.color = Color(0x2E, 0x5B, 0xFF)
    graphics.fillRoundRect(inset, inset, size - inset * 2, size - inset * 2, size / 3, size / 3)

    graphics.color = Color.WHITE
    graphics.font = Font(Font.SANS_SERIF, Font.BOLD, (size * 0.62).toInt().coerceAtLeast(8))
    val metrics = graphics.fontMetrics
    val letter = "L"
    graphics.drawString(
        letter,
        (size - metrics.stringWidth(letter)) / 2,
        (size - metrics.height) / 2 + metrics.ascent,
    )
    graphics.dispose()
    return image
}
