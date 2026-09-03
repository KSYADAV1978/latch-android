package com.latch.desktop.ui

import com.latch.desktop.store.DesktopSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants

/**
 * FR-1000 on Windows.
 *
 * **A window, like the Inbox and unlike the capture popup**, for the same reason: this is a
 * surface a user works in rather than one that appears and goes.
 *
 * **It draws and collects characters; it decides nothing.** `applyForm` says what a form means
 * and `settingsProblemText` says why it means nothing, both in a file a JVM test calls. The
 * window's only judgement is which fields exist, and even the list of things this client cannot
 * yet offer comes from `settingsGaps` — named on screen rather than absent, because SRS 1.65
 * records what an absent control costs: the model reads as though the work were done.
 */
class SettingsWindow(
    private val onSave: (SettingsForm) -> Unit,
    private val onClose: () -> Unit,
) {
    private val frame = JFrame(DesktopStrings.SETTINGS_TITLE)

    private val hotkey = JTextField(16)
    private val dayFirst = JRadioButton(DesktopStrings.SETTINGS_DAY_FIRST)
    private val monthFirst = JRadioButton(DesktopStrings.SETTINGS_MONTH_FIRST)
    private val duration = JTextField(6)
    private val reminders = JTextField(16)
    private val threshold = JTextField(6)
    private val timeZone = JTextField(20)
    private val days = WEEK_IN_ORDER.associateWith { JCheckBox(it.label()) }
    private val message = JLabel()

    init {
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE

        val fields = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(14, 16, 8, 16)

            add(field(DesktopStrings.SETTINGS_HOTKEY, hotkey, DesktopStrings.SETTINGS_HOTKEY_HINT))
            add(dateOrder())
            add(field(DesktopStrings.SETTINGS_DURATION, duration))
            add(field(DesktopStrings.SETTINGS_REMINDERS, reminders, DesktopStrings.SETTINGS_REMINDERS_HINT))
            add(field(DesktopStrings.SETTINGS_THRESHOLD, threshold, DesktopStrings.SETTINGS_THRESHOLD_HINT))
            add(workingWeek())
            add(field(DesktopStrings.SETTINGS_TIME_ZONE, timeZone, DesktopStrings.SETTINGS_TIME_ZONE_HINT))
            add(gaps())
        }

        val content = JPanel(BorderLayout(0, 8))
        content.add(
            JScrollPane(fields).apply {
                border = BorderFactory.createEmptyBorder()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBar.unitIncrement = 16
                preferredSize = Dimension(520, 520)
            },
            BorderLayout.CENTER,
        )

        val save = JButton(DesktopStrings.SETTINGS_SAVE).apply { addActionListener { onSave(form()) } }
        val south = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createEmptyBorder(0, 16, 12, 16)
            add(message, BorderLayout.NORTH)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    add(JButton(DesktopStrings.SETTINGS_CLOSE).apply { addActionListener { close() } })
                    add(save)
                },
                BorderLayout.SOUTH,
            )
        }
        content.add(south, BorderLayout.SOUTH)

        // FR-304's keyboard rule, which is not really scoped to the capture popup: Enter saves,
        // Escape closes without saving.
        frame.rootPane.defaultButton = save
        frame.rootPane.registerKeyboardAction(
            { close() },
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
        frame.contentPane = content

        ButtonGroup().apply { add(dayFirst); add(monthFirst) }
    }

    fun show(settings: DesktopSettings) {
        fill(formOf(settings))
        message.text = ""
        if (!frame.isVisible) {
            frame.pack()
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
        }
        frame.toFront()
        frame.requestFocus()
    }

    fun fill(form: SettingsForm) {
        hotkey.text = form.hotkey
        dayFirst.isSelected = form.dayFirst
        monthFirst.isSelected = !form.dayFirst
        duration.text = form.defaultDurationMinutes
        reminders.text = form.defaultReminderMinutes
        threshold.text = form.confidencePercent
        timeZone.text = form.timeZone
        days.forEach { (day, box) -> box.isSelected = day in form.workingDays }
    }

    fun say(text: String) {
        message.text = "<html><body style='width:460px'>" + text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>"
    }

    private fun form() = SettingsForm(
        hotkey = hotkey.text,
        dayFirst = dayFirst.isSelected,
        defaultDurationMinutes = duration.text,
        defaultReminderMinutes = reminders.text,
        confidencePercent = threshold.text,
        workingDays = days.filterValues { it.isSelected }.keys,
        timeZone = timeZone.text,
    )

    private fun field(label: String, input: JTextField, hint: String? = null): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            add(JLabel(label).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(3))
            input.alignmentX = JComponent.LEFT_ALIGNMENT
            input.maximumSize = Dimension(Int.MAX_VALUE, input.preferredSize.height)
            // NFR-401: a bare text box beside a label is two unrelated things to a screen
            // reader. The Java Access Bridge reads this.
            input.getAccessibleContext().accessibleName = label
            add(input)
            hint?.let { add(hintLabel(it)) }
        }

    private fun dateOrder(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
        add(JLabel(DesktopStrings.SETTINGS_DATE_ORDER).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        // FR-504 shown by example rather than by name. "DD/MM" is jargon; "5 September" is the
        // question the user is actually being asked.
        add(dayFirst.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        add(monthFirst.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
    }

    private fun workingWeek(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
        add(JLabel(DesktopStrings.SETTINGS_WORKING_WEEK).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                days.forEach { (_, box) -> add(box) }
            }
        )
    }

    private fun gaps(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color(0xDD, 0xDD, 0xDD)),
            BorderFactory.createEmptyBorder(10, 0, 0, 0),
        )
        add(
            JLabel(DesktopStrings.SETTINGS_NOT_YET).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                font = font.deriveFont(Font.BOLD, 11f)
            }
        )
        settingsGaps().forEach { add(hintLabel("• " + it)) }
    }

    private fun hintLabel(text: String) = JLabel("<html><body style='width:440px'>" + text + "</body></html>")
        .apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            font = font.deriveFont(Font.ITALIC, 11f)
            foreground = java.awt.Color(0x55, 0x55, 0x55)
        }

    fun close() {
        frame.isVisible = false
        frame.dispose()
        onClose()
    }
}

private fun DayOfWeek.label(): String = getDisplayName(TextStyle.SHORT, Locale.getDefault())
