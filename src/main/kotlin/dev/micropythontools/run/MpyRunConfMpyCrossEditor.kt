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
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.setEmptyState
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.settings.isRunConfTargetPathValid
import dev.micropythontools.settings.normalizeMpyPath
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * @authors Lukas Kremla
 */
private data class CompileParameters(
    var uploadMode: Int,
    var selectedPaths: MutableList<String>,
    var path: String,
    var outputPath: String,
    var pathRelativeToOutput: String,
)

/**
 * @authors Lukas Kremla
 */
internal class MpyRunConfMpyCrossEditor(private val runConfiguration: MpyRunConfMpyCross) :
    SettingsEditor<MpyRunConfMpyCross>() {

    private val transferService = runConfiguration.project.service<MpyTransferService>()

    private val parameters = with(runConfiguration.options) {
        CompileParameters(
            uploadMode = uploadMode,
            selectedPaths = selectedPaths.toMutableList(),
            path = path ?: "",
            outputPath = outputPath ?: "${runConfiguration.project.guessProjectDir()?.path}/mpy-output",
            pathRelativeToOutput = pathRelativeToOutput ?: ""
        )
    }

    private fun sortSourceItemsTable(table: TableView<SourceItem>) {
        val sortedItems = table.listTableModel.items.toList().sortedBy { it.path }

        repeat(table.rowCount) {
            table.listTableModel.removeRow(0)
        }

        sortedItems.forEach {
            table.listTableModel.addRow(it)
        }
    }

    private fun setupTableSelectionListeners() {
        var isAdjusting = false

        availableSourcesTable.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !isAdjusting) {
                if (availableSourcesTable.selectedRow != -1) {
                    isAdjusting = true
                    selectedSourcesTable.clearSelection()
                    isAdjusting = false

                    selectedToAvailableButton.enabled(false)
                    availableToSelectedButton.enabled(true)
                } else {
                    availableToSelectedButton.enabled(false)
                }
            }
        }

        selectedSourcesTable.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !isAdjusting) {
                if (selectedSourcesTable.selectedRow != -1) {
                    isAdjusting = true
                    availableSourcesTable.clearSelection()
                    isAdjusting = false

                    availableToSelectedButton.enabled(false)
                    selectedToAvailableButton.enabled(true)
                } else {
                    selectedToAvailableButton.enabled(false)
                }
            }
        }
    }

    private fun moveActivelySelectedSourceItems(fromTable: TableView<SourceItem>, toTable: TableView<SourceItem>) {
        val itemToTransfer = fromTable.getRow(fromTable.selectedRow)

        if (itemToTransfer.isValid) toTable.listTableModel.addRow(itemToTransfer)

        fromTable.listTableModel.removeRow(fromTable.selectedRow)

        toTable.requestFocusInWindow()

        sortSourceItemsTable(fromTable)
        sortSourceItemsTable(toTable)
    }

    private fun sourcesTable(emptyText: String) = TableView<SourceItem>().apply {
        val column = object : ColumnInfo<SourceItem, String>("Source Path") {
            override fun valueOf(item: SourceItem) = item.displayText

            override fun getRenderer(item: SourceItem) = DefaultTableCellRenderer().apply {
                icon = item.icon
            }
        }

        model = ListTableModel<SourceItem>(column)
        tableHeader = null
        setShowGrid(false)
        setEmptyState(emptyText)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        preferredScrollableViewportSize = Dimension(250, 150)
    }

    private val availableSourcesTable = sourcesTable("No available MicroPython Sources Roots")
    private val selectedSourcesTable = sourcesTable("No selected MicroPython Sources Roots")

    private lateinit var configurationPanel: DialogPanel
    private lateinit var uploadProjectRadioButton: Cell<JBRadioButton>
    private lateinit var useSelectedPathsRadioButton: Cell<JBRadioButton>
    private lateinit var usePathRadiobutton: Cell<JBRadioButton>
    private lateinit var relativeToTextField: Cell<JBTextField>
    private lateinit var outputTextField: Cell<TextFieldWithBrowseButton>
    private lateinit var availableToSelectedButton: Cell<JButton>
    private lateinit var selectedToAvailableButton: Cell<JButton>

    override fun createEditor(): JComponent {
        setupTableSelectionListeners()

        configurationPanel = panel {
            buttonsGroup {
                row {
                    label("Type: ")
                    uploadProjectRadioButton = radioButton("Project")
                        .bindSelected({ parameters.uploadMode == 0 }, { if (it) parameters.uploadMode = 0 })
                        .applyToComponent {
                            addActionListener {
                                if (runConfiguration.isGeneratedName) {
                                    runConfiguration.suggestedName()
                                }
                            }
                        }
                        .comment("Learn about how compilation works <a href=\"https://github.com/lukaskremla/micropython-tools-jetbrains/blob/main/DOCUMENTATION.md#uploads\">here</a>")
                    useSelectedPathsRadioButton = radioButton("Selected MPY sources roots")
                        .bindSelected({ parameters.uploadMode == 1 }, { if (it) parameters.uploadMode = 1 })
                        .applyToComponent {
                            addActionListener {
                                if (runConfiguration.isGeneratedName) {
                                    runConfiguration.suggestedName()
                                }
                            }
                        }
                    usePathRadiobutton = radioButton("Custom path")
                        .bindSelected({ parameters.uploadMode == 2 }, { if (it) parameters.uploadMode = 2 })
                        .applyToComponent {
                            addActionListener {
                                if (runConfiguration.isGeneratedName) {
                                    runConfiguration.suggestedName()
                                }
                            }
                        }
                }
            }

            panel {
                row {
                    cell(JBScrollPane(availableSourcesTable))
                        .align(AlignX.FILL)
                        .label("Available MPY sources roots:", LabelPosition.TOP)
                        .resizableColumn()

                    panel {
                        row {
                            availableToSelectedButton = button("→") {
                                moveActivelySelectedSourceItems(availableSourcesTable, selectedSourcesTable)
                            }.enabled(false)
                        }
                        row {
                            selectedToAvailableButton = button("←") {
                                moveActivelySelectedSourceItems(selectedSourcesTable, availableSourcesTable)
                            }.enabled(false)
                        }
                    }

                    cell(JBScrollPane(selectedSourcesTable))
                        .align(AlignX.FILL)
                        .label("Selected MPY sources roots:", LabelPosition.TOP)
                        .resizableColumn()
                }
            }.visibleIf(useSelectedPathsRadioButton.selected)

            row("Source path: ") {
                textFieldWithBrowseButton(
                    FileChooserDescriptor(true, true, false, false, false, false).withTitle("Select Path"),
                    runConfiguration.project
                )
                    .widthGroup("TextFields")
                    .apply {
                        component.text = parameters.path
                        component.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updatePath()
                            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updatePath()
                            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updatePath()

                            private fun updatePath() {
                                parameters.path = component.text
                            }
                        })
                    }
            }.visibleIf(usePathRadiobutton.selected)

            row("Relative path: ") {
                relativeToTextField = textField()
                    .bindText(parameters::pathRelativeToOutput)
                    .widthGroup("TextFields")
                    .gap(RightGap.SMALL)
                    .validationInfo { field ->
                        val validationResult = isRunConfTargetPathValid(field.text)

                        if (validationResult != null) {
                            error(validationResult)
                        } else null
                    }
            }.visibleIf(usePathRadiobutton.selected)

            row("Output to: ") {
                outputTextField = textFieldWithBrowseButton(
                    FileChooserDescriptor(false, true, false, false, false, false).withTitle("Select Output Directory"),
                    runConfiguration.project
                )
                    .widthGroup("TextFields")
                    .columns(45)
                    .bindText(parameters::outputPath)
                    .gap(RightGap.SMALL)
                actionButton(object :
                    AnAction("Restore Default", "Restore default output directory", AllIcons.Actions.Rollback) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val defaultPath = "${runConfiguration.project.guessProjectDir()?.path}/mpy-output"
                        parameters.outputPath = defaultPath
                        outputTextField.component.textField.text = defaultPath
                    }
                })
            }
        }

        return configurationPanel
    }

    override fun applyEditorTo(runConfiguration: MpyRunConfMpyCross) {
        configurationPanel.apply()

        with(parameters) {
            selectedPaths.clear()

            val selectedSourcesTableItemsPaths = selectedSourcesTable.listTableModel.items.map { it.path }

            selectedPaths.addAll(selectedSourcesTableItemsPaths)

            val normalizedUploadToPath = normalizeMpyPath(pathRelativeToOutput, true)

            relativeToTextField.component.text = normalizedUploadToPath

            runConfiguration.saveOptions(
                uploadMode = uploadMode,
                selectedPaths = selectedPaths,
                path = path,
                outputPath = outputPath,
                pathRelativeToOutput = pathRelativeToOutput
            )
        }
    }

    override fun resetEditorFrom(runConfiguration: MpyRunConfMpyCross) {
        // Reset available sources table
        repeat(availableSourcesTable.rowCount) {
            availableSourcesTable.listTableModel.removeRow(0)
        }

        transferService.collectMpySourceRoots().forEach { file ->
            if (file.path in runConfiguration.options.selectedPaths) return@forEach
            availableSourcesTable.listTableModel.addRow(SourceItem(runConfiguration.project, file.path))
        }

        sortSourceItemsTable(availableSourcesTable)

        // Reset selected sources table
        repeat(selectedSourcesTable.rowCount) {
            selectedSourcesTable.listTableModel.removeRow(0)
        }

        runConfiguration.options.selectedPaths.forEach { path ->
            selectedSourcesTable.listTableModel.addRow(SourceItem(runConfiguration.project, path))
        }

        sortSourceItemsTable(selectedSourcesTable)

        configurationPanel.reset()
    }
}
