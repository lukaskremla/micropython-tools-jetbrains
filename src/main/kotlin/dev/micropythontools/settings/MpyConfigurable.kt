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
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.communication.State
import dev.micropythontools.ui.fileSystemWidget
import dev.micropythontools.util.MpyPythonService
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * @author Lukas Kremla
 */
private data class ConfigurableParameters(
    var isPluginEnabled: Boolean,
    var usingUart: Boolean,
    var portName: String,
    var webReplUrl: String,
    var webReplPassword: String,
    var ssid: String,
    var wifiPassword: String,
    var areStubsEnabled: Boolean,
    var activeStubsPackage: String
)

/**
 * @author Lukas Kremla
 */
class MpyConfigurable(private val project: Project) : BoundSearchableConfigurable("MicroPython Tools", "dev.micropythontools.settings") {
    private val settings = project.service<MpySettingsService>()
    private val pythonService = project.service<MpyPythonService>()
    private val transferService = project.service<MpyTransferService>()

    private val parameters = with(settings.state) {
        runWithModalProgressBlocking(project, "Retrieving settings...") {
            val wifiCredentials = settings.retrieveWifiCredentials()

            ConfigurableParameters(
                isPluginEnabled = isPluginEnabled,
                usingUart = usingUart,
                portName = if (portName.isNullOrBlank()) EMPTY_PORT_NAME_TEXT else portName.toString(),
                webReplUrl = webReplUrl ?: DEFAULT_WEBREPL_URL,
                webReplPassword = settings.retrieveWebReplPassword(),
                ssid = wifiCredentials.userName ?: "",
                wifiPassword = wifiCredentials.getPasswordAsString() ?: "",
                areStubsEnabled = areStubsEnabled,
                activeStubsPackage = activeStubsPackage ?: "",
            )
        }
    }

    private lateinit var pluginEnabledCheckBox: Cell<JBCheckBox>
    private lateinit var areStubsEnabled: Cell<JBCheckBox>

    private lateinit var serialRadioButton: Cell<JBRadioButton>
    private lateinit var webReplRadioButton: Cell<JBRadioButton>

    private lateinit var portSelectComboBox: Cell<ComboBox<String>>

    override fun createPanel(): DialogPanel {
        val portSelectModel = MutableCollectionComboBoxModel<String>()
        updatePortSelectModel(portSelectModel, true)

        val availableStubs = pythonService.getAvailableStubs()

        return panel {
            row {
                pluginEnabledCheckBox = checkBox("Enable MicroPython support")
                    .bindSelected(parameters::isPluginEnabled)
            }

            panel {
                group("Connection") {
                    buttonsGroup {
                        row {
                            label("Type: ")
                            serialRadioButton = radioButton("Serial")
                                .bindSelected(parameters::usingUart)

                            webReplRadioButton = radioButton("WebREPL")
                                .bindSelected({ !parameters.usingUart }, { parameters.usingUart = !it })
                        }
                    }

                    panel {
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

                    panel {
                        row {
                            textField()
                                .bindText(parameters::webReplUrl)
                                .label("URL: ")
                                .columns(40)
                                .validationInfo { field ->
                                    val msg = messageForBrokenUrl(field.text)
                                    msg?.let { error(it).withOKEnabled() }
                                }
                        }.layout(RowLayout.LABEL_ALIGNED)
                        row {
                            passwordField()
                                .bindText(parameters::webReplPassword)
                                .label("Password: ")
                                .comment("(4-9 symbols)")
                                .columns(40)
                                .validationInfo { field ->
                                    if (field.password.size !in WEBREPL_PASSWORD_LENGTH_RANGE) {
                                        error("Allowed password length is $WEBREPL_PASSWORD_LENGTH_RANGE").withOKEnabled()
                                    } else null
                                }
                        }.layout(RowLayout.LABEL_ALIGNED)
                    }.visibleIf(webReplRadioButton.selected)
                }.enabled(
                    (fileSystemWidget(project)?.state == State.DISCONNECTED ||
                            fileSystemWidget(project)?.state == State.DISCONNECTING)
                )

                group("FTP Upload Wi-Fi Credentials") {
                    row {
                        textField()
                            .bindText(parameters::ssid)
                            .label("SSID: ")
                            .columns(40)
                    }.layout(RowLayout.LABEL_ALIGNED)
                    row {
                        passwordField()
                            .bindText(parameters::wifiPassword)
                            .label("Password: ")
                            .columns(40)
                    }.layout(RowLayout.LABEL_ALIGNED)
                }

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
                                        "Stubs authored by <a href=\"https://github.com/Josverl/micropython-stubber\">Jos Verlinde</a>", maxLineLength = 60
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
                }
            }.enabledIf(pluginEnabledCheckBox.selected)
        }.apply {
            validateAll()
        }
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
            settings.state.isPluginEnabled = isPluginEnabled
            settings.state.usingUart = usingUart
            settings.state.portName = portName.takeUnless { it == EMPTY_PORT_NAME_TEXT }
            settings.state.webReplUrl = webReplUrl
            settings.state.areStubsEnabled = areStubsEnabled
            settings.state.activeStubsPackage = activeStubsPackage

            runWithModalProgressBlocking(project, "Saving settings...") {
                settings.saveWebReplPassword(webReplPassword)
                settings.saveWifiCredentials(ssid, wifiPassword)
            }
        }

        pythonService.updateLibrary()
    }

    fun updatePortSelectModel(portSelectModel: MutableCollectionComboBoxModel<String>, isInitialUpdate: Boolean = false) {
        val ports = transferService.listSerialPorts()

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