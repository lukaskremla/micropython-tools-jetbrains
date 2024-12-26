/*
 * Copyright 2000-2024 JetBrains s.r.o.
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

package dev.micropythontools.intellij.nova

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CheckboxAction
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
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider
import javax.swing.JComponent

/**
 * @author elmot
 */
internal const val NOTIFICATION_GROUP = "Micropython"
internal const val TOOL_WINDOW_ID = "com.jetbrains.micropython.nova.MicroPythonToolWindow"

class MicroPythonToolWindow : ToolWindowFactory, DumbAware {

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
        val actions = ActionManager.getInstance().getAction("micropython.repl.ReplToolbar") as ActionGroup
        val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, actions, true)
        actionToolbar.targetComponent = terminal
        widget.addToTop(actionToolbar.component)
        return widget

    }

}

class AutoClearAction : CheckboxAction("Auto Clear REPL", "Automatically clear REPL console on device reset/upload", null),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = isAutoClearEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) =
        PropertiesComponent.getInstance().setValue(PROPERTY_NAME, state, DEFAULT)

    companion object {
        private const val PROPERTY_NAME = "micropython.repl.autoClear"
        private const val DEFAULT = true
        val isAutoClearEnabled: Boolean
            get() = PropertiesComponent.getInstance().getBoolean(PROPERTY_NAME, DEFAULT)

    }
}

