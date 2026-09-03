package com.latch.desktop.ui

import com.latch.desktop.save.undoSecondsLeft

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * FR-304's capture popup.
 *
 * **Swing rather than a modern toolkit**, and the reason is in this file rather than only in
 * the build script: FR-304 requires the popup to be *fully keyboard-operable* and NFR-401
 * requires a screen reader to work, and Swing reaches Windows' accessibility layer through the
 * Java Access Bridge without anything being added. It also costs nothing against NFR-103's
 * 80 MB, where a Compose Desktop build would bring tens of megabytes of Skia natives.
 *
 * **This class draws and nothing else.** Every decision about what appears is `popupModel`'s,
 * which a JVM test calls; the same discipline that put `saveBlocker` and `writeDecision` in
 * pure functions on Android, and for the same reason — a screen that decides something is a
 * thing no test can check.
 *
 * The action row is deliberately outside the scroll. On Android the confirmation sheet grew
 * past the glass and put Save below it on a device at a large font scale, and a scroll alone
 * would have left Save reachable only after everything above it.
 */
class CaptureWindow(
    private val onSave: (Set<Int>, Map<Int, String>) -> Unit,
    /** FR-1005. Nothing is written to Google by this, which is the point of offering it. */
    private val onExport: (Set<Int>, Map<Int, String>) -> Unit,
    private val onClose: () -> Unit,
) {
    private val dialog = JDialog(null as java.awt.Frame?, "Latch", false)
    private val rowsPanel = JPanel()
    private val titleField = JTextField()
    private val summary = JLabel()
    private val blocker = JLabel()
    private val save = JButton(DesktopStrings.SAVE)
    private val exportButton = JButton(DesktopStrings.EXPORT)
    private val closeButton = JButton(DesktopStrings.CLOSE)
    private val update = JButton(DesktopStrings.UPDATE)
    private val createNew = JButton(DesktopStrings.CREATE_NEW)
    private val undo = JButton()
    private val message = JLabel()
    private val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
    private var countdown: javax.swing.Timer? = null
    private val checkboxes = mutableMapOf<Int, JCheckBox>()

    init {
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.isAlwaysOnTop = true

        rowsPanel.layout = BoxLayout(rowsPanel, BoxLayout.Y_AXIS)

        titleField.font = titleField.font.deriveFont(Font.BOLD, 14f)
        // FR-509b: the title is corrected before anything is written. A plain field rather
        // than a button-then-field, because on a desktop the keyboard is already the way in
        // and a Tab reaches it — the two-tap arrangement on the phone exists to keep a
        // software keyboard from covering the sheet, which is not a problem here.
        titleField.getAccessibleContext().accessibleName = "Title"

        blocker.foreground = java.awt.Color(0xB0, 0x30, 0x30)

        val content = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
        }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titleField)
            add(Box.createVerticalStrut(6))
            add(summary.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        }
        content.add(header, BorderLayout.NORTH)

        content.add(
            JScrollPane(rowsPanel).apply {
                border = BorderFactory.createEmptyBorder()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                preferredSize = Dimension(460, 220)
            },
            BorderLayout.CENTER,
        )

        exportButton.addActionListener {
            onExport(checkboxes.filterValues { it.isSelected }.keys.toSet(), titleOverride())
        }
        closeButton.addActionListener { dismiss() }

        val south = JPanel(BorderLayout(0, 6))
        message.border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
        south.add(message, BorderLayout.NORTH)
        south.add(actions, BorderLayout.SOUTH)
        content.add(south, BorderLayout.SOUTH)
        showConfirmActions()

        save.addActionListener {
            onSave(
                checkboxes.filterValues { it.isSelected }.keys.toSet(),
                titleOverride(),
            )
        }
        // FR-304: Enter saves and Escape dismisses, from anywhere in the window.
        dialog.rootPane.defaultButton = save
        dialog.rootPane.registerKeyboardAction(
            { dismiss() },
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )

        dialog.contentPane = content
    }

    private var derivedTitle: String = ""

    private fun titleOverride(): Map<Int, String> {
        val typed = titleField.text.trim()
        // Blank, or unchanged, is not an override. A user who cleared the field mid-edit has
        // not asked for an item with no name — the same reading FR-509b takes on the phone.
        if (typed.isEmpty() || typed == derivedTitle) return emptyMap()
        return checkboxes.keys.associateWith { typed }
    }

    fun show(model: PopupModel) {
        derivedTitle = model.title
        titleField.text = model.title
        summary.text = model.summary
        blocker.text = model.blocker.orEmpty()
        save.isEnabled = model.canSave

        rowsPanel.removeAll()
        checkboxes.clear()
        model.rows.forEach { row ->
            rowsPanel.add(rowPanel(row))
            rowsPanel.add(Box.createVerticalStrut(4))
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()

        dialog.pack()
        dialog.location = nearCursor(dialog.size)
        dialog.isVisible = true
        // The title is focused **and selected**, so correcting it is type-then-Enter with no
        // click at all, and accepting it is Enter on its own.
        //
        // FR-509 cannot be fixed by a better derivation — measured, see SRS 1.64 — so the
        // answer is to make FR-509b's correction the path of least resistance rather than a
        // button to notice and reach for. Selecting is what turns two actions into none: the
        // user either types over it or does not.
        //
        // Wiping it by accident costs nothing, which is what makes selecting safe here: FR-509b
        // already reads a blank field as "no override" and falls back to the derived title, so
        // the worst case is undone by clearing.
        SwingUtilities.invokeLater {
            titleField.requestFocusInWindow()
            titleField.selectAll()
        }
    }

    private fun rowPanel(row: CaptureRow): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(4, 0, 4, 0)

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            val box = JCheckBox(row.badge + "   " + row.whenLine, row.checked).apply {
                // The badge and the date read as one label to a screen reader, which is what
                // NFR-401 needs: two adjacent controls saying "EVENT" and "8 Sep 2027" are
                // read as two unrelated things.
                getAccessibleContext().accessibleName = row.badge + ", " + row.whenLine + ", " + row.title
            }
            checkboxes[row.index] = box
            add(box)
        }
        add(top)

        add(JLabel(row.title).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 26, 0, 0)
            font = font.deriveFont(Font.PLAIN, 12f)
        })

        row.note?.let {
            add(JLabel(it).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(0, 26, 0, 0)
                font = font.deriveFont(Font.ITALIC, 11f)
            })
        }
    }

    private fun showConfirmActions() {
        actions.removeAll()
        actions.add(blocker)
        actions.add(exportButton)
        actions.add(closeButton)
        actions.add(save)
        dialog.rootPane.defaultButton = save
        relayout()
    }

    /**
     * FR-804's offer.
     *
     * **Save is taken away, and that is the requirement rather than tidiness.** The question on
     * screen is now update-or-create, and Save is not one of the two answers; leaving it up
     * would offer a third door that writes without answering. `saveIsOffered` says the same
     * thing on the phone.
     *
     * **Neither answer is the default.** `defaultButton` is cleared, so Enter does nothing here
     * — a reschedule moves an item the user already has, and a keystroke they did not aim
     * should not be able to choose which way. Escape still closes, and closing writes nothing.
     */
    fun showRescheduleOffer(text: String, onUpdate: () -> Unit, onCreateNew: () -> Unit) {
        message.text = "<html><body style='width:400px'>" + escapeHtml(text) + "</body></html>"
        actions.removeAll()
        actions.add(closeButton)
        actions.add(createNew)
        actions.add(update)
        for (listener in update.actionListeners) update.removeActionListener(listener)
        for (listener in createNew.actionListeners) createNew.removeActionListener(listener)
        update.addActionListener { onUpdate() }
        createNew.addActionListener { onCreateNew() }
        dialog.rootPane.defaultButton = null
        relayout()
    }

    /**
     * FR-807's ten seconds, on the window that made the save.
     *
     * The window stays open for the length of the offer and **closes itself when the offer
     * lapses**, which is the behaviour the phone has: the capture window is the offer, so it
     * outliving the offer would leave a dead thing on screen.
     */
    fun showSaved(text: String, savedAt: java.time.Instant, onUndo: () -> Unit, onLapse: () -> Unit) {
        message.text = "<html><body style='width:400px'>" + escapeHtml(text) + "</body></html>"
        actions.removeAll()
        actions.add(closeButton)
        actions.add(undo)
        for (listener in undo.actionListeners) undo.removeActionListener(listener)
        undo.addActionListener {
            countdown?.stop()
            onUndo()
        }
        dialog.rootPane.defaultButton = null

        countdown?.stop()
        val tick = javax.swing.Timer(250) {
            val left = undoSecondsLeft(savedAt, java.time.Instant.now())
            undo.text = undoLabel(left)
            if (left <= 0) {
                countdown?.stop()
                onLapse()
                dismiss()
            }
        }
        undo.text = undoLabel(undoSecondsLeft(savedAt, java.time.Instant.now()))
        tick.start()
        countdown = tick
        relayout()
    }

    /** A terminal message with nothing left to answer — "already saved", or a failure. */
    fun showOutcome(text: String) {
        countdown?.stop()
        message.text = "<html><body style='width:400px'>" + escapeHtml(text) + "</body></html>"
        actions.removeAll()
        actions.add(closeButton)
        dialog.rootPane.defaultButton = closeButton
        relayout()
    }

    private fun relayout() {
        actions.revalidate()
        actions.repaint()
        dialog.pack()
        dialog.location = nearCursor(dialog.size)
    }

    private fun dismiss() {
        countdown?.stop()
        countdown = null
        dialog.isVisible = false
        dialog.dispose()
        onClose()
    }

    fun close() = dismiss()

    /** Swing renders a label as HTML, so text the user captured must not be read as markup. */
    private fun escapeHtml(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

/**
 * FR-304: near the cursor, and wholly on the screen the cursor is on.
 *
 * The second half is the part worth writing down. A popup placed at the pointer runs off the
 * edge whenever the pointer is near one, and a window half off the bottom of a screen is a
 * window whose Save button cannot be clicked — the desktop version of the defect the Android
 * sheet had at a large font scale. It is clamped to the screen's usable bounds, which excludes
 * the taskbar.
 */
internal fun nearCursor(size: Dimension, pointer: Point? = null, bounds: Rectangle? = null): Point {
    val at = pointer ?: runCatching { MouseInfo.getPointerInfo().location }.getOrNull() ?: Point(200, 200)
    val screen = bounds ?: usableBoundsFor(at)
    val x = (at.x + 12).coerceIn(screen.x, maxOf(screen.x, screen.x + screen.width - size.width))
    val y = (at.y + 12).coerceIn(screen.y, maxOf(screen.y, screen.y + screen.height - size.height))
    return Point(x, y)
}

private fun usableBoundsFor(at: Point): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val device = environment.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(at) }
        ?: environment.defaultScreenDevice
    val configuration = device.defaultConfiguration
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    val bounds = configuration.bounds
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}
