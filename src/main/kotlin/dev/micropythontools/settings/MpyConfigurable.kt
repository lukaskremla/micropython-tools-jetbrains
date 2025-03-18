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
import com.intellij.openapi.util.IconLoader
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.communication.State
import dev.micropythontools.ui.fileSystemWidget
import dev.micropythontools.ui.performReplAction
import dev.micropythontools.util.MpyPythonService
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
    var webReplUrl: String,
    var webReplPassword: String,
    var useFTP: Boolean,
    var cacheFTPScript: Boolean,
    var cachedFTPScriptPath: String,
    var requireMinimumFTPUploadSize: Boolean,
    var minimumFTPUploadSize: Int,
    var ssid: String,
    var wifiPassword: String,
    var areStubsEnabled: Boolean,
    var activeStubsPackage: String
)

/**
 * @author Lukas Kremla
 */
class MpyConfigurable(private val project: Project) : BoundSearchableConfigurable("MicroPython Tools", "dev.micropythontools.settings") {
    private val questionMarkIcon = IconLoader.getIcon("/icons/questionMark.svg", MpyConfigurable::class.java)
    private val settings = project.service<MpySettingsService>()
    private val pythonService = project.service<MpyPythonService>()
    private val transferService = project.service<MpyTransferService>()

    private fun isDisconnected() = (fileSystemWidget(project)?.state == State.DISCONNECTED ||
            fileSystemWidget(project)?.state == State.DISCONNECTING)

