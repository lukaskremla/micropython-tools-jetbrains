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

package dev.micropythontools.intellij.settings

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.dsl.builder.*
import com.intellij.util.asSafely
import com.intellij.util.ui.UIUtil
import dev.micropythontools.intellij.nova.ConnectionParameters
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * @author vlan, elmot
 */
class MpySettingsPanel(private val module: Module, disposable: Disposable) : JPanel(BorderLayout()) {
    private val parameters = MpySettingsService.getInstance(module.project).let { settings ->
        ConnectionParameters(
            usingUart = settings.state.usingUart,
            portName = settings.state.portName ?: "",
            webReplUrl = settings.state.webReplUrl ?: DEFAULT_WEBREPL_URL,
            webReplPassword = "",
            ssid = "",
            wifiPassword = "",
            activeStubsPackage = settings.state.activeStubsPackage,
        )
    }

    private val settingsPanel: DialogPanel

    init {
        border = IdeBorderFactory.createEmptyBorder(UIUtil.PANEL_SMALL_INSETS)
        runWithModalProgressBlocking(module.project, "Retrieving credentials...") {
            parameters.webReplPassword = module.project.service<MpySettingsService>().retrieveWebReplPassword()

            val wifiCredentials = module.project.service<MpySettingsService>().retrieveWifiCredentials()

            parameters.ssid = wifiCredentials.userName ?: ""
            parameters.wifiPassword = wifiCredentials.getPasswordAsString() ?: ""
        }

        val portSelectModel = MutableCollectionComboBoxModel<String>()
        if (parameters.portName.isNotBlank()) {
            portSelectModel.add(parameters.portName)
        }

        refreshAPortSelectModel(module, portSelectModel)

        val availableStubs = module.mpyFacet?.getAvailableStubs() ?: emptyList()

        settingsPanel = panel {
            group("Connection") {
                buttonsGroup {
                    row {
                        radioButton("Serial").bindSelected(parameters::usingUart)
                        comboBox(portSelectModel)
                            .label("Port:")
                            .columns(20)
                            .bind(
                                { it.editor.item.toString() },
                                { component, text -> component.selectedItem = text },
                                parameters::portName.toMutableProperty()
                            )
                            .validationInfo { comboBox ->
                                val portName = comboBox.selectedItem.asSafely<String>()

                                val isPyserialInstalled = module.mpyFacet?.isPyserialInstalled()

                                if (module.mpyFacet?.findValidPyhonSdk() == null) {
                                    ValidationInfo("MicroPython Tools plugin requires a valid Python 3.10+ SDK")
                                } else if (isPyserialInstalled == false) {
                                    ValidationInfo("Required Python packages are missing. Check the tool window for more info")
                                } else if (isPyserialInstalled == null) {
                                    ValidationInfo("Wait for python library manager initialization, please reopen the settings...")
                                } else if (portName.isNullOrBlank()) {
                                    ValidationInfo("No port name provided")
                                        .withOKEnabled()
                                } else if (!portSelectModel.contains(portName)) {
                                    ValidationInfo("Unknown port name")
                                        .asWarning()
                                        .withOKEnabled()
                                } else null
                            }
                            .applyToComponent {
                                isEditable = true
                                addPopupMenuListener(object : PopupMenuListener {
                                    override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                                        refreshAPortSelectModel(module, portSelectModel)
                                    }

                                    override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
                                    override fun popupMenuCanceled(e: PopupMenuEvent?) {}
                                })
                            }
                    }
                    separator()
                    row {
                        radioButton("WebREPL").bindSelected({ !parameters.usingUart }, { parameters.usingUart = !it })
                    }
                    indent {
                        row {
                            textField().bindText(parameters::webReplUrl).label("URL: ").columns(40)
                                .validationInfo { field ->
                                    val msg = messageForBrokenUrl(field.text)
                                    msg?.let { error(it).withOKEnabled() }
                                }
                        }.layout(RowLayout.LABEL_ALIGNED)
                        row {
                            passwordField().bindText(parameters::webReplPassword).label("Password: ")
                                .comment("(4-9 symbols)")
                                .columns(40)
                                .validationInfo { field ->
                                    if (field.password.size !in PASSWORD_LENGTH && !parameters.usingUart) {
                                        error("Allowed password length is $PASSWORD_LENGTH").withOKEnabled()
                                    } else null
                                }
                        }.layout(RowLayout.LABEL_ALIGNED)
                    }
                }
            }

            group("FTP Upload Wi-Fi Credentials") {
                indent {
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
            }

            group("MicroPython Stubs") {
                row {
                    cell(
                        TextFieldWithAutoCompletion(
                            module.project,
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
                            ""
                        ).apply {
                            setPreferredWidth(450)
                        }
                    )
                        .bind(
                            { it.text },
                            { component, text -> component.text = text ?: "" },
                            parameters::activeStubsPackage.toMutableProperty()
                        )
                        .label("Stubs package: ")
                        .comment(
                            "Type \"micropython\" to browse available packages, or leave it empty to disable built-in stubs. " +
                                    "Stubs authored by <a href=\"https://github.com/Josverl/micropython-stubber\">Jos Verlinde</a>", maxLineLength = 60
                        )
                        .validationInfo { field ->
                            val text = field.text

                            if (text.isEmpty()) {
                                ValidationInfo("No built-in stubs will be active!").asWarning().withOKEnabled()
                            } else if (!availableStubs.contains(text)) {
                                ValidationInfo("Invalid stubs package name!")
                            } else null
                        }
                }
            }

        }.apply {
            registerValidators(disposable)
            validateAll()
        }

        add(settingsPanel, BorderLayout.CENTER)
    }

    fun isModified(): Boolean {
        return settingsPanel.isModified()
    }

    fun apply() {
        settingsPanel.apply()
        val settings = MpySettingsService.getInstance(module.project)

        settings.state.usingUart = parameters.usingUart
        settings.state.portName = parameters.portName
        settings.state.webReplUrl = parameters.webReplUrl
        settings.state.activeStubsPackage = parameters.activeStubsPackage

        runWithModalProgressBlocking(module.project, "Saving credentials...") {
            module.project.service<MpySettingsService>()
                .saveWebReplPassword(parameters.webReplPassword)

            module.project.service<MpySettingsService>()
                .saveWifiCredentials(parameters.ssid, parameters.wifiPassword)
        }
    }

    fun reset() {
        settingsPanel.reset()
    }

    fun refreshAPortSelectModel(module: Module, portSelectModel: MutableCollectionComboBoxModel<String>) {
        val ports = module.mpyFacet?.listSerialPorts(module.project) ?: emptyList()

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

        if (portSelectModel.selectedItem.asSafely<String>().isNullOrBlank() && !portSelectModel.isEmpty) {
            portSelectModel.selectedItem = portSelectModel.items.firstOrNull()
        }
    }
}

private val PASSWORD_LENGTH = 4..9
