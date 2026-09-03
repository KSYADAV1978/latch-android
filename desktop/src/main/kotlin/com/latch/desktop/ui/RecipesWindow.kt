package com.latch.desktop.ui

import com.latch.core.model.Direction
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants

/**
 * FR-603's editor: create, edit, duplicate and delete the user's own recipes.
 *
 * **It draws and collects characters.** `applyRecipeForm` says what a typed recipe means and
 * `recipeProblemText` says why it means nothing; the shadowing rule that makes editing a
 * built-in a *copy* is `:recipes`', compiled by both clients.
 *
 * **The list and the editor are one window, not two.** FR-603's four verbs are all operations
 * on a list, and a modal editor over a modal list on a desktop is two things to close for one
 * change of mind. The list is on the left and whatever is being edited is below it.
 */
class RecipesWindow(
    private val onSave: (RecipeForm) -> Unit,
    private val onDuplicate: (Recipe) -> Unit,
    private val onDelete: (Recipe) -> Unit,
    private val onNew: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val frame = JFrame(DesktopStrings.RECIPES_TITLE)
    private val list = JPanel()
    private val editor = JPanel()
    private val message = JLabel()

    private val name = JTextField(24)
    private var steps = mutableListOf<RecipeStepForm>()
    private var editing: Recipe? = null
    private var stored: List<Recipe> = emptyList()

    init {
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        list.layout = BoxLayout(list, BoxLayout.Y_AXIS)
        editor.layout = BoxLayout(editor, BoxLayout.Y_AXIS)

        val content = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(14, 16, 12, 16)
        }
        content.add(
            JScrollPane(JPanel(BorderLayout(0, 10)).apply {
                add(list, BorderLayout.NORTH)
                add(editor, BorderLayout.CENTER)
            }).apply {
                border = BorderFactory.createEmptyBorder()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBar.unitIncrement = 16
                preferredSize = Dimension(600, 560)
            },
            BorderLayout.CENTER,
        )
        content.add(
            JPanel(BorderLayout(0, 4)).apply {
                add(message, BorderLayout.NORTH)
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                        add(JButton(DesktopStrings.RECIPE_NEW).apply { addActionListener { onNew() } })
                        add(JButton(DesktopStrings.SETTINGS_CLOSE).apply { addActionListener { close() } })
                    },
                    BorderLayout.SOUTH,
                )
            },
            BorderLayout.SOUTH,
        )

        frame.rootPane.registerKeyboardAction(
            { close() },
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
        frame.contentPane = content
    }

    fun show(all: List<Recipe>, own: List<Recipe>) {
        render(all, own)
        if (!frame.isVisible) {
            frame.pack()
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
        }
        frame.toFront()
        frame.requestFocus()
    }

    fun render(all: List<Recipe>, own: List<Recipe>) {
        stored = own
        list.removeAll()
        all.forEach { recipe -> list.add(rowFor(recipe)) }
        list.revalidate()
        list.repaint()
        // The recipe being edited may have been rewritten under us by the save that got us
        // here, so it is refreshed from the list rather than kept.
        editing?.let { current -> all.firstOrNull { it.id == current.id }?.let(::edit) } ?: clearEditor()
    }

    fun say(text: String) {
        message.text = text
    }

    private fun rowFor(recipe: Recipe): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        add(JLabel(recipe.name).apply { font = font.deriveFont(Font.BOLD, 12f) })
        add(
            JLabel(recipeOrigin(recipe, stored)).apply {
                font = font.deriveFont(Font.ITALIC, 11f)
                foreground = java.awt.Color(0x55, 0x55, 0x55)
            }
        )
        add(small(DesktopStrings.RECIPE_EDIT) { edit(recipe) })
        add(small(DesktopStrings.RECIPE_DUPLICATE) { onDuplicate(recipe) })
        // FR-603's delete is two actions behind one button — removing the user's own, and
        // restoring a shipped one they had edited — so the button says which it is.
        val label = deleteLabelFor(recipe, stored)
        if (label == DesktopStrings.RECIPE_RESTORE || !recipe.builtIn) {
            add(small(label) { onDelete(recipe) })
        }
    }

    private fun small(label: String, action: () -> Unit) = JButton(label).apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        margin = java.awt.Insets(1, 6, 1, 6)
        addActionListener { action() }
    }

    private fun clearEditor() {
        editing = null
        editor.removeAll()
        editor.revalidate()
        editor.repaint()
    }

    /**
     * FR-603's edit.
     *
     * **A built-in opens in the editor exactly as the user's own does**, and saving stores a
     * copy carrying its id. Nothing here knows that; `editableCopyOf` does, one layer up, and
     * both clients compile it.
     */
    fun edit(recipe: Recipe) {
        editing = recipe
        val form = formOf(recipe)
        name.text = form.name
        steps = form.steps.toMutableList()
        drawEditor()
    }

    private fun drawEditor() {
        val current = editing ?: return
        editor.removeAll()
        editor.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color(0xDD, 0xDD, 0xDD)),
            BorderFactory.createEmptyBorder(10, 0, 0, 0),
        )
        editor.add(
            JLabel(DesktopStrings.RECIPE_NAME).apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        )
        name.alignmentX = JComponent.LEFT_ALIGNMENT
        name.maximumSize = Dimension(Int.MAX_VALUE, name.preferredSize.height)
        name.getAccessibleContext().accessibleName = DesktopStrings.RECIPE_NAME
        editor.add(name)
        editor.add(Box.createVerticalStrut(8))

        steps.forEachIndexed { index, step -> editor.add(stepPanel(index, step)) }

        editor.add(
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                add(
                    JButton(DesktopStrings.RECIPE_ADD_STEP).apply {
                        addActionListener {
                            steps.add(
                                RecipeStepForm(
                                    offset = "0",
                                    unit = OffsetUnit.CALENDAR_DAYS,
                                    direction = Direction.BEFORE,
                                    type = ItemType.TASK,
                                    titleTemplate = DesktopStrings.RECIPE_NEW_STEP,
                                    reminders = "",
                                )
                            )
                            drawEditor()
                        }
                    }
                )
                add(
                    JButton(DesktopStrings.SETTINGS_SAVE).apply {
                        addActionListener { onSave(RecipeForm(current.id, typedName(), steps.toList())) }
                    }
                )
            }
        )
        editor.revalidate()
        editor.repaint()
    }

    private fun stepPanel(index: Int, step: RecipeStepForm): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(6, 0, 6, 0)

        val offset = JTextField(step.offset, 4).apply {
            getAccessibleContext().accessibleName = DesktopStrings.RECIPE_STEP_OFFSET
        }
        val unit = JComboBox(arrayOf(DesktopStrings.RECIPE_CALENDAR_DAYS, DesktopStrings.RECIPE_WORKING_DAYS)).apply {
            selectedIndex = if (step.unit == OffsetUnit.WORKING_DAYS) 1 else 0
        }
        val direction = JComboBox(arrayOf(DesktopStrings.RECIPE_BEFORE, DesktopStrings.RECIPE_AFTER)).apply {
            selectedIndex = if (step.direction == Direction.AFTER) 1 else 0
        }
        val type = JComboBox(arrayOf(DesktopStrings.BADGE_EVENT, DesktopStrings.BADGE_TASK)).apply {
            selectedIndex = if (step.type == ItemType.TASK) 1 else 0
        }
        val title = JTextField(step.titleTemplate, 28)
        val reminders = JTextField(step.reminders, 12)

        fun commit() {
            steps[index] = RecipeStepForm(
                offset = offset.text,
                unit = if (unit.selectedIndex == 1) OffsetUnit.WORKING_DAYS else OffsetUnit.CALENDAR_DAYS,
                direction = if (direction.selectedIndex == 1) Direction.AFTER else Direction.BEFORE,
                type = if (type.selectedIndex == 1) ItemType.TASK else ItemType.EVENT,
                titleTemplate = title.text,
                reminders = reminders.text,
            )
        }
        listOf(offset, title, reminders).forEach { field ->
            field.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(event: java.awt.event.FocusEvent) = commit()
            })
        }
        listOf(unit, direction, type).forEach { box -> box.addActionListener { commit() } }

        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                add(type)
                add(offset)
                add(unit)
                add(direction)
                add(
                    JButton(DesktopStrings.RECIPE_REMOVE_STEP).apply {
                        font = font.deriveFont(Font.PLAIN, 11f)
                        margin = java.awt.Insets(1, 6, 1, 6)
                        addActionListener {
                            steps.removeAt(index)
                            drawEditor()
                        }
                    }
                )
            }
        )
        add(hint(DesktopStrings.RECIPE_STEP_TITLE))
        title.alignmentX = JComponent.LEFT_ALIGNMENT
        title.maximumSize = Dimension(Int.MAX_VALUE, title.preferredSize.height)
        title.getAccessibleContext().accessibleName = DesktopStrings.RECIPE_STEP_TITLE
        add(title)
        add(hint(DesktopStrings.RECIPE_STEP_REMINDERS))
        reminders.alignmentX = JComponent.LEFT_ALIGNMENT
        reminders.maximumSize = Dimension(Int.MAX_VALUE, reminders.preferredSize.height)
        reminders.getAccessibleContext().accessibleName = DesktopStrings.RECIPE_STEP_REMINDERS
        add(reminders)
    }

    private fun typedName(): String = name.text

    private fun hint(text: String) = JLabel(text).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = java.awt.Color(0x55, 0x55, 0x55)
    }

    fun close() {
        frame.isVisible = false
        frame.dispose()
        onClose()
    }
}
