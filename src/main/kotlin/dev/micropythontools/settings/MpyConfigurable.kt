/*
 * Copyright 2025 Lukas Kremla
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

package dev.micropythontools.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.State
import dev.micropythontools.communication.performReplAction
import dev.micropythontools.core.MpyValidators
import dev.micropythontools.stubs.MpyStubPackageService
import dev.micropythontools.stubs.StubPackage
import jssc.SerialPort
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.table.DefaultTableCellRenderer

private data class ConfigurableParameters(
    var isPluginEnabled: Boolean,
    var usingUart: Boolean,
    var enableManualEditing: Boolean,
    var filterManufacturers: Boolean,
    var portName: String,
    var webReplIp: String,
    var webReplPort: Int,
    var webReplPassword: String,
    var legacyVolumeSupportEnabled: Boolean,
    var showUploadPreviewDialog: Boolean,
    var ssid: String,
    var wifiPassword: String,
    var areStubsEnabled: Boolean,
    var activeStubsPackage: String
)

internal class MpyConfigurable(private val project: Project) :
    BoundSearchableConfigurable("MicroPython Tools", "dev.micropythontools.settings") {

    private val settings = project.service<MpySettingsService>()
    private val deviceService = project.service<MpyDeviceService>()
    private val stubPackageService = project.service<MpyStubPackageService>()

    private val isConnected
        get() = (deviceService.state == State.CONNECTED ||
                deviceService.state == State.CONNECTING ||
                deviceService.state == State.TTY_DETACHED)

    private val stubsAndPluginEnabled
        get() = pluginEnabledCheckBox.component.isSelected && stubsEnabledCheckBox.component.isSelected

    private val parameters = with(settings.state) {
        runWithModalProgressBlocking(project, "Retrieving settings...") {
            val wifiCredentials = settings.retrieveWifiCredentials()

            ConfigurableParameters(
                isPluginEnabled = isPluginEnabled,
                usingUart = usingUart,
                enableManualEditing = enableManualEditing,
                filterManufacturers = filterManufacturers,
                portName = if (portName.isNullOrBlank()) EMPTY_PORT_NAME_TEXT else portName.toString(),
                webReplIp = webReplIp ?: DEFAULT_WEBREPL_IP,
                webReplPort = webReplPort,
                webReplPassword = settings.retrieveWebReplPassword(),
                legacyVolumeSupportEnabled = legacyVolumeSupportEnabled,
                showUploadPreviewDialog = showUploadPreviewDialog,
                ssid = wifiCredentials.userName ?: "",
                wifiPassword = wifiCredentials.getPasswordAsString() ?: "",
                areStubsEnabled = areStubsEnabled,
                activeStubsPackage = stubPackageService.getSelectedStubPackageName(),
            )
        }
    }

    private lateinit var settingsPanel: DialogPanel

    private lateinit var pluginEnabledCheckBox: Cell<JBCheckBox>

    private lateinit var connectionGroup: Row

    private lateinit var serialRadioButton: Cell<JBRadioButton>
    private lateinit var webReplRadioButton: Cell<JBRadioButton>

    private lateinit var enableManualEditingCheckbox: Cell<JBCheckBox>
    private lateinit var filterManufacturersCheckBox: Cell<JBCheckBox>
    private lateinit var portSelectComboBox: Cell<ComboBox<String>>

    private lateinit var stubsEnabledCheckBox: Cell<JBCheckBox>

    override fun createPanel(): DialogPanel {
        val portSelectModel = MutableCollectionComboBoxModel<String>()
        updatePortSelectModel(portSelectModel, true)

        settingsPanel = panel {
            row {
                pluginEnabledCheckBox = checkBox("Enable MicroPython support")
                    .bindSelected(parameters::isPluginEnabled)
                    .comment("Find usage tips, report bugs or ask questions on <a href=\"https://github.com/lukaskremla/micropython-tools-jetbrains\">GitHub</a>")
            }

            panel {
                connectionGroup = group("Connection") {
                    buttonsGroup {
                        row {
                            label("Type: ")
                            serialRadioButton = radioButton("Serial")
                                .bindSelected(parameters::usingUart)

                            webReplRadioButton = radioButton("WebREPL")
                                .bindSelected({ !parameters.usingUart }, { parameters.usingUart = !it })
                        }
                    }

                    indent {
                        row {
                            enableManualEditingCheckbox = checkBox("Edit port manually")
                                .bindSelected(parameters::enableManualEditing)
                                .applyToComponent {
                                    addActionListener {
                                        val comboBox = portSelectComboBox.component
                                        comboBox.isEditable = isSelected
                                        comboBox.revalidate()
                                        comboBox.repaint()
                                    }
                                }
                        }
                        row {
                            filterManufacturersCheckBox = checkBox("Filter out devices with unknown manufacturers")
                                .bindSelected(parameters::filterManufacturers)
                        }
                        row {
                            portSelectComboBox = comboBox(portSelectModel)
                                .label("Port: ")
                                .columns(20)
                                .bindItem(
                                    { parameters.portName },
                                    { parameters.portName = it.takeIf { !it.isNullOrBlank() } ?: EMPTY_PORT_NAME_TEXT }
                                )
                                .applyToComponent {
                                    isEditable = parameters.enableManualEditing
                                    selectedItem = parameters.portName

                                    addPopupMenuListener(object : PopupMenuListener {
                                        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                                            updatePortSelectModel(portSelectModel)
                                        }

                                        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
                                        override fun popupMenuCanceled(e: PopupMenuEvent?) {}
                                    })

                                    // Add listener to detect typing changes in the editor
                                    val editorComponent = editor.editorComponent
                                    if (editorComponent is javax.swing.JTextField) {
                                        editorComponent.document.addDocumentListener(object :
                                            javax.swing.event.DocumentListener {
                                            override fun insertUpdate(e: DocumentEvent?) =
                                                updateModel()

                                            override fun removeUpdate(e: DocumentEvent?) =
                                                updateModel()

                                            override fun changedUpdate(e: DocumentEvent?) =
                                                updateModel()

                                            private fun updateModel() {
                                                val text = editorComponent.text
                                                if (text != parameters.portName) {
                                                    parameters.portName = text
                                                    settingsPanel.validateAll()
                                                }
                                            }
                                        })
                                    }
                                }
                        }
                    }.visibleIf(serialRadioButton.selected)

                    indent {
                        row("URL: ") {
                            cell().apply { }
                            @Suppress("DialogTitleCapitalization")
                            label("ws://").gap(RightGap.SMALL)

                            cell(object : com.intellij.ui.components.JBTextField(parameters.webReplIp) {
                                // Width of 201 seems to be the best fit for making the ip and password fields' right edges line up
                                private val fixedSize = Dimension(201, 34)

                                // Override all size methods to return the fixed size
                                override fun getPreferredSize(): Dimension = fixedSize
                                override fun getMinimumSize(): Dimension = fixedSize
                                override fun getMaximumSize(): Dimension = fixedSize
                            })
                                .bindText(parameters::webReplIp)
                                .validationInfo { field ->
                                    val msg = MpyValidators.messageForBrokenIp(field.text)
                                    msg?.let { error(it).withOKEnabled() }
                                }
                                .applyToComponent {
                                    toolTipText = "IP of the target device"
                                }
                                .gap(RightGap.SMALL)

                            label(":").gap(RightGap.SMALL)

                            intTextField()
                                .bindIntText(parameters::webReplPort)
                                .validationInfo { field ->
                                    val msg = MpyValidators.messageForBrokenPort(field.text)
                                    msg?.let { error(it).withOKEnabled() }
                                }
                                .applyToComponent {
                                    toolTipText = "Default WebREPL port is 8266"
                                }
                                .columns(6)
                        }

                        row("Password: ") {
                            passwordField()
                                .bindText(parameters::webReplPassword)
                                .comment("(4-9 characters)")
                                .columns(21)
                                .validationInfo { field ->
                                    val msg = MpyValidators.messageForBrokenPassword(field.password)
                                    msg?.let { error(it).withOKEnabled() }
                                }
                        }
                    }.visibleIf(webReplRadioButton.selected)
                }.bottomGap(BottomGap.NONE).enabled(!isConnected)

                indent {
                    indent {
                        row {
                            comment("A board is currently connected. <a>Disconnect</a>", action = {
                                performReplAction(
                                    project,
                                    false,
                                    false,
                                    "Disconnecting...",
                                    "Disconnect operation cancelled",
                                    "Disconnect operation timed out",
                                    { reporter -> deviceService.disconnect(reporter) }
                                )

                                connectionGroup.enabled(true)

                                this.visible(false)
                            })
                        }
                    }
                }.visible(isConnected)

                group("Communication Settings") {
                    row {
                        checkBox("Enable legacy volume support (pre-1.25.0)")
                            .bindSelected(parameters::legacyVolumeSupportEnabled)
                            .gap(RightGap.SMALL)

                        cell(JBLabel(AllIcons.General.ContextHelp).apply {
                            toolTipText =
                                "Enables scanning for additional mounted volumes on MicroPython versions older than 1.25.0.<br>" +
                                        " May slow down filesystem refreshes on MicroPython versions older than 1.25.0."
                        })
                    }

                    row {
                        checkBox("Show upload preview dialog")
                            .bindSelected(parameters::showUploadPreviewDialog)
                    }
                }.bottomGap(BottomGap.NONE).topGap(TopGap.SMALL)

                group("MicroPython Stubs") {
                    row {
                        stubsEnabledCheckBox = checkBox("Enable MicroPython stubs")
                            .bindSelected(parameters::areStubsEnabled)
                    }

                    // Column definitions
                    val stubColumns = arrayOf(
                        object : ColumnInfo<StubPackage, String>("Name") {
                            override fun valueOf(item: StubPackage): String = item.name
                        },
                        object : ColumnInfo<StubPackage, String>("Version") {
                            override fun valueOf(item: StubPackage): String = item.mpyVersion
                        }
                    )

                    var stubPackages = emptyList<StubPackage>()

                    val tableModel: ListTableModel<StubPackage> = ListTableModel(*stubColumns)
                    tableModel.items = stubPackages

                    val table = TableView(tableModel)
                    table.setShowGrid(false)
                    table.preferredScrollableViewportSize = Dimension(600, 250)

                    ApplicationManager.getApplication().executeOnPooledThread {
                        val data = stubPackageService.getStubPackages()
                        SwingUtilities.invokeLater {
                            tableModel.items = data
                            tableModel.fireTableDataChanged()
                        }
                    }

                    table.setDefaultRenderer(String::class.java, object : DefaultTableCellRenderer() {
                        override fun getTableCellRendererComponent(
                            table: JTable,
                            value: Any?,
                            isSelected: Boolean,
                            hasFocus: Boolean,
                            row: Int,
                            column: Int
                        ): java.awt.Component {
                            val c = super.getTableCellRendererComponent(
                                table,
                                value,
                                isSelected,
                                hasFocus,
                                row,
                                column
                            ) as JLabel

                            val modelRow = table.convertRowIndexToModel(row)
                            val stubPackage = (table.model as ListTableModel<*>).items[modelRow]

                            if (stubPackage !is StubPackage) return c

                            val shouldGray = !stubsAndPluginEnabled ||
                                    !stubPackage.isInstalled

                            val base = if (isSelected)
                                UIUtil.getTableSelectionForeground(true)
                            else
                                UIUtil.getTableForeground()

                            c.foreground = when {
                                shouldGray -> UIUtil.getLabelDisabledForeground()
                                stubPackage.isUpToDate -> base
                                else -> JBColor.BLUE
                            }

                            return c
                        }
                    })

                    // Search field logic
                    val searchField = SearchTextField()
                    searchField.minimumSize = Dimension(350, 0)
                    searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
                        override fun textChanged(e: DocumentEvent) {
                            val filterText = searchField.text.lowercase()
                            tableModel.items = stubPackages.filter {
                                it.name.lowercase().contains(filterText) ||
                                        it.mpyVersion.lowercase().contains(filterText)
                            }
                        }
                    })

                    fun refreshTable() {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            stubPackages = stubPackageService.getStubPackages()

                            // re-apply current search filter
                            val q = searchField.text.lowercase()
                            val filtered = if (q.isBlank()) {
                                stubPackages
                            } else {
                                stubPackages.filter {
                                    it.name.lowercase().contains(q) || it.mpyVersion.lowercase().contains(q)
                                }
                            }

                            // push into the model & notify
                            tableModel.items = filtered
                            tableModel.fireTableDataChanged()

                            if (table.selectedRow >= tableModel.rowCount) {
                                table.clearSelection()
                            }
                        }
                    }

                    // Setup ToolbarDecorator with buttons
                    val decoratedPanel = ToolbarDecorator.createDecorator(table)
                        .disableAddAction()
                        .disableRemoveAction()
                        .disableUpDownActions()
                        .addExtraAction(object : AnAction() {
                            init {
                                this.templatePresentation.icon = AllIcons.Actions.Checked
                                this.templatePresentation.text = "Apply"
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    val selectedStubPackageName = stubPackageService.getSelectedStubPackageName()

                                    e.presentation.isEnabled = stubsAndPluginEnabled &&
                                            table.selectedObject?.name != selectedStubPackageName
                                }
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                stubPackageService.updateLibrary(
                                    table.selectedObject?.name
                                        ?: throw RuntimeException("Selected stub package name can't be null")
                                )
                            }
                        })
                        .addExtraAction(object : AnAction() {
                            init {
                                this.templatePresentation.icon = AllIcons.Diff.Remove
                                this.templatePresentation.text = "Unapply"
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    val selectedStubPackageName = stubPackageService.getSelectedStubPackageName()

                                    e.presentation.isEnabled = stubsAndPluginEnabled &&
                                            table.selectedObject?.name == selectedStubPackageName
                                }
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                stubPackageService.updateLibrary("")
                            }
                        })
                        .addExtraAction(object : AnAction() {
                            init {
                                this.templatePresentation.icon = AllIcons.Actions.Download
                                this.templatePresentation.text = "Install/Update"
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                e.presentation.isEnabled = stubsAndPluginEnabled && table.selectedObject != null
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                val selected: StubPackage = table.selectedObject ?: return
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    stubPackageService.install(selected)
                                }
                                refreshTable()
                            }
                        })
                        .addExtraAction(object : AnAction() {
                            init {
                                this.templatePresentation.icon = AllIcons.General.Delete
                                this.templatePresentation.text = "Delete"
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                e.presentation.isEnabled = stubsAndPluginEnabled && table.selectedObject != null
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                val selected: StubPackage = table.selectedObject ?: return
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    stubPackageService.delete(selected)
                                }
                                refreshTable()
                            }
                        })
                        .addExtraAction(object : AnAction() {
                            init {
                                this.templatePresentation.icon = AllIcons.Actions.Refresh
                                this.templatePresentation.text = "Refresh"
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                e.presentation.isEnabled = stubsAndPluginEnabled
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                refreshTable()
                            }
                        })
                        .createPanel()

                    row {
                        cell(searchField)
                    }.enabledIf(stubsEnabledCheckBox.selected)
                    row {
                        cell(decoratedPanel)
                            .resizableColumn()
                            .comment("Stubs authored by <a href=\"https://github.com/Josverl/micropython-stubs\">Jos Verlinde</a>")
                    }.enabledIf(stubsEnabledCheckBox.selected)
                }.topGap(TopGap.SMALL)
            }.enabledIf(pluginEnabledCheckBox.selected)
        }.apply {
            validateAll()
        }

        return settingsPanel
    }

    override fun reset() {
        portSelectComboBox.component.model.selectedItem = with(settings.state) {
            if (portName.isNullOrBlank()) EMPTY_PORT_NAME_TEXT else portName.toString()
        }

        super.reset()
    }

    override fun apply() {
        super.apply()

        with(parameters) {
            if (isConnected && !isPluginEnabled) {
                runBlocking { deviceService.disconnect(null) }
            }

            settings.state.isPluginEnabled = isPluginEnabled
            settings.state.usingUart = usingUart
            settings.state.enableManualEditing = enableManualEditing
            settings.state.filterManufacturers = filterManufacturers
            settings.state.portName = portName.takeUnless { it == EMPTY_PORT_NAME_TEXT }

            settings.state.webReplIp = webReplIp
            settings.state.webReplPort = webReplPort
            settings.state.legacyVolumeSupportEnabled = legacyVolumeSupportEnabled
            settings.state.showUploadPreviewDialog = showUploadPreviewDialog
            settings.state.areStubsEnabled = areStubsEnabled

            val stubPackageToUse = if (areStubsEnabled) activeStubsPackage else null

            stubPackageService.updateLibrary(stubPackageToUse)

            runWithModalProgressBlocking(project, "Saving settings...") {
                settings.saveWebReplPassword(webReplPassword)
                settings.saveWifiCredentials(ssid, wifiPassword)
            }
        }
    }

    private fun updatePortSelectModel(
        portSelectModel: MutableCollectionComboBoxModel<String>,
        isInitialUpdate: Boolean = false
    ) {
        val lsPortsParam =
            if (isInitialUpdate) parameters.filterManufacturers else filterManufacturersCheckBox.component.isSelected

        val ports = deviceService.listSerialPorts(lsPortsParam)

        portSelectModel.items
            .filterNot { it in ports || it == portSelectModel.selectedItem }
            .forEach { portSelectModel.remove(it) }

        val newPorts = ports.filterNot { portSelectModel.contains(it) }
        portSelectModel.addAll(portSelectModel.size, newPorts)

        if (isInitialUpdate || portSelectModel.isEmpty) {
            portSelectModel.selectedItem = parameters.portName.ifBlank {
                EMPTY_PORT_NAME_TEXT
            }
        }
    }
}