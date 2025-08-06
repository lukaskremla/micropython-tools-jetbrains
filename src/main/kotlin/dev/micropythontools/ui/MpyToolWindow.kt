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

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.DataManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.components.BorderLayoutPanel
import com.jediterm.terminal.TtyConnector
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.State
import dev.micropythontools.settings.EMPTY_PORT_NAME_TEXT
import dev.micropythontools.settings.EMPTY_URL_TEXT
import dev.micropythontools.settings.MpySettingsService
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import javax.swing.JComponent

internal const val NOTIFICATION_GROUP = "MicroPython Tools"
internal const val TOOL_WINDOW_ID = "MicroPython Tools"

/**
 * @author elmot, Lukas Kremla
 */
internal class MpyToolWindow() : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val deviceService = project.service<MpyDeviceService>()
        val componentRegistryService = project.service<MpyComponentRegistryService>()

        val newDisposable = Disposer.newDisposable(toolWindow.disposable, "MpyToolWindowDisposable")

        val fileSystemWidget = FileSystemWidget(project)
        val fileSystemContent = ContentFactory.getInstance().createContent(fileSystemWidget, "File System", true)
        fileSystemContent.setDisposer(newDisposable)
        toolWindow.contentManager.addContent(fileSystemContent)
        componentRegistryService.registerFileSystem(fileSystemWidget)

        val console = createConsoleView(project, newDisposable, deviceService.ttyConnector)
        val terminalContent = ContentFactory.getInstance().createContent(console.component, "REPL", true)
        terminalContent.setDisposer(newDisposable)
        toolWindow.contentManager.addContent(terminalContent)
        componentRegistryService.registerTerminalContent(terminalContent)
    }

    private fun createConsoleView(project: Project, disposable: Disposable, connector: TtyConnector): ConsoleView {
        val componentRegistryService = project.service<MpyComponentRegistryService>()
        val console = ConsoleViewImpl(project, false)
        componentRegistryService.registerConsole(console)
        Disposer.register(disposable, console)

        // Create a bridge between TtyConnector and ConsoleView
        val bridge = object : ProcessHandler() {
            override fun destroyProcessImpl() = notifyProcessTerminated(0)
            override fun detachProcessImpl() = notifyProcessTerminated(0)
            override fun detachIsDefault() = true
            override fun getProcessInput(): OutputStream = object : OutputStream() {
                override fun write(b: Int) {
                    val data = byteArrayOf(b.toByte())
                    val text = String(data, StandardCharsets.UTF_8).replace("\n", "\r\n")
                    connector.write(text.toByteArray(StandardCharsets.UTF_8))
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    val data = b.copyOfRange(off, off + len)
                    val text = String(data, StandardCharsets.UTF_8).replace("\n", "\r\n")
                    connector.write(text.toByteArray(StandardCharsets.UTF_8))
                }
            }
        }

        // Connect the console to the process handler
        console.attachToProcess(bridge)

        // Start thread to read from connector and write to console
        Thread {
            val buffer = CharArray(1024)
            try {
                while (true) {
                    val len = connector.read(buffer, 0, buffer.size)
                    if (len > 0) {
                        val text = String(buffer, 0, len)
                        console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
                    }
                    if (len < 0) break // EOF
                }
            } catch (e: Exception) {
                console.print("Device error: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
        }.start()

        // Add toolbar
        val widget = BorderLayoutPanel()
        widget.addToCenter(console.component)
        val actions = ActionManager.getInstance().getAction("micropythontools.repl.ReplToolbar") as ActionGroup
        val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, actions, true)
        actionToolbar.targetComponent = console.component
        widget.addToTop(actionToolbar.component)

        // Return the wrapped console
        return object : ConsoleView by console {
            override fun getComponent(): JComponent = widget
        }
    }
}

internal class AutoClearAction :
    CheckboxAction("Auto Clear REPL", "Automatically clear REPL console on upload/reset", null),
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

internal class ConnectionSelectorAction : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

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
