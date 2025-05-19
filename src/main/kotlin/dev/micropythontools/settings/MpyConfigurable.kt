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

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.*
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.State
import dev.micropythontools.communication.performReplAction
import dev.micropythontools.util.MpyStubPackageService
import jssc.SerialPort
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * @author Lukas Kremla
 */
private data class ConfigurableParameters(
    var isPluginEnabled: Boolean,
    var usingUart: Boolean,
    var filterManufacturers: Boolean,
    var portName: String,
    var webReplIp: String,
    var webReplPort: Int,
    var webReplPassword: String,
    var compileToBytecode: Boolean,
    var useSockets: Boolean,
    var requireMinimumSocketTransferSize: Boolean,
    var minimumSocketTransferSize: Int,
    var showUploadPreviewDialog: Boolean,
    var ssid: String,
    var wifiPassword: String,
    var areStubsEnabled: Boolean,
    var activeStubsPackage: String
)

/**
 * @author Lukas Kremla
 */
internal class MpyConfigurable(private val project: Project) :
    BoundSearchableConfigurable("MicroPython Tools", "dev.micropythontools.settings") {

    private val settings = project.service<MpySettingsService>()
    private val deviceService = project.service<MpyDeviceService>()
    private val mpyStubPackageService = project.service<MpyStubPackageService>()

    private val isConnected
        get() = (deviceService.state == State.CONNECTED || deviceService.state == State.CONNECTING ||
                deviceService.state == State.TTY_DETACHED)

    private val parameters = with(settings.state) {
        runWithModalProgressBlocking(project, "Retrieving settings...") {
            val wifiCredentials = settings.retrieveWifiCredentials()

            ConfigurableParameters(
                isPluginEnabled = isPluginEnabled,
                usingUart = usingUart,
                filterManufacturers = filterManufacturers,
                portName = if (portName.isNullOrBlank()) EMPTY_PORT_NAME_TEXT else portName.toString(),
                webReplIp = webReplIp ?: DEFAULT_WEBREPL_IP,
                webReplPort = webReplPort,
                webReplPassword = settings.retrieveWebReplPassword(),
                compileToBytecode = compileToBytecode,
                useSockets = useSockets,
                requireMinimumSocketTransferSize = requireMinimumSocketTransferSize,
                minimumSocketTransferSize = minimumSocketTransferSize,
                showUploadPreviewDialog = showUploadPreviewDialog,
                ssid = wifiCredentials.userName ?: "",
                wifiPassword = wifiCredentials.getPasswordAsString() ?: "",
                areStubsEnabled = areStubsEnabled,
                activeStubsPackage = mpyStubPackageService.getExistingStubPackage(),
            )
        }
    }

    private lateinit var settingsPanel: DialogPanel

    private lateinit var pluginEnabledCheckBox: Cell<JBCheckBox>

    private lateinit var connectionGroup: Row

    private lateinit var serialRadioButton: Cell<JBRadioButton>
    private lateinit var webReplRadioButton: Cell<JBRadioButton>

    private lateinit var filterManufacturersCheckBox: Cell<JBCheckBox>
    private lateinit var portSelectComboBox: Cell<ComboBox<String>>

    private lateinit var showUploadPreviewDialogCheckBox: Cell<JBCheckBox>

    private lateinit var areStubsEnabled: Cell<JBCheckBox>

    override fun createPanel(): DialogPanel {
        val portSelectModel = MutableCollectionComboBoxModel<String>()
        updatePortSelectModel(portSelectModel, true)

        val availableStubs = mpyStubPackageService.getAvailableStubs()

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
                                    isEditable = false
                                    selectedItem = parameters.portName

                                    addPopupMenuListener(object : PopupMenuListener {
                                        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                                            updatePortSelectModel(portSelectModel)
                                        }

                                        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
                                        override fun popupMenuCanceled(e: PopupMenuEvent?) {}
                                    })
                                }
                        }
                    }.visibleIf(serialRadioButton.selected)

                    indent {
                        row("URL: ") {
                            cell().apply { }
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
                                    val msg = messageForBrokenIp(field.text)
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
                                    val msg = messageForBrokenPort(field.text)
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
                                    val msg = messageForBrokenPassword(field.password)
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
                                    "Disconnecting...",
                                    false,
                                    "Disconnect operation cancelled",
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
                        showUploadPreviewDialogCheckBox = checkBox("Show upload preview dialog")
                            .bindSelected(parameters::showUploadPreviewDialog)
                    }
                }.bottomGap(BottomGap.NONE).topGap(TopGap.SMALL)

                group("MicroPython Stubs") {
                    row {
                        areStubsEnabled = checkBox("Enable MicroPython stubs")
                            .bindSelected(parameters::areStubsEnabled)
                    }

                    row {
                        cell(
                            TextFieldWithAutoCompletion(
                                project,
                                object : TextFieldWithAutoCompletionListProvider<String>(availableStubs) {
                                    override fun getItems(
                                        prefix: String?,
                                        cached: Boolean,
                                        parameters: CompletionParameters?
                                    ): MutableCollection<String> {
                                        return if (prefix.isNullOrEmpty()) {
                                            availableStubs.toMutableList()
                                        } else {
                                            availableStubs.filter { it.contains(prefix, ignoreCase = true) }
                                                .toMutableList()
                                        }
                                    }

                                    override fun getLookupString(item: String) = item
                                },
                                true,
                                parameters.activeStubsPackage
                            ).apply {
                                setPreferredWidth(450)
                            }
                        )
                            .bind(
                                { component -> component.text.takeIf { it.isNotBlank() } ?: "" },
                                { component, text -> component.text = text },
                                parameters::activeStubsPackage.toMutableProperty()
                            )
                            .label("Stubs package: ")
                            .comment(
                                "Note: Library changes may not take effect immediately after IDE startup. " +
                                        "If the changes don't appear right away, close the settings, wait a few seconds and try again.<br>" +
                                        "Stubs authored by <a href=\"https://github.com/Josverl/micropython-stubs\">Jos Verlinde</a>",
                                maxLineLength = 60
                            )
                            .validationInfo { field ->
                                val text = field.text

                                if (!availableStubs.contains(text) || text.isBlank()) {
                                    ValidationInfo("Invalid stubs package name!")
                                } else null
                            }
                            .applyToComponent {
                                toolTipText = "Type \"micropython\" to browse available packages"
                            }
                    }.enabledIf(areStubsEnabled.selected)
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
            settings.state.filterManufacturers = filterManufacturers
            settings.state.portName = portName.takeUnless { it == EMPTY_PORT_NAME_TEXT }

            // Force create these manually-configured settings
            if (settings.state.increaseBaudrateForFileTransfers == "") {
                settings.state.increaseBaudrateForFileTransfers = "false"
            }

            if (settings.state.increasedFileTransferBaudrate == "") {
                settings.state.increasedFileTransferBaudrate = "${SerialPort.BAUDRATE_115200}"
            }

            settings.state.webReplIp = webReplIp
            settings.state.webReplPort = webReplPort
            settings.state.compileToBytecode = compileToBytecode
            settings.state.useSockets = useSockets
            settings.state.requireMinimumSocketTransferSize = requireMinimumSocketTransferSize
            settings.state.showUploadPreviewDialog = showUploadPreviewDialog
            settings.state.minimumSocketTransferSize = minimumSocketTransferSize
            settings.state.areStubsEnabled = areStubsEnabled

            val stubPackageToUse = if (areStubsEnabled) activeStubsPackage else null

            mpyStubPackageService.updateLibrary(stubPackageToUse)

            runWithModalProgressBlocking(project, "Saving settings...") {
                settings.saveWebReplPassword(webReplPassword)
                settings.saveWifiCredentials(ssid, wifiPassword)
            }
        }
    }

    fun updatePortSelectModel(
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