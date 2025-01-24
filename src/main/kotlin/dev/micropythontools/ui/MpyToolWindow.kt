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

package dev.micropythontools.ui

import com.intellij.ide.DataManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.components.BorderLayoutPanel
import com.jediterm.terminal.TerminalMode
import com.jediterm.terminal.TtyConnector
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.communication.State
import dev.micropythontools.settings.MpySettingsService
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider
import javax.swing.JComponent

internal const val NOTIFICATION_GROUP = "MicroPython Tools"
internal const val TOOL_WINDOW_ID = "MicroPython Tools"

/**
 * @author elmot, Lukas Kremla
 */
class MpyToolWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val newDisposable = Disposer.newDisposable(toolWindow.disposable, "Webrepl")

        val fileSystemWidget = FileSystemWidget(project, newDisposable)
        val fileSystemContent = ContentFactory.getInstance().createContent(fileSystemWidget, "File System", true)
        fileSystemContent.setDisposer(newDisposable)
        toolWindow.contentManager.addContent(fileSystemContent)

        val jediTermWidget = jediTermWidget(project, newDisposable, fileSystemWidget.ttyConnector)
        val terminalContent = ContentFactory.getInstance().createContent(jediTermWidget, "REPL", true)
        terminalContent.setDisposer(newDisposable)
        toolWindow.contentManager.addContent(terminalContent)
        fileSystemWidget.terminalContent = terminalContent

        val mpyDeviceWidget = MpyDeviceWidget(project, fileSystemWidget)
        val mpyDeviceWidgetContent = ContentFactory.getInstance().createContent(mpyDeviceWidget, "Device", true)
        toolWindow.contentManager.addContent(mpyDeviceWidgetContent)
    }

    private fun jediTermWidget(project: Project, disposable: Disposable, connector: TtyConnector): JComponent {
        val mySettingsProvider = JBTerminalSystemSettingsProvider()
        val terminal = JBTerminalWidget(project, mySettingsProvider, disposable)
        terminal.isEnabled = false
        with(terminal.terminal) {
            setModeEnabled(TerminalMode.ANSI, true)
            setModeEnabled(TerminalMode.AutoNewLine, true)
            setModeEnabled(TerminalMode.WideColumn, true)
        }
        terminal.ttyConnector = connector
        terminal.start()

        val widget = BorderLayoutPanel()
        widget.addToCenter(terminal)
        val actions = ActionManager.getInstance().getAction("micropythontools.repl.ReplToolbar") as ActionGroup
        val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, actions, true)
        actionToolbar.targetComponent = terminal
        widget.addToTop(actionToolbar.component)
        return widget
    }
}

class AutoClearAction :
    CheckboxAction("Auto Clear REPL", "Automatically clear REPL console on device reset/upload", null),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)

        val settings = e.project?.service<MpySettingsService>()

        e.presentation.isEnabled = settings?.state?.isPluginEnabled == true
    }

    override fun isSelected(e: AnActionEvent): Boolean = isAutoClearEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) =
        PropertiesComponent.getInstance().setValue(PROPERTY_NAME, state, DEFAULT)

    companion object {
        private const val PROPERTY_NAME = "micropythontools.repl.autoClear"
        private const val DEFAULT = true
        val isAutoClearEnabled: Boolean
            get() = PropertiesComponent.getInstance().getBoolean(PROPERTY_NAME, DEFAULT)
    }
}

class ConnectionSelectorAction : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)

        val project = e.project

        val settings = project?.let { MpySettingsService.getInstance(it) }

        val isPluginEnabled = settings?.state?.isPluginEnabled == true
        val portName = settings?.state?.portName
        val uart = settings?.state?.usingUart
        val url = settings?.state?.webReplUrl

        if (uart == true || uart == null) {
            e.presentation.text = if (portName == "" || portName == null) "No Port Selected" else portName
        } else {
            e.presentation.text = if (url == "") "No URL Selected" else url
        }

        e.presentation.isEnabled =
            isPluginEnabled &&
                    (fileSystemWidget(project)?.state == State.DISCONNECTED
                            || fileSystemWidget(project)?.state == State.DISCONNECTING)
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()

        val project = DataManager.getInstance().getDataContext(button).getData(CommonDataKeys.PROJECT)

        val settings = project?.let { MpySettingsService.getInstance(it) }

        val transferService = project?.service<MpyTransferService>()

        val portListing = transferService?.listSerialPorts()

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