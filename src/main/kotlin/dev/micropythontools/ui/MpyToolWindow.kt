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

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
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
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.icons.MpyIcons
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider
import javax.swing.Icon
import javax.swing.JComponent

internal class MpyToolWindow() : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val deviceService = project.service<MpyDeviceService>()
        val componentRegistryService = project.service<MpyComponentRegistryService>()

        val newDisposable = Disposer.newDisposable(toolWindow.disposable, "MpyToolWindowDisposable")

        val fileSystemWidget = FileSystemWidget(project)
        val fileSystemContent = ContentFactory.getInstance()
            .createContent(fileSystemWidget, MpyBundle.message("toolwindow.file.system.tab.title"), true)
        fileSystemContent.setDisposer(newDisposable)
        toolWindow.contentManager.addContent(fileSystemContent)
        componentRegistryService.registerFileSystem(fileSystemWidget)

        val jediTermWidget = jediTermWidget(project, newDisposable, deviceService.ttyConnector)
        val terminalContent = ContentFactory.getInstance()
            .createContent(jediTermWidget, MpyBundle.message("toolwindow.repl.tab.title"), true)
        terminalContent.setDisposer(newDisposable)
        toolWindow.contentManager.addContent(terminalContent)
        componentRegistryService.registerTerminalContent(terminalContent)
        componentRegistryService.registerToolWindow(toolWindow)
        toolWindow.stripeTitle = "Kerel"
    }

    override val icon: Icon
        get() = MpyIcons.micropythonTw

    private fun jediTermWidget(project: Project, disposable: Disposable, connector: TtyConnector): JComponent {
        val componentRegistryService = project.service<MpyComponentRegistryService>()

        val mySettingsProvider = JBTerminalSystemSettingsProvider()
        val terminal = JBTerminalWidget(project, mySettingsProvider, disposable)
        componentRegistryService.registerTerminal(terminal)
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
        val actions = ActionManager.getInstance().getAction("dev.micropythontools.repl.ReplToolbarGroup") as ActionGroup
        val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, actions, true)
        actionToolbar.targetComponent = terminal
        widget.addToTop(actionToolbar.component)
        return widget
    }
}