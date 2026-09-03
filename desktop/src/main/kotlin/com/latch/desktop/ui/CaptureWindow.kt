package com.latch.desktop.ui

import com.latch.desktop.save.undoSecondsLeft
import com.latch.wire.DateSuggestion
import com.latch.wire.SheetEdits
import com.latch.wire.dateFrom

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.time.LocalDate
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
import javax.swing.JSpinner
import javax.swing.SpinnerDateModel
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
    private val onSave: (Set<Int>, SheetEdits) -> Unit,
    /** FR-1005. Nothing is written to Google by this, which is the point of offering it. */
    private val onExport: (Set<Int>, SheetEdits) -> Unit,
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

    /**
     * FR-506 row 3, FR-507 and FR-509b's answers, held here and applied in **one** place.
     *
     * The window keeps the edits and re-asks [renderer] for a model whenever they change, which
     * is how the badge, the date line, the blocker and the write all read the same rows. A
     * screen that applied an override itself would eventually show one thing and save another —
     * the divergence a confirmation screen exists to make impossible, and the reason `:app`
     * derives `parsed.withEdits(edits, today)` exactly once.
     */
    private var edits = SheetEdits()
    private var renderer: ((SheetEdits, Set<Int>) -> PopupModel)? = null
    private var selected: MutableSet<Int> = mutableSetOf()

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
            onExport(ticked(), withTypedTitle())
        }
        closeButton.addActionListener { dismiss() }

        val south = JPanel(BorderLayout(0, 6))
        message.border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
        south.add(message, BorderLayout.NORTH)
        south.add(actions, BorderLayout.SOUTH)
        content.add(south, BorderLayout.SOUTH)
        showConfirmActions()

        save.addActionListener { onSave(ticked(), withTypedTitle()) }
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

    private fun ticked(): Set<Int> = checkboxes.filterValues { it.isSelected }.keys.toSet()

    private fun withTypedTitle(): SheetEdits {
        val typed = titleField.text.trim()
        // Blank, or unchanged, is not an override. A user who cleared the field mid-edit has
        // not asked for an item with no name — the same reading FR-509b takes on the phone.
        if (typed.isEmpty() || typed == derivedTitle) return edits
        return edits.copy(titleOverrides = checkboxes.keys.associateWith { typed })
    }

    /**
     * Re-derives everything from the edits, so no two parts of the sheet can disagree.
     *
     * It renders with [withTypedTitle] rather than the stored edits, so a title corrected in the
     * field shows on the rows immediately. `derivedTitle` is deliberately **not** updated here:
     * it is what the parser produced, set once, and comparing against it is how FR-509b tells a
     * correction from an untouched title.
     */
    private fun redraw() {
        val model = renderer?.invoke(withTypedTitle(), selected) ?: return
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
    }

    fun show(initiallyTicked: Set<Int>, render: (SheetEdits, Set<Int>) -> PopupModel) {
        renderer = render
        edits = SheetEdits()
        selected = initiallyTicked.toMutableSet()
        // FR-511: every row starts ticked, which is what the requirement asks the checkboxes to
        // begin as.
        derivedTitle = render(edits, selected).title
        titleField.text = derivedTitle
        redraw()
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

        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                val box = JCheckBox(row.whenLine, row.checked).apply {
                    // The badge and the date read as one label to a screen reader, which is
                    // what NFR-401 needs: two adjacent controls saying "EVENT" and "8 Sep 2027"
                    // are read as two unrelated things.
                    getAccessibleContext().accessibleName =
                        row.badge + ", " + row.whenLine + ", " + row.title
                    addItemListener {
                        if (isSelected) selected += row.index else selected -= row.index
                        redraw()
                    }
                }
                checkboxes[row.index] = box
                add(badgeButton(row))
                add(box)
            }
        )

        add(
            JLabel(row.title).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(0, 26, 0, 0)
                font = font.deriveFont(Font.PLAIN, 12f)
            }
        )

        // §8.1's cost, and FR-510's reason where the badge is fixed. Both read *before* the
        // press: this is the one place a user action deliberately discards something they
        // wrote, so the sentence has to be there while the badge still says what it says now.
        val hint = row.overrideCost ?: if (!row.canOverride) DesktopStrings.PAST_CANNOT_BE_EVENT else null
        hint?.let { add(smallNote(it)) }
        row.note?.let { add(smallNote(it)) }

        if (row.needsDate) add(datePicker(row))
    }

    /**
     * FR-507's single control.
     *
     * The badge **is** the control, which satisfies "a single control" literally and puts the
     * override where FR-508 already requires the classification to be shown. It was computed by
     * the model and drawn as dead text until 3 Sep 2026 — the capability existed and nothing
     * rendered it, which reads as done and is worse than absent.
     */
    private fun badgeButton(row: CaptureRow): JButton = JButton(row.badge).apply {
        font = font.deriveFont(Font.BOLD, 11f)
        margin = java.awt.Insets(2, 8, 2, 8)
        isFocusPainted = true
        isEnabled = row.canOverride
        toolTipText =
            if (row.canOverride) "Switch between event and to-do"
            else DesktopStrings.PAST_CANNOT_BE_EVENT
        getAccessibleContext().accessibleName = row.badge + ", press to switch"
        addActionListener {
            edits = edits.copy(
                typeOverrides = edits.typeOverrides + (row.index to row.otherType),
            )
            redraw()
        }
    }

    /**
     * FR-506 row 3: a time with no day.
     *
     * **The chips are on screen without a press**, because the requirement says the picker
     * "opens automatically with suggestion chips" — a row that only says "pick a day" and hides
     * the way to do it is the state this client was in until now.
     *
     * **Nothing is pre-selected.** The spinner shows today because a spinner must show
     * something, and it does not count as a choice until `Use` is pressed: design principle 1
     * forbids the *app* choosing a date, and a control the user operates is the user choosing
     * one.
     */
    private fun datePicker(row: CaptureRow): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(2, 22, 0, 0)

        listOf(
            DesktopStrings.TODAY to DateSuggestion.TODAY,
            DesktopStrings.TOMORROW to DateSuggestion.TOMORROW,
            DesktopStrings.IN_A_WEEK to DateSuggestion.IN_A_WEEK,
        ).forEach { (label, suggestion) ->
            add(
                JButton(label).apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    margin = java.awt.Insets(1, 6, 1, 6)
                    addActionListener { assign(row.index, suggestion.dateFrom(LocalDate.now())) }
                }
            )
        }

        val spinner = JSpinner(SpinnerDateModel()).apply {
            editor = JSpinner.DateEditor(this, "d MMM yyyy")
            preferredSize = Dimension(120, preferredSize.height)
            getAccessibleContext().accessibleName = "Other date"
        }
        add(spinner)
        add(
            JButton(DesktopStrings.OTHER_DATE).apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                margin = java.awt.Insets(1, 6, 1, 6)
                addActionListener {
                    val picked = (spinner.value as java.util.Date).toInstant()
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    assign(row.index, picked)
                }
            }
        )
    }

    private fun assign(index: Int, date: LocalDate) {
        edits = edits.copy(assignedDates = edits.assignedDates + (index to date))
        // Completing a row ticks it. A picker that filled the date and left the row unticked
        // would feel inert — the user has just said when the thing is.
        selected += index
        redraw()
    }

    private fun smallNote(text: String): JLabel = JLabel(text).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 26, 0, 0)
        font = font.deriveFont(Font.ITALIC, 11f)
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
