package com.latch.desktop.ui

import com.latch.core.model.ItemType
import com.latch.wire.DateSuggestion
import com.latch.wire.dateFrom
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.time.LocalDate
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SpinnerDateModel

/**
 * FR-700's triage surface on Windows.
 *
 * **A window rather than a popup**, which is the one place this screen departs from everything
 * else in this client. The capture popup appears at the pointer and is gone in seconds; the
 * Inbox is a list a user sits with, may leave open, and returns to over days (FR-705). Placing
 * it at the cursor and keeping it always-on-top would make a working surface behave like an
 * interruption.
 *
 * **This class draws and nothing else.** Every sentence, badge and enabled control is
 * `inboxModel`'s, which a JVM test calls — the arrangement `CaptureWindow` and `popupModel`
 * already have, and for the reason this project keeps relearning: twice the untested thing was
 * the load-bearing thing, both times because it sat inside something a test could not build.
 *
 * FR-304's keyboard rule is not scoped to the capture popup in spirit, so it holds here too:
 * Tab reaches everything, Esc closes, and each row's controls carry accessible names for
 * NFR-401's screen reader.
 */
class InboxWindow(
    private val onAssignDate: (String, LocalDate) -> Unit,
    private val onEditTitle: (String, String) -> Unit,
    private val onOverrideType: (String, ItemType) -> Unit,
    private val onSave: (String) -> Unit,
    private val onSnooze: (String) -> Unit,
    private val onDiscard: (String) -> Unit,
    private val onClose: () -> Unit,
) {
    private val frame = JFrame(DesktopStrings.INBOX_TITLE)
    private val rowsPanel = JPanel()
    private val localOnly = JLabel()
    private val unreadable = JLabel()
    private val message = JLabel()

    init {
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        rowsPanel.layout = BoxLayout(rowsPanel, BoxLayout.Y_AXIS)

        val content = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
        }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            // FR-703's guarantee, at the top rather than in a tooltip. This list is the one
            // place in the client where a user could reasonably believe something is saved
            // when it is not, so the sentence that says otherwise leads.
            add(localOnly.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            add(unreadable.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        }
        content.add(header, BorderLayout.NORTH)
        content.add(
            JScrollPane(rowsPanel).apply {
                border = BorderFactory.createEmptyBorder()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBar.unitIncrement = 16
                preferredSize = Dimension(560, 420)
            },
            BorderLayout.CENTER,
        )
        content.add(message, BorderLayout.SOUTH)

        frame.rootPane.registerKeyboardAction(
            { close() },
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
        frame.contentPane = content
    }

    fun show(model: InboxModel) {
        render(model)
        if (!frame.isVisible) {
            frame.pack()
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
        }
        frame.toFront()
        frame.requestFocus()
    }

    /** Called after every FR-702 action, so the list and the row's own state cannot disagree. */
    fun render(model: InboxModel) {
        localOnly.text = model.localOnlyLine
        localOnly.font = localOnly.font.deriveFont(Font.ITALIC, 11f)
        unreadable.text = model.unreadableLine.orEmpty()
        unreadable.isVisible = model.unreadableLine != null
        unreadable.foreground = java.awt.Color(0xB0, 0x30, 0x30)

        rowsPanel.removeAll()
        if (model.rows.isEmpty()) {
            rowsPanel.add(
                JLabel(model.emptyLine.orEmpty()).apply {
                    alignmentX = JComponent.LEFT_ALIGNMENT
                    border = BorderFactory.createEmptyBorder(12, 2, 12, 2)
                }
            )
        }
        model.rows.forEach { row ->
            rowsPanel.add(rowPanel(row))
            rowsPanel.add(Box.createVerticalStrut(6))
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    fun say(text: String) {
        message.text = text
    }

    private fun rowPanel(row: InboxRow): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, java.awt.Color(0xDD, 0xDD, 0xDD)),
            BorderFactory.createEmptyBorder(8, 2, 10, 2),
        )

        // FR-702's edit, and FR-509b's control in the place the phone puts it too: a field the
        // user types in rather than a mode they enter. It commits on focus loss and on Enter,
        // never on every keystroke, so the store is not rewritten a letter at a time.
        val title = JTextField(row.title).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            getAccessibleContext().accessibleName = DesktopStrings.INBOX_TITLE_FIELD
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = JComponent.LEFT_ALIGNMENT
            addActionListener { onEditTitle(row.id, text) }
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(event: java.awt.event.FocusEvent) {
                    if (text != row.title) onEditTitle(row.id, text)
                }
            })
        }
        add(title)

        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                add(badge(row))
                add(JLabel(row.whenLine))
            }
        )

        add(note(row.excerpt))
        add(note(row.reason))
        add(note(row.capturedLine))
        // FR-705: surfaced, never deleted. A row that has waited a fortnight says so and is
        // still exactly where the user left it.
        row.agedLine?.let { add(note(it)) }
        row.overrideCost?.let { add(note(it)) }
        if (!row.canOverride) add(note(DesktopStrings.PAST_CANNOT_BE_EVENT))

        // FR-702's "assign a date", and FR-506 row 3's chips one screen over. Present without a
        // press, and nothing pre-selected: design principle 1 forbids the app choosing a date,
        // and a control the user operates is the user choosing one.
        if (row.needsDate) add(datePicker(row))

        add(actions(row))
    }

    private fun badge(row: InboxRow): JButton = JButton(row.badge).apply {
        font = font.deriveFont(Font.BOLD, 11f)
        margin = java.awt.Insets(2, 8, 2, 8)
        isEnabled = row.canOverride
        toolTipText =
            if (row.canOverride) "Switch between event and to-do"
            else DesktopStrings.PAST_CANNOT_BE_EVENT
        getAccessibleContext().accessibleName = row.badge + ", press to switch"
        addActionListener { onOverrideType(row.id, row.otherType) }
    }

    private fun datePicker(row: InboxRow): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(JLabel(DesktopStrings.INBOX_SET_DATE))
        listOf(
            DesktopStrings.TODAY to DateSuggestion.TODAY,
            DesktopStrings.TOMORROW to DateSuggestion.TOMORROW,
            DesktopStrings.IN_A_WEEK to DateSuggestion.IN_A_WEEK,
        ).forEach { (label, suggestion) ->
            add(
                JButton(label).apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    margin = java.awt.Insets(1, 6, 1, 6)
                    addActionListener { onAssignDate(row.id, suggestion.dateFrom(LocalDate.now())) }
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
                    onAssignDate(row.id, picked)
                }
            }
        )
    }

    /** FR-702's remaining three, in the requirement's own order. */
    private fun actions(row: InboxRow): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(
            JButton(DesktopStrings.INBOX_SAVE).apply {
                isEnabled = row.canSave
                // Says why rather than being dead: a disabled button with no reason beside it
                // is the state FR-506 row 3 was in on this client until SRS 1.65.
                toolTipText = if (row.canSave) null else DesktopStrings.INBOX_NEEDS_DATE
                addActionListener { onSave(row.id) }
            }
        )
        add(JButton(DesktopStrings.INBOX_SNOOZE).apply { addActionListener { onSnooze(row.id) } })
        add(JButton(DesktopStrings.INBOX_DISCARD).apply { addActionListener { onDiscard(row.id) } })
    }

    private fun note(text: String): JLabel = JLabel(text).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = java.awt.Color(0x55, 0x55, 0x55)
    }

    val isOpen: Boolean get() = frame.isVisible

    fun close() {
        frame.isVisible = false
        frame.dispose()
        onClose()
    }
}
