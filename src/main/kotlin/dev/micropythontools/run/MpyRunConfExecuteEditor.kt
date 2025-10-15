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

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.MpySettingsService
import javax.swing.JComponent

private data class ExecuteParameters(
    var path: String,
    var switchToReplOnSuccess: Boolean
)

internal class MpyRunConfExecuteEditor(private val runConfiguration: MpyRunConfExecute) :
    SettingsEditor<MpyRunConfExecute>() {
    private val parameters = with(runConfiguration.options) {
        ExecuteParameters(
            path = path ?: "",
            switchToReplOnSuccess = switchToReplOnSuccess
        )
    }

    private lateinit var configurationPanel: DialogPanel

    override fun createEditor(): JComponent {
        configurationPanel = panel {
            row(MpyBundle.message("run.conf.execute.editor.label.source.path")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle(MpyBundle.message("run.conf.execute.editor.file.chooser.title"))
                        .withRoots(runConfiguration.project.guessProjectDir()),
                    runConfiguration.project
                ).apply {
                    component.text = parameters.path
                    component.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updatePath()
                        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updatePath()
                        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updatePath()

                        private fun updatePath() {
                            parameters.path = component.text
                        }
                    })
                }.align(AlignX.FILL)
            }
            row {
                checkBox(MpyBundle.message("run.conf.execute.editor.checkbox.switch.to.repl"))
                    .bindSelected(parameters::switchToReplOnSuccess)
            }
        }

        val isPluginEnabled = runConfiguration.project.service<MpySettingsService>().state.isPluginEnabled

        if (!isPluginEnabled) {
            configurationPanel.components.forEach {
                it.isEnabled = false
            }

            configurationPanel.toolTipText = MpyBundle.message("run.conf.tooltip.plugin.not.enabled")
        }

        return configurationPanel
    }

    override fun applyEditorTo(runConfiguration: MpyRunConfExecute) {
        configurationPanel.apply()

        with(parameters) {
            runConfiguration.saveOptions(
                path = path,
                switchToReplOnSuccess = switchToReplOnSuccess
            )
        }
    }

    override fun resetEditorFrom(runConfiguration: MpyRunConfExecute) = configurationPanel.reset()
}