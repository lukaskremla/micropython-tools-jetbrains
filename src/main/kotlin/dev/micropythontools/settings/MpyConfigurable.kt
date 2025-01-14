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
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.dsl.builder.*
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.communication.State
import dev.micropythontools.ui.ConnectionParameters
import dev.micropythontools.ui.fileSystemWidget
import dev.micropythontools.util.MpyPythonService
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * @author Lukas Kremla
 */
class MpyConfigurable(private val project: Project) : BoundSearchableConfigurable("MicroPython Tools", "MicroPython Tools") {
    private val settings = project.service<MpySettingsService>()
    private val pythonService = project.service<MpyPythonService>()
    private val transferService = project.service<MpyTransferService>()

    private var isPluginEnabled = settings.state.isPluginEnabled
    private var areStubsEnabled = settings.state.areStubsEnabled

    private lateinit var settingsPanel: DialogPanel
    private lateinit var mainSettingsPanel: Panel

    private lateinit var serialPanel: Panel
    private lateinit var webReplPanel: Panel

    private lateinit var stubsPackageRow: Row

    private val maxPasswordLength = 4..9

    private val parameters = ConnectionParameters(
        usingUart = settings.state.usingUart,
        portName = settings.state.portName!!,
        webReplUrl = settings.state.webReplUrl!!,
        webReplPassword = "",
        ssid = "",
        wifiPassword = "",
        activeStubsPackage = settings.state.activeStubsPackage,
    )

    private var usingUartUI = parameters.usingUart

    override fun createPanel(): DialogPanel {
        runWithModalProgressBlocking(project, "Retrieving credentials...") {
            parameters.webReplPassword = project.service<MpySettingsService>().retrieveWebReplPassword()

            val wifiCredentials = project.service<MpySettingsService>().retrieveWifiCredentials()

            parameters.ssid = wifiCredentials.userName ?: ""
            parameters.wifiPassword = wifiCredentials.getPasswordAsString() ?: ""
        }

        val portSelectModel = MutableCollectionComboBoxModel<String>()
        if (parameters.portName.isNotBlank()) {
            portSelectModel.add(parameters.portName)
        }

        updatePortSelectModel(portSelectModel, true)

        val availableStubs = pythonService.getAvailableStubs()

        settingsPanel = panel {
            row {
                checkBox("Enable MicroPython support")
                    .bindSelected({ isPluginEnabled }) {
                        isPluginEnabled = it
                        mainSettingsPanel.enabled(it)
                    }
                    .applyToComponent {
                        addActionListener {
                            mainSettingsPanel.enabled(isSelected)
                        }
                    }
            }

            mainSettingsPanel = panel {
                group("Connection") {
                    buttonsGroup {
                        row {
                            label("Type: ")
                            radioButton("Serial")
                                .bindSelected(parameters::usingUart)
                                .applyToComponent {
                                    addActionListener {
                                        if (isSelected && !usingUartUI) {
                                            serialPanel.visible(true)
                                            webReplPanel.visible(false)
                                            usingUartUI = true
                                        }
                                    }
                                }
                            radioButton("WebREPL")
                                .bindSelected({ !parameters.usingUart }, { parameters.usingUart = !it })
                                .applyToComponent {
                                    addActionListener {
                                        toolTipText

                                        if (isSelected && usingUartUI) {
                                            serialPanel.visible(false)
                                            webReplPanel.visible(true)
                                            usingUartUI = false
                                        }
                                    }
                                }
                        }
                    }

                    serialPanel = panel {
                        row {
                            comboBox(portSelectModel)
                                .label("Port: ")
                                .columns(20)
                                .bindItem(
                                    { parameters.portName },
                                    { parameters.portName = it ?: "" }
                                )
                                .applyToComponent {
                                    selectedItem = parameters.portName.takeIf { it.isNotBlank() } ?: "No Port Selected"
                                    isEditable = false
                                    addPopupMenuListener(object : PopupMenuListener {
                                        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                                            updatePortSelectModel(portSelectModel)
                                        }

                                        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}

                                        override fun popupMenuCanceled(e: PopupMenuEvent?) {}
                                    })
                                }
                        }
                    }.visible(parameters.usingUart)

                    webReplPanel = panel {
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
                                .bindText(parameters::webReplPassword).label("Password: ")
                                .comment("(4-9 symbols)")
                                .columns(40)
                                .validationInfo { field ->
                                    if (field.password.size !in maxPasswordLength) {
                                        error("Allowed password length is $maxPasswordLength").withOKEnabled()
                                    } else null
                                }
                        }.layout(RowLayout.LABEL_ALIGNED)
                    }.visible(!parameters.usingUart)
                }.enabled(
                    (fileSystemWidget(project)?.state == State.DISCONNECTED ||
                            fileSystemWidget(project)?.state == State.DISCONNECTING)
                )

                group("FTP Upload Wi-Fi Credentials") {
                    row {
                        textField()
                            .bindText(parameters::ssid.toMutableProperty())
                            .label("SSID: ")
                            .columns(40)
                    }.layout(RowLayout.LABEL_ALIGNED)
                    row {
                        passwordField()
                            .bindText(parameters::wifiPassword.toMutableProperty())
                            .label("Password: ")
                            .columns(40)
                    }.layout(RowLayout.LABEL_ALIGNED)
                }

                group("MicroPython Stubs") {
                    row {
                        checkBox("Enable MicroPython stubs")
                            .bindSelected({ areStubsEnabled }) {
                                areStubsEnabled = it
                                stubsPackageRow.enabled(it)
                            }
                            .applyToComponent {
                                addActionListener {
                                    stubsPackageRow.enabled(isSelected)
                                }
                            }
                    }

                    stubsPackageRow = row {
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
                                { it.text.takeUnless { it.isEmpty() } },
                                { component, text -> component.text = text ?: "" },
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
                    }.enabled(areStubsEnabled)
                }
            }.enabled(isPluginEnabled)
        }.apply {
            validateAll()
        }

        return settingsPanel
    }

    override fun isModified(): Boolean {
        return settingsPanel.isModified()
    }

    override fun apply() {
        super.apply()

        val formattedPortName = if (parameters.portName != "No Port Selected") parameters.portName else ""

        settings.state.isPluginEnabled = isPluginEnabled
        settings.state.usingUart = parameters.usingUart
        settings.state.portName = formattedPortName
        settings.state.webReplUrl = parameters.webReplUrl
        settings.state.areStubsEnabled = areStubsEnabled
        settings.state.activeStubsPackage = parameters.activeStubsPackage

        runWithModalProgressBlocking(project, "Saving credentials...") {
            settings.saveWebReplPassword(parameters.webReplPassword)
            settings.saveWifiCredentials(parameters.ssid, parameters.wifiPassword)
        }

        pythonService.updateLibrary()
    }

    fun updatePortSelectModel(portSelectModel: MutableCollectionComboBoxModel<String>, isInitialUpdate: Boolean = false) {
        val ports = transferService.listSerialPorts()

        var i = 0
        while (i < portSelectModel.size) {
            val item = portSelectModel.items[i]

            if (item !in ports) {
                if (item != portSelectModel.selectedItem) {
                    portSelectModel.remove(item)
                }
            }

            i += 1
        }

        portSelectModel.addAll(portSelectModel.size, ports.filter { !portSelectModel.contains(it) })

        if (isInitialUpdate || portSelectModel.isEmpty) {
            if (parameters.portName.isBlank()) {
                portSelectModel.selectedItem = "No Port Selected"
            } else {
                portSelectModel.selectedItem = parameters.portName
            }
        }
    }
}