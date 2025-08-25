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
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.setEmptyState
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.and
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.jetbrains.python.PythonFileType
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.core.MpyValidators
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.icons.MpyIcons
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer

private data class FlashParameters(
    var uploadMode: Int,
    var selectedPaths: MutableList<String>,
    var path: String,
    var uploadToPath: String,
    var switchToReplOnSuccess: Boolean,
    var resetOnSuccess: Boolean,
    var synchronize: Boolean,
    var excludePaths: Boolean,
    var excludedPaths: MutableList<String>
)

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

    val isValid = virtualFile?.exists() == true && project.service<MpyTransferService>().collectMpySourceRoots()
        .any { mpySource ->
            VfsUtil.isAncestor(mpySource, virtualFile, false)
        }

    val icon = if (isValid) MpyIcons.Source
    else AllIcons.General.Error
}

private data class ExcludedItem(val path: String) {
    // This check isn't perfect, but with the limited information about excluded paths, it is the best choice
    val isDirectory = !path.substringAfterLast("/").contains(".")

    val icon: Icon = when {
        isDirectory -> AllIcons.Nodes.Folder
        path.endsWith("mpy") -> PythonFileType.INSTANCE.icon
        else -> FileTypeRegistry.getInstance().getFileTypeByFileName(path).icon
    }
}

