/*
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
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.setEmptyState
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.MpySourceIconProvider
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * @authors Lukas Kremla
 */
private data class FlashParameters(
    var flashingProject: Boolean,
    var selectedPaths: MutableList<String>,
    var resetOnSuccess: Boolean,
    var switchToReplOnSuccess: Boolean,
    var alwaysUseFTP: Boolean,
    var synchronize: Boolean,
    var excludePaths: Boolean,
    var excludedPaths: MutableList<String>
)

/**
 * @authors Lukas Kremla
 */
private data class SourceItem(
    private val project: Project,
    val path: String
) {
    val virtualFile: VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)

    val displayText: String = virtualFile?.let { thisFile ->
        project.guessProjectDir()?.let { projectDir ->
            VfsUtil.getRelativePath(thisFile, projectDir)
        } ?: thisFile.name
    } ?: path
}

/**
 * @authors Lukas Kremla
 */
class MpyFlashConfigurationEditor(private val project: Project, private val config: MpyFlashConfiguration) : SettingsEditor<MpyFlashConfiguration>() {
    val settings = project.service<MpySettingsService>()

    private val parameters = with(config.options) {
        FlashParameters(
            flashingProject = flashingProject,
            selectedPaths = selectedPaths.toMutableList(),
            resetOnSuccess = resetOnSuccess,
            switchToReplOnSuccess = switchToReplOnSuccess,
            alwaysUseFTP = alwaysUseFTP,
            synchronize = synchronize,
            excludePaths = excludePaths,
            excludedPaths = excludedPaths.toMutableList()
        )
    }

    private fun validateMicroPythonPath(path: String): String? {
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

        // A just-in-case limit
        if (path.length > 256) {
            return "Path is too long (maximum 256 characters)"
        }

        if (path.isEmpty()) {
            return "Path can't be empty!"
        }

        return null
    }

    private fun normalizeMicroPythonPath(path: String): String {
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

    private val excludedPathsTable = JBTable().apply {
        emptyText.text = "No excluded paths"
        model = DefaultTableModel(0, 1)
        setShowGrid(false)
        tableHeader = null
        preferredScrollableViewportSize = Dimension(500, 100)
    }

    // TODO: Switch to using a TableView
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
                val validationResult = validateMicroPythonPath(textField.text)

                if (validationResult == null) {
                    val normalizedPath = normalizeMicroPythonPath(textField.text)

                    model.addRow(arrayOf(normalizedPath))
                    sortDefaultTableModel(model)
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
                val validationResult = validateMicroPythonPath(textField.text)

                if (validationResult == null) {
                    val normalizedPath = normalizeMicroPythonPath(textField.text)

                    model.setValueAt(normalizedPath, selectedRow, 0)
                    sortDefaultTableModel(model)
                    dialog.dialogWrapper.close(0)
                } else {
                    dialog.setErrorText(validationResult)
                }
            }

            dialog.show()
        }
        .createPanel()

    private fun sourcesTable(emptyText: String) = TableView<SourceItem>().apply {
        val column = object : ColumnInfo<SourceItem, String>("Source Path") {
            override fun valueOf(item: SourceItem) = item.displayText

            override fun getRenderer(item: SourceItem) = DefaultTableCellRenderer().apply {
                icon = IconLoader.getIcon("icons/MpySource.svg", MpySourceIconProvider::class.java)
            }
        }

        model = ListTableModel<SourceItem>(column)
        tableHeader = null
        setShowGrid(false)
        setEmptyState(emptyText)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        preferredScrollableViewportSize = Dimension(250, 150)
    }

    private val availableSourcesTable = sourcesTable("No available MicroPython sources")
    private val selectedSourcesTable = sourcesTable("No selected MicroPython sources")

    private lateinit var configurationPanel: DialogPanel
    private lateinit var synchronizeCheckbox: Cell<JBCheckBox>
    private lateinit var excludePathsCheckbox: Cell<JBCheckBox>

    private lateinit var useSelectedPathsRadioButton: Cell<JBRadioButton>

