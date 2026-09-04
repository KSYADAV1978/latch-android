package com.latch.desktop.ui

import com.latch.core.model.InboxStatus
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

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

/**
 * One row of the tray menu, or the rule between two groups.
 *
 * **Status and action are different kinds of row, and the type says so** (SRS 1.82). A menu
 * whose every line looks alike leaves the user to work out which of them do anything, and the
 * dogfooding finding that produced this type was exactly that: "3 waiting in the Inbox" had
 * opened the Inbox since the day it was written, and nothing about it suggested a press would.
 *
 * So being clickable and being an action are separate facts here. A [Status] carries an optional
 * [id]: present, the row is dimmed *and* offers itself; absent, it is information and pressing
 * it does nothing.
 */
sealed interface TrayItem {
    /** Null for a rule between groups. */
    val label: String? get() = null

    /** What pressing the row does, or null where pressing it does nothing. */
    val id: TrayAction? get() = null

    data class Action(
        override val label: String,
        override val id: TrayAction,
        val enabled: Boolean = true,
    ) : TrayItem

    data class Status(override val label: String, override val id: TrayAction? = null) : TrayItem

    object Separator : TrayItem
}

enum class TrayAction { CAPTURE, INBOX, RECIPES, SIGN_IN, SIGN_OUT, RETRY, SETTINGS, QUIT }

/**
 * The menu, as a pure function.
 *
 * The tray is the only surface this application has when no capture is open, so what it says is
 * the whole of the user's information about whether Latch is working. That makes it worth
 * testing, and testing it means deciding it here rather than inside a Swing callback — which on
 * this client is the part no JVM test reaches, and which has now produced five defects of one
 * shape.
 *
 * **The grouping is load-bearing rather than decorative.** Something to do, then how things
 * stand, then the windows, then leaving: four groups and three rules between them.
 */
fun trayMenu(model: TrayModel): List<TrayItem> = buildList {
    // FR-213's reading, one client over (SRS 1.82). This reads the clipboard and does **not**
    // synthesise a copy: pressing a tray row means the window holding the selection has just
    // lost focus, so a synthesised Ctrl+C would reach the wrong window — or a terminal, where
    // it is not a copy at all. The hotkey keeps the selection path, where focus is right by
    // construction.
    add(TrayItem.Action(DesktopStrings.TRAY_CAPTURE, TrayAction.CAPTURE))

    add(TrayItem.Separator)

    // Said rather than offered. The shortcut is how this application is meant to be used, and a
    // menu is where somebody who has not learnt it will be looking.
    add(TrayItem.Status(DesktopStrings.TRAY_HOTKEY_HINT.replace("%s", model.hotkeyLabel)))

    // The account line is always status, and is pressable exactly when there is something to do
    // about it. Signing out is not that something — it is an act with a consequence, so it is an
    // action of its own below rather than a press hidden behind a line that reads as
    // information.
    when {
        // Not "Sign in", which would offer something that cannot work. FR-001's Desktop client
        // is missing and the menu says so rather than failing on the press.
        !model.configured -> add(TrayItem.Status(DesktopStrings.TRAY_NO_CLIENT))
        model.signedInAs == null ->
            add(TrayItem.Status(DesktopStrings.TRAY_NOT_SIGNED_IN, TrayAction.SIGN_IN))

        else ->
            add(TrayItem.Status(DesktopStrings.TRAY_SIGNED_IN.replace("%s", model.signedInAs)))
    }

    if (model.pending > 0 || model.givenUp > 0) {
        // FR-806's count, where the Android home screen shows one. A queue working through items
        // silently is indistinguishable from one that has stopped, so it says which.
        //
        // A given-up entry is counted separately and named, because it is the one state the user
        // has to do something about: it has stopped retrying and will stay stopped until they
        // ask. It is never deleted — the entry holds a capture that exists nowhere else.
        val label = when {
            model.givenUp > 0 && model.pending > 0 ->
                DesktopStrings.TRAY_QUEUE_BOTH
                    .replace("%1", model.pending.toString())
                    .replace("%2", model.givenUp.toString())

            model.givenUp > 0 ->
                DesktopStrings.TRAY_QUEUE_STUCK.replace("%s", model.givenUp.toString())

            else ->
                DesktopStrings.TRAY_QUEUE_WAITING.replace("%s", model.pending.toString())
        }
        add(TrayItem.Status(label, TrayAction.RETRY))
    }

    // FR-704, and it is deliberately below FR-806's line: a queued capture is on its way to
    // Google and a held one is waiting on the user, so the one the user can do nothing about
    // comes first and neither is ever shown at zero.
    inboxCountLabel(InboxStatus(due = model.inbox, snoozed = 0, unreadable = 0))
        ?.let { add(TrayItem.Status(it, TrayAction.INBOX)) }

    add(TrayItem.Separator)
    add(TrayItem.Action(DesktopStrings.TRAY_RECIPES, TrayAction.RECIPES))
    add(TrayItem.Action(DesktopStrings.TRAY_SETTINGS, TrayAction.SETTINGS))
    if (model.configured && model.signedInAs != null) {
        add(TrayItem.Action(DesktopStrings.TRAY_SIGN_OUT, TrayAction.SIGN_OUT))
    }

    add(TrayItem.Separator)
    add(TrayItem.Action(DesktopStrings.TRAY_QUIT, TrayAction.QUIT))
}