internal class MpyRunConfUploadEditor(private val runConfiguration: MpyRunConfUpload) :
    SettingsEditor<MpyRunConfUpload>() {

    private val transferService = runConfiguration.project.service<MpyTransferService>()

    private val parameters = with(runConfiguration.options) {
        FlashParameters(
            uploadMode = uploadMode,
            selectedPaths = selectedPaths.toMutableList(),
            path = path ?: "",
            uploadToPath = uploadToPath ?: "/",
            switchToReplOnSuccess = switchToReplOnSuccess,
            resetOnSuccess = resetOnSuccess,
            synchronize = synchronize,
            excludePaths = excludePaths,
            excludedPaths = excludedPaths.toMutableList()
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

    private fun sortExcludedItemsTable(table: TableView<ExcludedItem>) {
        val sortedItems = table.listTableModel.items.toList().sortedWith(
            compareBy<ExcludedItem> { !it.isDirectory }
                .thenBy { it.path.lowercase() }
        )
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

    private fun handlePathChange(uploadToPath: String, filePath: String) {
        val file = StandardFileSystems.local().findFileByPath(filePath)

        if (file?.isDirectory != true) {
            targetPathRow.visible(true)

            targetPathLabel.component.text = when {
                uploadToPath.endsWith("/") -> "$uploadToPath${runConfiguration.getFileName(filePath)}"
                else -> "$uploadToPath/${runConfiguration.getFileName(filePath)}"
            }
        } else {
            targetPathRow.visible(false)
        }
    }

    private fun sourcesTable(emptyText: String) = TableView<SourceItem>().apply {
        val column =
            object : ColumnInfo<SourceItem, String>(MpyBundle.message("run.conf.upload.editor.source.table.label")) {
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

    private val availableSourcesTable =
        sourcesTable(MpyBundle.message("run.conf.upload.editor.source.table.empty.text.none.available"))
    private val selectedSourcesTable =
        sourcesTable(MpyBundle.message("run.conf.upload.editor.source.table.empty.text.none.selected"))

    private val excludedPathsTable = TableView<ExcludedItem>().apply {
        val column = object :
            ColumnInfo<ExcludedItem, String>(MpyBundle.message("run.conf.upload.editor.excluded.table.label")) {
            override fun valueOf(item: ExcludedItem) = item.path

            override fun getRenderer(item: ExcludedItem) = DefaultTableCellRenderer().apply {
                icon = item.icon
            }
        }

        model = ListTableModel<ExcludedItem>(column)
        tableHeader = null
        setShowGrid(false)
        setEmptyState(MpyBundle.message("run.conf.upload.editor.excluded.table.empty.text"))
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        preferredScrollableViewportSize = Dimension(500, 100)
    }

    private val excludedPathsTableWithToolbar = ToolbarDecorator.createDecorator(excludedPathsTable)
        .disableUpDownActions()
        .setAddAction {
            val dialog = DialogBuilder(runConfiguration.project)
            dialog.title(MpyBundle.message("run.conf.upload.editor.excluded.add.dialog.title"))

            val textField = JBTextField().apply {
                preferredSize = Dimension(300, preferredSize.height)
            }

            val panel = JPanel(BorderLayout())
            panel.add(JBLabel("${MpyBundle.message("run.conf.upload.editor.excluded.path.label")} "), BorderLayout.WEST)
            panel.add((textField), BorderLayout.CENTER)
            dialog.centerPanel(panel)

            dialog.setOkOperation {
                val validationResult = MpyValidators.validateMpyPath(textField.text)

                if (validationResult == null) {
                    val normalizedPath = MpyValidators.normalizeMpyPath(textField.text, true)

                    val model = excludedPathsTable.listTableModel
                    model.addRow(ExcludedItem(normalizedPath))
                    sortExcludedItemsTable(excludedPathsTable)
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
                val model = excludedPathsTable.listTableModel

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
            if (selectedRow != -1) {
                val model = excludedPathsTable.listTableModel
                val currentItem = model.getItem(selectedRow)

                val dialog = DialogBuilder(runConfiguration.project)
                dialog.title(MpyBundle.message("run.conf.upload.editor.excluded.edit.dialog.title"))

                val textField = JBTextField(currentItem.path).apply {
                    preferredSize = Dimension(300, preferredSize.height)
                }

                val panel = JPanel(BorderLayout())
                panel.add(
                    JBLabel("${MpyBundle.message("run.conf.upload.editor.excluded.path.label")} "),
                    BorderLayout.WEST
                )
                panel.add((textField), BorderLayout.CENTER)
                dialog.centerPanel(panel)

                dialog.setOkOperation {
                    val validationResult = MpyValidators.validateMpyPath(textField.text)

                    if (validationResult == null) {
                        val normalizedPath = MpyValidators.normalizeMpyPath(textField.text, true)

                        model.setItem(selectedRow, ExcludedItem(normalizedPath))
                        sortExcludedItemsTable(excludedPathsTable)
                        dialog.dialogWrapper.close(0)
                    } else {
                        dialog.setErrorText(validationResult)
                    }
                }

                dialog.show()
            }
        }
        .createPanel()

    private lateinit var configurationPanel: DialogPanel
    private lateinit var synchronizeCheckbox: Cell<JBCheckBox>
    private lateinit var excludePathsCheckbox: Cell<JBCheckBox>
    private lateinit var uploadProjectRadioButton: Cell<JBRadioButton>
    private lateinit var useSelectedPathsRadioButton: Cell<JBRadioButton>
    private lateinit var usePathRadiobutton: Cell<JBRadioButton>
    private lateinit var uploadToTextField: Cell<JBTextField>
    private lateinit var targetPathRow: Row
    private lateinit var targetPathLabel: Cell<JLabel>
    private lateinit var availableToSelectedButton: Cell<JButton>
    private lateinit var selectedToAvailableButton: Cell<JButton>

    override fun createEditor(): JComponent {
        setupTableSelectionListeners()

        configurationPanel = panel {
            buttonsGroup {
                row {
                    label("${MpyBundle.message("run.conf.upload.editor.type.selector.label")} ")
                    uploadProjectRadioButton =
                        radioButton(MpyBundle.message("run.conf.upload.editor.type.selector.button.project"))
                            .bindSelected({ parameters.uploadMode == 0 }, { if (it) parameters.uploadMode = 0 })
                            .applyToComponent {
                                addActionListener {
                                    if (runConfiguration.isGeneratedName) {
                                        runConfiguration.suggestedName()
                                    }
                                }
                            }
                            .comment(
                                MpyBundle.message(
                                    "run.conf.upload.editor.comment.learn.about",
                                    "https://github.com/lukaskremla/micropython-tools-jetbrains/blob/main/DOCUMENTATION.md#uploads"
                                )
                            )
                    useSelectedPathsRadioButton =
                        radioButton(MpyBundle.message("run.conf.upload.editor.type.selector.button.selected"))
                            .bindSelected({ parameters.uploadMode == 1 }, { if (it) parameters.uploadMode = 1 })
                            .applyToComponent {
                                addActionListener {
                                    if (runConfiguration.isGeneratedName) {
                                        runConfiguration.suggestedName()
                                    }
                                }
                            }
                    usePathRadiobutton =
                        radioButton(MpyBundle.message("run.conf.upload.editor.type.selector.button.path"))
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
                        .label(MpyBundle.message("run.conf.upload.editor.tables.label.available"), LabelPosition.TOP)
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
                        .label(MpyBundle.message("run.conf.upload.editor.tables.label.selected"), LabelPosition.TOP)
                        .resizableColumn()
                }
            }.visibleIf(useSelectedPathsRadioButton.selected)

            panel {
                row("${MpyBundle.message("run.conf.upload.editor.custom.path.label")} ") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptor(true, true, false, false, false, false)
                            .withTitle(MpyBundle.message("run.conf.upload.editor.custom.path.file.chooser.title"))
                            .withRoots(runConfiguration.project.guessProjectDir()),
                        runConfiguration.project
                    ).apply {
                        component.text = parameters.path
                        component.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updatePath()
                            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updatePath()
                            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updatePath()

                            private fun updatePath() {
                                val newPath = component.text
                                parameters.path = newPath
                                handlePathChange(parameters.uploadToPath, newPath)
                            }
                        })
                    }.align(AlignX.FILL)
                }

                row("${MpyBundle.message("run.conf.upload.editor.upload.to.label")} ") {
                    uploadToTextField = textField()
                        .bindText(parameters::uploadToPath)
                        .columns(15)
                        .gap(RightGap.SMALL)
                        .validationInfo { field ->
                            val validationResult = MpyValidators.isRunConfTargetPathValid(field.text)

                            if (validationResult != null) {
                                error(validationResult)
                            } else null
                        }.apply {
                            component.document.addDocumentListener(object :
                                javax.swing.event.DocumentListener {
                                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updatePath()
                                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updatePath()
                                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updatePath()

                                private fun updatePath() {
                                    handlePathChange(component.text, parameters.path)
                                }
                            })
                        }
                }

                targetPathRow = row("${MpyBundle.message("run.conf.upload.editor.target.path.label")} ") {
                    targetPathLabel = label("")
                }
                handlePathChange(parameters.uploadToPath, parameters.path)
            }.visibleIf(usePathRadiobutton.selected)

            row {
                checkBox(MpyBundle.message("run.conf.upload.editor.checkbox.reset.on.success"))
                    .bindSelected(parameters::resetOnSuccess)
            }

            row {
                checkBox(MpyBundle.message("run.conf.upload.editor.checkbox.switch.to.repl"))
                    .bindSelected(parameters::switchToReplOnSuccess)
            }

            row {
                synchronizeCheckbox = checkBox(MpyBundle.message("run.conf.upload.editor.checkbox.synchronize"))
                    .bindSelected(parameters::synchronize)
                    .gap(RightGap.SMALL)

                cell(JBLabel(AllIcons.General.ContextHelp).apply {
                    toolTipText = MpyBundle.message("run.conf.upload.editor.tooltip.synchronize")
                })
            }

            row {
                excludePathsCheckbox =
                    checkBox(MpyBundle.message("run.conf.upload.editor.checkbox.exclude.from.synchronization"))
                        .bindSelected(parameters::excludePaths)
                        .gap(RightGap.SMALL)
            }.visibleIf(synchronizeCheckbox.selected)

            collapsibleGroup(MpyBundle.message("run.conf.upload.editor.collapsible.group.excluded.paths.title")) {
                row {
                    cell(excludedPathsTableWithToolbar)
                }
            }.apply {
                this.expanded = true
            }.visibleIf(synchronizeCheckbox.selected.and(excludePathsCheckbox.selected))
        }

        return configurationPanel
    }

    override fun applyEditorTo(runConfiguration: MpyRunConfUpload) {
        configurationPanel.apply()

        with(parameters) {
            selectedPaths.clear()
            excludedPaths.clear()

            val selectedSourcesTableItemsPaths = selectedSourcesTable.listTableModel.items.map { it.path }

            selectedPaths.addAll(selectedSourcesTableItemsPaths)

            val excludedPathsTableItemsPaths = excludedPathsTable.listTableModel.items.map { it.path }

            excludedPaths.addAll(excludedPathsTableItemsPaths)

            val normalizedUploadToPath = MpyValidators.normalizeMpyPath(uploadToPath, true)

            uploadToTextField.component.text = normalizedUploadToPath

            runConfiguration.saveOptions(
                uploadMode = uploadMode,
                selectedPaths = selectedPaths,
                path = path,
                uploadToPath = normalizedUploadToPath,
                switchToReplOnSuccess = switchToReplOnSuccess,
                resetOnSuccess = resetOnSuccess,
                synchronize = synchronize,
                excludePaths = excludePaths,
                excludedPaths = excludedPaths
            )
        }
    }

    override fun resetEditorFrom(runConfiguration: MpyRunConfUpload) {
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

        // Reset excluded paths table
        repeat(excludedPathsTable.rowCount) {
            excludedPathsTable.listTableModel.removeRow(0)
        }

        runConfiguration.options.excludedPaths.forEach { path ->
            excludedPathsTable.listTableModel.addRow(ExcludedItem(path))
        }

        sortExcludedItemsTable(excludedPathsTable)

        configurationPanel.reset()
    }
}
