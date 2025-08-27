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
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.ui.*
import com.intellij.ui.awt.RelativePoint
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
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.stubs.MpyStubPackageService
import dev.micropythontools.stubs.StubPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.NotNull
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable
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
    BoundSearchableConfigurable(MpyBundle.message("configurable.name"), "dev.micropythontools.settings") {

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
        runWithModalProgressBlocking(project, MpyBundle.message("configurable.progress.retrieving.settings")) {
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
    private lateinit var selectedStubPackageLabel: Cell<JLabel>
    private lateinit var selectedStubPackageHiddenField: Cell<com.intellij.ui.components.JBTextField>

    override fun createPanel(): DialogPanel {
        val portSelectModel = MutableCollectionComboBoxModel<String>()
        updatePortSelectModel(portSelectModel, true)

        settingsPanel = panel {
            row {
                pluginEnabledCheckBox = checkBox(MpyBundle.message("configurable.plugin.enabled.checkbox.text"))
                    .bindSelected(parameters::isPluginEnabled)
                    .comment(
                        MpyBundle.message(
                            "configurable.plugin.enabled.checkbox.comment",
                            "https://github.com/lukaskremla/micropython-tools-jetbrains"
                        )
                    )
            }

            panel {
                connectionGroup = group(MpyBundle.message("configurable.connection.group.title")) {
                    buttonsGroup {
                        row {
                            label("${MpyBundle.message("configurable.connection.type.selector.label")} ")
                            serialRadioButton =
                                radioButton(MpyBundle.message("configurable.connection.radio.button.serial"))
                                    .bindSelected(parameters::usingUart)

                            webReplRadioButton =
                                radioButton(MpyBundle.message("configurable.connection.radio.button.webrepl"))
                                    .bindSelected({ !parameters.usingUart }, { parameters.usingUart = !it })
                        }
                    }

                    indent {
                        row {
                            enableManualEditingCheckbox =
                                checkBox(MpyBundle.message("configurable.enable.manual.port.editing.checkbox.text"))
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
                            filterManufacturersCheckBox =
                                checkBox(MpyBundle.message("configurable.filter.out.unknown.manufacturers.checkbox.text"))
                                    .bindSelected(parameters::filterManufacturers)
                        }
                        row {
                            portSelectComboBox = comboBox(portSelectModel)
                                .label("${MpyBundle.message("configurable.port.select.combobox.label")} ")
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
                        row("${MpyBundle.message("configurable.webrepl.url.label")} ") {
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
                                    toolTipText = MpyBundle.message("configurable.webrepl.url.tooltip")
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
                                    toolTipText = MpyBundle.message("configurable.webrepl.url.textbox.tooltip")
                                }
                                .columns(6)
                        }

                        row("${MpyBundle.message("configurable.webrepl.password.textbox.label")} ") {
                            passwordField()
                                .bindText(parameters::webReplPassword)
                                .comment(MpyBundle.message("configurable.webrepl.password.textbox.comment"))
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
                            comment(MpyBundle.message("configurable.board.currently.connected.comment"), action = {
                                performReplAction(
                                    project,
                                    connectionRequired = false,
                                    requiresRefreshAfter = false,
                                    description = MpyBundle.message("action.disconnect.text"),
                                    cancelledMessage = MpyBundle.message("action.disconnect.cancelled"),
                                    timedOutMessage = MpyBundle.message("action.disconnect.timeout"),
                                    { reporter -> deviceService.disconnect(reporter) }
                                )

                                connectionGroup.enabled(true)

                                this.visible(false)
                            })
                        }
                    }
                }.visible(isConnected)

                group(MpyBundle.message("configurable.communication.group.title")) {
                    row {
                        checkBox(MpyBundle.message("configurable.legacy.volume.support.checkbox.text"))
                            .bindSelected(parameters::legacyVolumeSupportEnabled)
                            .gap(RightGap.SMALL)

                        cell(JBLabel(AllIcons.General.ContextHelp).apply {
                            toolTipText = MpyBundle.message("configurable.legacy.volume.support.checkbox.tooltip")
                        })
                    }

                    row {
                        checkBox(MpyBundle.message("configurable.show.upload.preview.checkbox.title"))
                            .bindSelected(parameters::showUploadPreviewDialog)
                    }
                }.bottomGap(BottomGap.NONE).topGap(TopGap.SMALL)

                group(MpyBundle.message("configurable.stubs.group.title")) {
                    row {
                        stubsEnabledCheckBox = checkBox(MpyBundle.message("configurable.enable.stubs.checkbox.text"))
                            .bindSelected(parameters::areStubsEnabled)
                    }

                    row {
                        selectedStubPackageLabel = label("")
                            .apply {
                                this.component.icon = AllIcons.Actions.Checked
                            }

                        // Invisible binder so the panel tracks modifications to activeStubsPackage
                        selectedStubPackageHiddenField = textField()
                            .bindText(parameters::activeStubsPackage)
                            .visible(false)
                    }

                    fun updateSelectedLabel() {
                        selectedStubPackageLabel.component.text =
                            if (selectedStubPackageHiddenField.component.text.isBlank())
                                MpyBundle.message("configurable.selected.stub.package.label.text.empty")
                            else
                                MpyBundle.message(
                                    "configurable.selected.stub.package.label.text",
                                    selectedStubPackageHiddenField.component.text
                                )
                    }

                    updateSelectedLabel()

                    val activeKey = { parameters.activeStubsPackage }

                    fun versionKey(v: String): List<Int> {
                        val (base, post) = v.split(".post", limit = 2).let { it[0] to it.getOrNull(1) }
                        val nums = base.split('.').mapNotNull { it.toIntOrNull() }
                        return nums + listOf(if (post != null) 1 else 0, post?.toIntOrNull() ?: 0)
                    }

                    fun List<StubPackage>.withActiveFirst(): List<StubPackage> =
                        this.sortedWith(
                            compareByDescending<StubPackage> { "${it.name}_${it.mpyVersion}" == activeKey() }
                                .thenByDescending { it.isInstalled }
                                .thenComparator { a, b ->
                                    // compare versionKey lists descending
                                    val ka = versionKey(a.mpyVersion)
                                    val kb = versionKey(b.mpyVersion)
                                    for (i in 0 until maxOf(ka.size, kb.size)) {
                                        val x = ka.getOrNull(i) ?: 0
                                        val y = kb.getOrNull(i) ?: 0
                                        if (x != y) return@thenComparator y.compareTo(x)
                                    }
                                    0
                                }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        )

                    // Column definitions
                    val stubColumns = arrayOf(
                        object :
                            ColumnInfo<StubPackage, String>(MpyBundle.message("configurable.stub.package.column.label.name")) {
                            override fun valueOf(item: StubPackage): String = item.name
                        },
                        object :
                            ColumnInfo<StubPackage, String>(MpyBundle.message("configurable.stub.package.column.label.version")) {
                            override fun valueOf(item: StubPackage): String = item.mpyVersion
                        }
                    )

                    var stubPackages: List<StubPackage> = emptyList()

                    val tableModel: ListTableModel<StubPackage> = ListTableModel(*stubColumns)

                    val table = TableView(tableModel)
                    table.setShowGrid(false)
                    table.preferredScrollableViewportSize = Dimension(600, 250)

                    ApplicationManager.getApplication().executeOnPooledThread {
                        val data = stubPackageService.getStubPackages().first
                        ApplicationManager.getApplication().invokeLater {
                            tableModel.items = data.withActiveFirst()
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

                            val isSelected =
                                "${stubPackage.name}_${stubPackage.mpyVersion}" == selectedStubPackageHiddenField.component.text

                            c.foreground = when {
                                shouldGray -> UIUtil.getLabelDisabledForeground()
                                !stubPackage.isUpToDate -> JBColor.BLUE
                                isSelected -> JBColor.GREEN
                                else -> base
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

                    fun refreshTable(e: AnActionEvent) {
                        val app = ApplicationManager.getApplication()
                        app.executeOnPooledThread {
                            val (newStubPackages, fetchedRemote) = stubPackageService.getStubPackages()

                            stubPackages = newStubPackages

                            app.invokeLater {
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
                                tableModel.items = filtered.withActiveFirst()
                                tableModel.fireTableDataChanged()

                                if (table.selectedRow >= tableModel.rowCount) {
                                    table.clearSelection()
                                }

                                if (!fetchedRemote) {
                                    val source = e.inputEvent?.component as? JComponent ?: return@invokeLater
                                    showBalloon(
                                        source,
                                        MpyBundle.message("configurable.error.failed.to.get.remote.stub.packages")
                                    )
                                }
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
                                this.templatePresentation.text =
                                    MpyBundle.message("configurable.stub.table.button.select")
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                val selectedObjects = table.selectedObjects

                                e.presentation.isEnabled = stubsAndPluginEnabled &&
                                        selectedObjects.size == 1 &&
                                        "${selectedObjects.first().name}_${selectedObjects.first().mpyVersion}" != selectedStubPackageHiddenField.component.text &&
                                        selectedObjects.first().isInstalled
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                val selected = table.selectedObject ?: return
                                val key = "${selected.name}_${selected.mpyVersion}"
                                selectedStubPackageHiddenField.component.text = key
                                updateSelectedLabel()
                            }
                        })
                        .addExtraAction(object : AnAction() {
                            init {
                                this.templatePresentation.icon = AllIcons.Diff.Remove
                                this.templatePresentation.text =
                                    MpyBundle.message("configurable.stub.table.button.deselect")
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                val selectedObjects = table.selectedObjects

                                e.presentation.isEnabled = stubsAndPluginEnabled &&
                                        selectedObjects.size == 1 &&
                                        "${selectedObjects.first().name}_${selectedObjects.first().mpyVersion}" == selectedStubPackageHiddenField.component.text &&
                                        selectedObjects.first().isInstalled
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                selectedStubPackageHiddenField.component.text = ""
                                updateSelectedLabel()
                            }
                        })
                        .addExtraAction(object : AnAction() {
                            init {
                                this.templatePresentation.icon = AllIcons.Actions.Download
                                this.templatePresentation.text =
                                    MpyBundle.message("configurable.stub.table.button.install.update")
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                val selected = table.selectedObject

                                e.presentation.isEnabled = stubsAndPluginEnabled &&
                                        selected != null &&
                                        (!selected.isUpToDate || !selected.isInstalled)
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                val selected: List<StubPackage> = table.selectedObjects
                                val app = ApplicationManager.getApplication()

                                try {
                                    runWithModalProgressBlocking(
                                        project,
                                        MpyBundle.message("configurable.progress.installing.stub.packages.title")
                                    ) {
                                        reportRawProgress { reporter ->
                                            var i = 1
                                            selected.forEach {
                                                reporter.text(
                                                    MpyBundle.message(
                                                        "configurable.progress.installing.stub.packages.text",
                                                        i,
                                                        selected.size
                                                    )
                                                )
                                                reporter.fraction(
                                                    (i.toDouble() / selected.size.toDouble())
                                                        .coerceIn(0.0, 1.0)
                                                )
                                                reporter.details("${it.name}_${it.mpyVersion}")
                                                stubPackageService.install(it)
                                                i++
                                            }
                                        }
                                    }

                                    refreshTable(e)
                                } catch (err: CancellationException) {
                                    refreshTable(e)
                                    throw err
                                } catch (err: Throwable) {
                                    app.invokeLater {
                                        val source =
                                            e.inputEvent?.component as? JComponent ?: return@invokeLater
                                        showBalloon(source, err.localizedMessage)
                                    }
                                }
                            }
                        })
                        .addExtraAction(object : AnAction() {
                            init {
                                this.templatePresentation.icon = AllIcons.General.Delete
                                this.templatePresentation.text =
                                    MpyBundle.message("configurable.stub.table.button.delete")
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                val selected = table.selectedObject

                                e.presentation.isEnabled = stubsAndPluginEnabled &&
                                        selected != null &&
                                        selected.isInstalled
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                val selected: List<StubPackage> = table.selectedObjects
                                val app = ApplicationManager.getApplication()
                                try {
                                    selected.forEach {
                                        stubPackageService.delete(it)
                                    }

                                    refreshTable(e)
                                } catch (err: Throwable) {
                                    app.invokeLater {
                                        val source =
                                            e.inputEvent?.component as? JComponent ?: return@invokeLater
                                        showBalloon(source, err.localizedMessage)
                                    }
                                }
                            }
                        })
                        .addExtraAction(object : AnAction() {
                            init {
                                this.templatePresentation.icon = AllIcons.Actions.Refresh
                                this.templatePresentation.text =
                                    MpyBundle.message("configurable.stub.table.button.refresh")
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                            override fun update(e: AnActionEvent) {
                                e.presentation.isEnabled = stubsAndPluginEnabled
                            }

                            override fun actionPerformed(e: AnActionEvent) {
                                refreshTable(e)
                            }
                        })
                        .createPanel()

                    row {
                        cell(searchField)
                    }.enabledIf(stubsEnabledCheckBox.selected)
                    row {
                        cell(decoratedPanel)
                            .resizableColumn()
                            .comment(
                                MpyBundle.message(
                                    "configurable.stubs.authored.by.comment",
                                    "https://github.com/Josverl/micropython-stubs"
                                )
                            )
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

            runWithModalProgressBlocking(project, MpyBundle.message("configurable.progress.saving.settings")) {
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

    private fun showBalloon(@NotNull component: JComponent, message: String) {
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(message, MessageType.ERROR, null)
            .setFadeoutTime(5000)
            .createBalloon()
            .show(RelativePoint.getCenterOf(component), Balloon.Position.above)
    }
}