/**
 * FR-301: Latch as a tray application.
 *
 * There is no main window, deliberately. The application is a hotkey and a popup; a window that
 * existed only to be minimised would be a second thing to close.
 *
 * **The menu is Swing rather than `java.awt.PopupMenu`** (SRS 1.82). AWT draws that one itself,
 * at a size it chooses, and exposes no font, no renderer and no styling — so at 200% display
 * scaling it came out at roughly half size, and the font it picked had no glyph for the ellipsis
 * in "Settings…", which rendered as a box in the only surface this application has. Neither is
 * reachable from inside that class, which is why the whole menu moved rather than being patched.
 */
class LatchTray(private val onAction: (TrayAction) -> Unit) : AutoCloseable {

    private var icon: TrayIcon? = null
    private var model: TrayModel? = null

    /**
     * The window a tray menu has to belong to.
     *
     * A `JPopupMenu` needs an invoker and a tray icon is not a component. Without a focused
     * owner the menu draws but never dismisses — a click elsewhere leaves it on screen, which is
     * the focus quirk this arrangement exists to avoid. So a one-pixel utility window is put at
     * the pointer, given focus, and disposed of when the menu closes.
     */
    private var invoker: JWindow? = null

    val isSupported: Boolean get() = SystemTray.isSupported()

    fun install(model: TrayModel): Boolean {
        if (!SystemTray.isSupported()) return false
        val tray = SystemTray.getSystemTray()
        val image = latchIcon(tray.trayIconSize.width.coerceAtLeast(16))

        val trayIcon = TrayIcon(image, "Latch — " + model.hotkeyLabel).apply {
            isImageAutoSize = true
        }
        // Deliberately no `popupMenu`: setting one gives Windows the AWT menu back on
        // right-click, beside ours. And deliberately no ActionListener — that fires on a
        // double-click and captured, which cannot work from the tray for the reason `trayMenu`
        // records, and which would now conflict with a left-click that opens the menu.
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(event: MouseEvent) = showMenu(event)
        })

        tray.add(trayIcon)
        icon = trayIcon
        this.model = model
        return true
    }

    /**
     * The menu is built when it is opened, not when the model changes.
     *
     * The old menu was rebuilt on every update and handed to AWT; this one is a function of
     * whatever the model holds at the moment of the press, which is one fewer thing that can be
     * stale. That matters here specifically: on this client a screen showing a model's earlier
     * value is the defect shape that has now appeared five times.
     */
    fun update(model: TrayModel) {
        this.model = model
        icon?.toolTip = "Latch — " + model.hotkeyLabel
    }

    /** NFR-303: a failure the user can act on, where there is no window to put it in. */
    fun say(title: String, message: String, kind: TrayIcon.MessageType = TrayIcon.MessageType.INFO) {
        icon?.displayMessage(title, message, kind)
    }

    /**
     * Both buttons open the menu.
     *
     * Windows has no single convention for a left-click on a tray icon; it is "the primary
     * thing, or the menu". Latch's primary thing is a capture, and a capture wants the selection
     * in the window the user has just clicked away from — so the menu is the honest answer for
     * both buttons. Opening the Inbox on a left-click was considered and rejected: FR-704
     * requires zero to be silent, zero is the steady state, and a gesture that opens an empty
     * window is that requirement broken one gesture over.
     */
    private fun showMenu(event: MouseEvent) {
        val current = model ?: return
        SwingUtilities.invokeLater { present(current, event.xOnScreen, event.yOnScreen) }
    }

    private fun present(model: TrayModel, x: Int, y: Int) {
        closeInvoker()

        val menu = JPopupMenu()
        trayMenu(model).forEach { item ->
            when (item) {
                is TrayItem.Separator -> menu.addSeparator()
                is TrayItem.Action -> menu.add(
                    JMenuItem(item.label).apply {
                        isEnabled = item.enabled
                        addActionListener { onAction(item.id) }
                    }
                )

                is TrayItem.Status -> menu.add(statusItem(item))
            }
        }

        val window = JWindow().apply {
            type = Window.Type.UTILITY
            isAlwaysOnTop = true
            setSize(1, 1)
            setLocation(x, y)
            addWindowFocusListener(object : WindowAdapter() {
                // Clicking away from a tray menu should close it, and this window losing focus
                // is the only signal for that.
                override fun windowLostFocus(event: WindowEvent) {
                    menu.isVisible = false
                }
            })
        }
        invoker = window
        window.isVisible = true
        window.toFront()
        window.requestFocus()

        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) =
                SwingUtilities.invokeLater(::closeInvoker)

            override fun popupMenuCanceled(event: PopupMenuEvent) = Unit
        })
        menu.show(window, 0, 0)
    }

    /**
     * A status row: dimmed, and pressable only where pressing it does something.
     *
     * The dimming is what separates "how things stand" from "something you can do". The chevron
     * puts back the one thing dimming takes away — the hint that this particular line is worth
     * pressing — which is the dogfooding finding this whole row type comes from.
     */
    private fun statusItem(status: TrayItem.Status): JMenuItem = JMenuItem(
        if (status.id == null) status.label else status.label + "   ›"
    ).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
            ?: UIManager.getColor("MenuItem.disabledForeground")
            ?: Color.GRAY
        if (status.id == null) {
            // Not `isEnabled = false`, which would grey it a second time and take it out of
            // keyboard traversal in a way that reads as broken. It simply does nothing.
            isFocusable = false
        } else {
            addActionListener { onAction(status.id) }
        }
    }

    private fun closeInvoker() {
        invoker?.let {
            it.isVisible = false
            it.dispose()
        }
        invoker = null
    }

    override fun close() {
        closeInvoker()
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