    override fun createEditor(): JComponent {
        configurationPanel = panel {
            buttonsGroup {
                row {
                    label("Type: ")
                    radioButton("Project")
                        .bindSelected(parameters::flashingProject)
                        .applyToComponent {
                            addActionListener {
                                if (config.isGeneratedName) {
                                    config.suggestedName()
                                }
                            }
                        }
                    useSelectedPathsRadioButton = radioButton("Selected")
                        .bindSelected({ !parameters.flashingProject }, { parameters.flashingProject = !it })
                        .applyToComponent {
                            addActionListener {
                                if (config.isGeneratedName) {
                                    config.suggestedName()
                                }
                            }
                        }
                }
            }

            panel {
                row {
                    cell(JBScrollPane(availableSourcesTable))
                        .label("Available sources:", LabelPosition.TOP)
                        .align(AlignX.FILL)

                    panel {
                        row {
                            button("→") {
                                // Move to selected
                                transferSourceItem(availableSourcesTable, selectedSourcesTable)
                            }
                        }
                        row {
                            button("←") {
                                // Move from selected
                                transferSourceItem(selectedSourcesTable, availableSourcesTable)
                            }
                        }
                    }

                    cell(JBScrollPane(selectedSourcesTable))
                        .label("Selected sources:", LabelPosition.TOP)
                        .align(AlignX.FILL)
                }
            }.visibleIf(useSelectedPathsRadioButton.selected)

            row {
                checkBox("Reset on success")
                    .bindSelected(parameters::resetOnSuccess)
            }

            row {
                checkBox("Switch to REPL tab on success")
                    .bindSelected(parameters::switchToReplOnSuccess)
            }

            row {
                checkBox("Always use FTP")
                    .bindSelected(parameters::alwaysUseFTP)
                    .gap(RightGap.SMALL)

                cell(JBLabel(AllIcons.General.Information).apply {
                    toolTipText = "Make sure to setup wifi credentials in the plugin settings"
                })
            }

            row {
                synchronizeCheckbox = checkBox("Synchronize")
                    .bindSelected(parameters::synchronize)
                    .gap(RightGap.SMALL)

                cell(JBLabel(AllIcons.General.Information).apply {
                    toolTipText = "Synchronize device file system to only contain flashed files (deletes empty folders)"
                })
            }

            row {
                excludePathsCheckbox = checkBox("Exclude selected paths")
                    .bindSelected(parameters::excludePaths)
                    .gap(RightGap.SMALL)

                cell(JBLabel(AllIcons.General.Information).apply {
                    toolTipText = "Exclude listed on-device paths from synchronization"
                })
            }.visibleIf(synchronizeCheckbox.selected)

            indent {
                row {
                    cell(excludedPathsTableWithToolbar)
                        .label("Excluded paths:", LabelPosition.TOP)
                }
            }.visibleIf(excludePathsCheckbox.selected)
        }

        return configurationPanel
    }

    override fun applyEditorTo(runConfiguration: MpyFlashConfiguration) {
        configurationPanel.apply()

        with(parameters) {
            val selectedSourcesListTableModel = selectedSourcesTable.listTableModel

            selectedPaths = selectedSourcesListTableModel.items
                .map { it.path }
                .toMutableList()

            excludedPaths.clear()

            val excludedPathsModel = excludedPathsTable.model as DefaultTableModel
            var i = 0
            while (i < excludedPathsModel.rowCount) {
                excludedPaths.add(excludedPathsModel.getValueAt(i, 0).toString())
                i++
            }

            runConfiguration.saveOptions(
                flashingProject = flashingProject,
                selectedPaths = selectedPaths,
                resetOnSuccess = resetOnSuccess,
                switchToReplOnSuccess = switchToReplOnSuccess,
                alwaysUseFTP = alwaysUseFTP,
                synchronize = synchronize,
                excludePaths = excludePaths,
                excludedPaths = excludedPaths
            )
        }
    }

    override fun resetEditorFrom(runConfiguration: MpyFlashConfiguration) {
        val projectMpySourcesPaths = settings.state.mpySourcePaths
        val configurationMpySourcesPaths = runConfiguration.options.selectedPaths

        val availableSourcesListTableModel = availableSourcesTable.listTableModel
        val selectedSourcesListTableModel = selectedSourcesTable.listTableModel

        availableSourcesListTableModel.items = projectMpySourcesPaths
            .subtract(configurationMpySourcesPaths)
            .mapNotNull { LocalFileSystem.getInstance().findFileByPath(it)?.path }
            .map { SourceItem(project, it) }
            .sortedBy { it.displayText }

        selectedSourcesListTableModel.items = configurationMpySourcesPaths
            .map { SourceItem(project, it) }
            .sortedBy { it.displayText }


        val excludedPathsModel = excludedPathsTable.model as DefaultTableModel
        excludedPathsModel.rowCount = 0
        runConfiguration.options.excludedPaths.forEach { path ->
            excludedPathsModel.addRow(arrayOf(path))
        }

        sortDefaultTableModel(excludedPathsModel)

        configurationPanel.reset()
    }

    private fun transferSourceItem(sourceTable: TableView<SourceItem>, destinationTable: TableView<SourceItem>) {
        val selectedRow = sourceTable.selectedRow

        if (selectedRow == -1) return

        val selectedItem = sourceTable.listTableModel.items[selectedRow]

        val newSelectedSources = sourceTable.listTableModel.items.toMutableList()
        newSelectedSources.remove(selectedItem)

        sourceTable.listTableModel.items = newSelectedSources.sortedBy { it.displayText }

        val newAvailableSources = destinationTable.items.toMutableList()
        newAvailableSources.add(selectedItem)

        destinationTable.listTableModel.items = newAvailableSources.sortedBy { it.displayText }
    }

    private fun sortDefaultTableModel(model: DefaultTableModel) {
        val paths = mutableListOf<String>()

        var i = 0
        while (i < model.rowCount) {
            paths.add(model.getValueAt(i, 0).toString())
            i++
        }

        model.rowCount = 0

        paths
            .sortedBy { it }
            .forEach { path ->
                model.addRow(arrayOf(path))
            }
    }
}