    private val parameters = with(settings.state) {
        runWithModalProgressBlocking(project, "Retrieving settings...") {
            val wifiCredentials = settings.retrieveWifiCredentials()

            ConfigurableParameters(
                isPluginEnabled = isPluginEnabled,
                usingUart = usingUart,
                filterManufacturers = filterManufacturers,
                portName = if (portName.isNullOrBlank()) EMPTY_PORT_NAME_TEXT else portName.toString(),
                webReplUrl = webReplUrl ?: DEFAULT_WEBREPL_URL,
                webReplPassword = settings.retrieveWebReplPassword(),
                useFTP = useFTP,
                cacheFTPScript = cacheFTPScript,
                cachedFTPScriptPath = cachedFTPScriptPath ?: "",
                requireMinimumFTPUploadSize = requireMinimumFTPUploadSize,
                minimumFTPUploadSize = minimumFTPUploadSize,
                ssid = wifiCredentials.userName ?: "",
                wifiPassword = wifiCredentials.getPasswordAsString() ?: "",
                areStubsEnabled = areStubsEnabled,
                activeStubsPackage = activeStubsPackage ?: "",
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

    private lateinit var useFTPCheckBox: Cell<JBCheckBox>
    private lateinit var cacheFTPScriptCheckBox: Cell<JBCheckBox>
    private lateinit var cacheFTPScriptPathTextField: Cell<JBTextField>
    private lateinit var minimumFTPUploadSizeCheckBox: Cell<JBCheckBox>

    private lateinit var areStubsEnabled: Cell<JBCheckBox>

    override fun createPanel(): DialogPanel {
        val portSelectModel = MutableCollectionComboBoxModel<String>()
        updatePortSelectModel(portSelectModel, true)

        val availableStubs = pythonService.getAvailableStubs()

        settingsPanel = panel {
            row {
                pluginEnabledCheckBox = checkBox("Enable MicroPython support")
                    .bindSelected(parameters::isPluginEnabled)
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
                            textField()
                                .bindText(parameters::webReplUrl)
                                .columns(25)
                                .validationInfo { field ->
                                    val msg = messageForBrokenUrl(field.text)
                                    msg?.let { error(it).withOKEnabled() }
                                }
                        }
                        row("Password: ") {
                            passwordField()
                                .bindText(parameters::webReplPassword)
                                .comment("(4-9 symbols)")
                                .columns(25)
                                .validationInfo { field ->
                                    if (field.password.size !in WEBREPL_PASSWORD_LENGTH_RANGE) {
                                        error("Allowed password length is $WEBREPL_PASSWORD_LENGTH_RANGE").withOKEnabled()
                                    } else null
                                }
                        }
                        row {
                            comment("WebREPL is temporarily disabled due to a bug. It will be reinstated in a later release.")
                        }
                    }.visibleIf(webReplRadioButton.selected).enabled(false)
                }.bottomGap(BottomGap.NONE).enabled(isDisconnected())

                indent {
                    indent {
                        row {
                            comment("A board is currently connected. <a>Disconnect</a>", action = {
                                performReplAction(
                                    project,
                                    false,
                                    "Disconnecting...",
                                    false,
                                    { _, reporter -> fileSystemWidget(project)?.disconnect(reporter) }
                                )

                                connectionGroup.enabled(true)

                                this.visible(false)
                            })
                        }
                    }
                }.visible(!isDisconnected())

                group("FTP Uploads") {
                    row {
                        useFTPCheckBox = checkBox("Use FTP for uploads")
                            .bindSelected(parameters::useFTP)
                            .gap(RightGap.SMALL)

                        cell(JBLabel(questionMarkIcon).apply {
                            toolTipText = "An FTP server will be established on the board. FTP uploads are faster and more reliable for large uploads."
                        })
                    }

                    indent {
                        indent {
                            row("SSID: ") {
                                textField()
                                    .bindText(parameters::ssid)
                                    .columns(25)
                            }
                            row("Password: ") {
                                passwordField()
                                    .bindText(parameters::wifiPassword)
                                    .columns(25)
                            }
                            row {
                                comment(
                                    "These credentials will be used to establish a network connection over serial communication.<br>" +
                                            "FTP uploads currently do not support webREPL"
                                )
                            }
                        }

                        /*
                         *"These credentials will be used to establish a network connection over serial communication.<br>" +
                         *"If WebREPL is active, its URL is used instead."
                         */

                        row {
                            cacheFTPScriptCheckBox = checkBox("Cache FTP script on device")
                                .bindSelected(parameters::cacheFTPScript)
                                .gap(RightGap.SMALL)

                            cell(JBLabel(questionMarkIcon).apply {
                                toolTipText = "The required uftpd script will be cached on the configured path to save time."
                            })
                        }

                        indent {
                            row("Path: ") {
                                cacheFTPScriptPathTextField = textField()
                                    .bindText(parameters::cachedFTPScriptPath)
                                    .columns(15)
                                    .gap(RightGap.SMALL)
                                    .validationInfo { field ->
                                        val validationResult = isUftpdPathValid(field.text)

                                        if (validationResult != null) {
                                            error(validationResult)
                                        } else null
                                    }

                                label("/uftpd.py")
                            }
                            row {
                                comment("Leave path empty if your MicroPython port includes the ufptd library as frozen bytecode.")
                            }
                        }.visibleIf(cacheFTPScriptCheckBox.selected)

                        row {
                            minimumFTPUploadSizeCheckBox = checkBox("FTP upload size threshold")
                                .bindSelected(parameters::requireMinimumFTPUploadSize)
                                .gap(RightGap.SMALL)

                            cell(JBLabel(questionMarkIcon).apply {
                                toolTipText = "FTP uploads will only be used if the total upload size is over the set threshold."
                            })
                        }

                        indent {
                            row("Size: ") {
                                intTextField()
                                    .bindIntText(parameters::minimumFTPUploadSize)
                                    .columns(5)
                                    .gap(RightGap.SMALL)

                                label("KB")
                            }
                        }.visibleIf(minimumFTPUploadSizeCheckBox.selected)
                    }.visibleIf(useFTPCheckBox.selected)
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
                                    override fun getItems(prefix: String?, cached: Boolean, parameters: CompletionParameters?): MutableCollection<String> {
                                        return if (prefix.isNullOrEmpty()) {
                                            availableStubs.toMutableList()
                                        } else {
                                            availableStubs.filter { it.contains(prefix, ignoreCase = true) }.toMutableList()
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
                                { it.text.takeIf { it.isNotBlank() } ?: "" },
                                { component, text -> component.text = text },
                                parameters::activeStubsPackage.toMutableProperty()
                            )
                            .label("Stubs package: ")
                            .comment(
                                "Note: Library changes may not take effect immediately after IDE startup. " +
                                        "If the changes don't appear right away, close the settings, wait a few seconds and try again.<br>" +
                                        "Stubs authored by <a href=\"https://github.com/Josverl/micropython-stubs\">Jos Verlinde</a>", maxLineLength = 60
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

        val oldStubPackage = settings.state.activeStubsPackage ?: ""

        with(parameters) {
            settings.state.isPluginEnabled = isPluginEnabled
            settings.state.usingUart = usingUart
            settings.state.filterManufacturers = filterManufacturers
            settings.state.portName = portName.takeUnless { it == EMPTY_PORT_NAME_TEXT }
            settings.state.webReplUrl = webReplUrl
            settings.state.useFTP = useFTP
            settings.state.cacheFTPScript = cacheFTPScript

            val normalizedFTPScriptPath = normalizeMpyPath(cachedFTPScriptPath)

            cacheFTPScriptPathTextField.component.text = normalizedFTPScriptPath
            cachedFTPScriptPath = normalizedFTPScriptPath

            settings.state.cachedFTPScriptPath = normalizedFTPScriptPath
            settings.state.requireMinimumFTPUploadSize = requireMinimumFTPUploadSize
            settings.state.minimumFTPUploadSize = minimumFTPUploadSize
            settings.state.areStubsEnabled = areStubsEnabled
            settings.state.activeStubsPackage = activeStubsPackage

            runWithModalProgressBlocking(project, "Saving settings...") {
                settings.saveWebReplPassword(webReplPassword)
                settings.saveWifiCredentials(ssid, wifiPassword)
            }
        }

        pythonService.updateLibrary()

        //notifyStubsChanged(project, oldStubPackage, parameters.activeStubsPackage)
    }

    fun updatePortSelectModel(portSelectModel: MutableCollectionComboBoxModel<String>, isInitialUpdate: Boolean = false) {
        val lsPortsParam = if (isInitialUpdate) parameters.filterManufacturers else filterManufacturersCheckBox.component.isSelected

        val ports = transferService.listSerialPorts(lsPortsParam)

        portSelectModel.items
            .filterNot { it in ports || it == portSelectModel.selectedItem }
            .forEach { portSelectModel.remove(it) }

        val newPorts = ports.filterNot { portSelectModel.contains(it) }
        portSelectModel.addAll(portSelectModel.size, newPorts)

        if (isInitialUpdate || portSelectModel.isEmpty) {
            portSelectModel.selectedItem = if (parameters.portName.isBlank()) {
                EMPTY_PORT_NAME_TEXT
            } else {
                parameters.portName
            }
        }
    }
}