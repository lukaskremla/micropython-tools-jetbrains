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

package dev.micropythontools.actions

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.State
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.EMPTY_PORT_NAME_TEXT
import dev.micropythontools.settings.EMPTY_URL_TEXT
import dev.micropythontools.settings.MpySettingsService
import javax.swing.JComponent

internal class MpyAutoClearAction :
    CheckboxAction(
        MpyBundle.message("action.auto.clear.repl.text"),
        MpyBundle.message("action.auto.clear.repl.description"),
        null
    ),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun update(e: AnActionEvent) {
        super.update(e)

        e.presentation.isEnabled = e.project?.service<MpySettingsService>()?.state?.isPluginEnabled == true
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.service<MpySettingsService>()?.state?.autoClearRepl == true

    override fun setSelected(e: AnActionEvent, state: Boolean) =
        e.project?.service<MpySettingsService>()?.state?.autoClearRepl = state
}

internal class MpyConnectionSelectorAction : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun update(e: AnActionEvent) {
        super.update(e)

        val project = e.project

        val settings = project?.service<MpySettingsService>()
        val deviceService = project?.service<MpyDeviceService>()

        val isPluginEnabled = settings?.state?.isPluginEnabled == true
        val portName = settings?.state?.portName
        val uart = settings?.state?.usingUart
        val url = settings?.webReplUrl

        if (uart == true || uart == null) {
            e.presentation.text = if (portName == "" || portName == null) EMPTY_PORT_NAME_TEXT else portName
        } else {
            e.presentation.text = if (url == "") EMPTY_URL_TEXT else url
        }

        e.presentation.isEnabled =
            isPluginEnabled &&
                    (deviceService?.state == State.DISCONNECTED
                            || deviceService?.state == State.DISCONNECTING)

        // utilize this update method to ensure accurate FileSystemWidget empty text
        ApplicationManager.getApplication().invokeLater {
            deviceService?.fileSystemWidget?.updateEmptyText()
        }
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()

        val project = DataManager.getInstance().getDataContext(button).getData(CommonDataKeys.PROJECT)

        val settings = project?.let { MpySettingsService.getInstance(it) }

        val deviceService = project?.service<MpyDeviceService>()

        val portListing = deviceService?.listSerialPorts()

        portListing?.forEach { portName ->
            val action = object : AnAction(portName) {
                override fun actionPerformed(e: AnActionEvent) {
                    settings?.state?.usingUart = true
                    settings?.state?.portName = portName

                    project.save()
                }
            }
            group.add(action)
        }

        return group
    }
}