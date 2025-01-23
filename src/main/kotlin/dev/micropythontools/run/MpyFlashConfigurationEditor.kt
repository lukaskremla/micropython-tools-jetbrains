/*
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2024-2025 Lukas Kremla
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.micropythontools.run

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComponentWithBrowseButton
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.CheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ItemEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * @authors Lukas Kremla
 */
class MpyFlashConfigurationEditor(config: MpyFlashConfiguration) : SettingsEditor<MpyFlashConfiguration>() {
    private val pathField = TextFieldWithBrowseButton()
    private val resetOnSuccess = CheckBox("Reset on success", selected = false)
    private val runReplOnSuccess = CheckBox("Switch to REPL tab on success", selected = true)
    private val useFTP = CheckBox("Use FTP for file uploads", selected = false)
    private val synchronize = CheckBox("Synchronize", selected = false)
    private val excludePaths = CheckBox("Exclude selected paths", selected = false)

    private val ftpPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(useFTP)
        add(JBLabel(AllIcons.General.Information).apply {
            toolTipText = "Make sure to setup wifi credentials in the plugin settings"
        })
    }

    private val synchronizePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(synchronize)
        add(JBLabel(AllIcons.General.Information).apply {
            toolTipText = "Synchronize device file system to only contain flashed files (deletes empty folders)"
        })
    }

    private val excludePathsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(excludePaths)
        add(JBLabel(AllIcons.General.Information).apply {
            toolTipText = "Exclude listed on-device paths from synchronization"
        })
    }

    private val excludedPathsTable = JBTable().apply {
        emptyText.text = "No excluded paths"
        model = DefaultTableModel(0, 1)
        setShowGrid(false)
        tableHeader = null
    }

    private fun validatePath(path: String): String? {
        val forbiddenCharacters = listOf("<", ">", ":", "\"", "|", "?", "*")
        val foundForbiddenCharacters = mutableListOf<String>()

        forbiddenCharacters.forEach {
            if (path.contains(it)) {
                foundForbiddenCharacters.add(it)
            }
        }

        if (foundForbiddenCharacters.isNotEmpty()) {
            return "Found forbidden characters: $foundForbiddenCharacters"
        }

        // A just-in-case limit, to prevent over-inflating the synchronization script
        if (path.length > 256) {
            return "Path is too long (maximum 256 characters)"
        }

        if (path.isEmpty()) {
            return "Path can't be empty!"
        }

        return null
    }

    private fun normalizePath(path: String): String {
        var normalizedPath = path

        // Replace slash format to fit MicroPython file system
        normalizedPath = normalizedPath.replace("\\", "/")

        // Normalize input to remove potential redundant path elements
        normalizedPath = java.nio.file.Paths.get(normalizedPath).normalize().toString()

        // Ensure correct slash format again
        normalizedPath = normalizedPath.replace("\\", "/")

        normalizedPath = normalizedPath.trim()

        if (!path.startsWith("/")) {
            normalizedPath = "/${path}"
        }

        return normalizedPath
    }

    private val excludedPathsTableWithToolbar = ToolbarDecorator.createDecorator(excludedPathsTable)
        .setAddAction {
            val dialog = DialogBuilder(config.project)
            dialog.title("Add Excluded Path")


            val model = excludedPathsTable.model as DefaultTableModel

            val textField = JBTextField().apply {
                preferredSize = Dimension(300, preferredSize.height)
            }

            val panel = JPanel(BorderLayout())

            panel.add(JBLabel("Path: "), BorderLayout.WEST)
            panel.add((textField), BorderLayout.CENTER)

            dialog.centerPanel(panel)

            dialog.setOkOperation {
                val validationResult = validatePath(textField.text)

                if (validationResult == null) {
                    val normalizedPath = normalizePath(textField.text)

                    model.addRow(arrayOf(normalizedPath))
                    sortExcludedPathsTableModel(model)
                    dialog.dialogWrapper.close(0)
                } else {
                    dialog.setErrorText(validationResult)
                }
            }

            dialog.show()
        }
        .setRemoveAction {
            val selectedRow = excludedPathsTable.selectedRow
            if (selectedRow != -1) {
                val model = excludedPathsTable.model as DefaultTableModel

                val newSelectionRow = if (selectedRow < model.rowCount - 1) selectedRow else selectedRow - 1

                model.removeRow(selectedRow)

                if (model.rowCount > 0) {
                    excludedPathsTable.selectionModel.setSelectionInterval(newSelectionRow, newSelectionRow)
                    excludedPathsTable.requestFocus()
                }
            }
        }
        .setEditAction {
            val selectedRow = excludedPathsTable.selectedRow
            val model = excludedPathsTable.model as DefaultTableModel
            val currentText = model.getValueAt(selectedRow, 0).toString()

            val dialog = DialogBuilder(config.project)
            dialog.title("Edit Excluded Path")

            val textField = JBTextField(currentText).apply {
                preferredSize = Dimension(300, preferredSize.height)
            }

            val panel = JPanel(BorderLayout())
            panel.add(JBLabel("Path: "), BorderLayout.WEST)
            panel.add((textField), BorderLayout.CENTER)
            dialog.centerPanel(panel)

            dialog.setOkOperation {
                val validationResult = validatePath(textField.text)

                if (validationResult == null) {
                    val normalizedPath = normalizePath(textField.text)

                    model.setValueAt(normalizedPath, selectedRow, 0)
                    sortExcludedPathsTableModel(model)
                    dialog.dialogWrapper.close(0)
                } else {
                    dialog.setErrorText(validationResult)
                }
            }

            dialog.show()
        }
        .createPanel()

    private val excludedPathsTablePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val labelPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalStrut(JBUI.scale(15)))
            add(JBLabel("Excluded paths:"))
            add(Box.createHorizontalGlue())
            alignmentX = 0.0f
        }

        val indentedPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalStrut(JBUI.scale(25)))
            add(excludedPathsTableWithToolbar)
            alignmentX = 0.0f
        }

        add(labelPanel)
        add(Box.createVerticalStrut(JBUI.scale(5)))
        add(indentedPanel)
    }

    init {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, false).withTitle("Select Path")
        val pathListener = ComponentWithBrowseButton.BrowseFolderActionListener(
            pathField,
            config.project,
            descriptor,
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        )
        pathField.addActionListener(pathListener)

        excludePathsPanel.isVisible = synchronize.isSelected

        synchronize.addItemListener { e ->
            excludePathsPanel.isVisible = e.stateChange == ItemEvent.SELECTED
        }

        excludedPathsTablePanel.isVisible = excludePaths.isSelected

        excludePaths.addItemListener { e ->
            excludedPathsTablePanel.isVisible = e.stateChange == ItemEvent.SELECTED
        }
    }

    override fun createEditor(): JComponent =
        FormBuilder.createFormBuilder()
            .addTooltip("Leave path empty to upload the whole project")
            .addLabeledComponent("Path:", pathField)
            .addComponent(resetOnSuccess)
            .addComponent(runReplOnSuccess)
            .addComponent(ftpPanel)
            .addComponent(synchronizePanel)
            .addComponent(excludePathsPanel)
            .addComponent(excludedPathsTablePanel)
            .panel

    override fun applyEditorTo(runConfiguration: MpyFlashConfiguration) {
        val excludedPathsList = mutableListOf<String>()

        val model = excludedPathsTable.model as DefaultTableModel
        var i = 0
        while (i < model.rowCount) {
            excludedPathsList.add(model.getValueAt(i, 0).toString())
            i++
        }

        runConfiguration.saveOptions(
            path = pathField.text,
            runReplOnSuccess = runReplOnSuccess.isSelected,
            resetOnSuccess = resetOnSuccess.isSelected,
            useFTP = useFTP.isSelected,
            synchronize = synchronize.isSelected,
            excludePaths = excludePaths.isSelected,
            excludedPaths = excludedPathsList
        )
    }

    override fun resetEditorFrom(runConfiguration: MpyFlashConfiguration) {
        val options = runConfiguration.getOptionsObject()

        pathField.text = options.path ?: ""
        runReplOnSuccess.isSelected = options.runReplOnSuccess
        resetOnSuccess.isSelected = options.resetOnSuccess
        useFTP.isSelected = options.useFTP
        synchronize.isSelected = options.synchronize
        excludePaths.isSelected = options.excludePaths

        val model = excludedPathsTable.model as DefaultTableModel
        model.rowCount = 0
        options.excludedPaths.forEach { path ->
            model.addRow(arrayOf(path))
        }

        sortExcludedPathsTableModel(model)
    }

    private fun sortExcludedPathsTableModel(model: DefaultTableModel) {
        val excludedPaths = mutableListOf<String>()

        var i = 0
        while (i < model.rowCount) {
            excludedPaths.add(model.getValueAt(i, 0).toString())
            i++
        }

        model.rowCount = 0

        excludedPaths
            .sortedBy { it }
            .forEach { path ->
                model.addRow(arrayOf(path))
            }
    }
}
