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

package dev.micropythontools.intellij.nova

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionPlaces.TOOLWINDOW_CONTENT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.asSafely
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.containers.TreeTraversal
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import dev.micropythontools.intellij.settings.DEFAULT_WEBREPL_URL
import dev.micropythontools.intellij.settings.MpyProjectConfigurable
import dev.micropythontools.intellij.settings.MpySettingsService
import dev.micropythontools.intellij.settings.mpyFacet
import kotlinx.coroutines.*
import org.jetbrains.annotations.NonNls
import java.awt.BorderLayout
import java.io.IOException
import java.util.*
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * @authors elmot, Lukas Kremla
 */
data class ConnectionParameters(
    var usingUart: Boolean = true,
    var portName: String,
    var webReplUrl: String,
    var webReplPassword: String,
    var ssid: String,
    var wifiPassword: String
) {
    constructor(portName: String) : this(
        usingUart = true,
        portName = portName,
        webReplUrl = DEFAULT_WEBREPL_URL,
        webReplPassword = "",
        ssid = "",
        wifiPassword = ""
    )

    constructor(webReplUrl: String, webReplPassword: String) : this(
        usingUart = false,
        portName = "",
        webReplUrl = webReplUrl,
        webReplPassword = webReplPassword,
        ssid = "",
        wifiPassword = ""
    )
}

class FileSystemWidget(val project: Project, newDisposable: Disposable) :
    JBPanel<FileSystemWidget>(BorderLayout()) {
    var terminalContent: Content? = null
    val ttyConnector: TtyConnector
        get() = comm.ttyConnector
    private val tree: Tree = Tree(newTreeModel())

    private val comm: MpyComm = MpyComm().also { Disposer.register(newDisposable, it) }

    private val module = project.let { ModuleManager.getInstance(it).modules.firstOrNull() }

    val state: State
        get() = comm.state

    private fun newTreeModel() = DefaultTreeModel(DirNode("/", "/"), true)

    fun updateEmptyText() {
        tree.emptyText.clear()

        val isPythonSdkValid = module?.mpyFacet?.findValidPyhonSdk() != null

        val isPyserialInstalled = module?.mpyFacet?.isPyserialInstalled() ?: true // Facet might not be loaded yet

        if (module?.mpyFacet != null && isPythonSdkValid && isPyserialInstalled) {
            tree.emptyText.appendText("No board is connected")
            tree.emptyText.appendLine("Connect...", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                performReplAction(
                    project,
                    false,
                    "Connecting...",
                    false,
                    "Connection attempt cancelled",
                    { fileSystemWidget, reporter ->
                        doConnect(fileSystemWidget, reporter)
                    }
                )
            }
        } else if (module?.mpyFacet == null) {
            tree.emptyText.appendText("MicroPython support is disabled")
            tree.emptyText.appendLine("Change settings...", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyProjectConfigurable::class.java)
            }
        } else if (!isPythonSdkValid) {
            tree.emptyText.appendText("No Python interpreter is configured")
            tree.emptyText.appendLine("Configure...", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                if (PluginManager.isPluginInstalled(PluginId.getId("com.intellij.modules.java")) ||
                    PluginManager.isPluginInstalled(PluginId.getId("com.intellij.java"))
                ) {
                    ProjectSettingsService.getInstance(module.project).openModuleLibrarySettings(module)
                } else {
                    ShowSettingsUtil.getInstance().showSettingsDialog(module.project, "ProjectStructure")
                }
            }
        } else if (!isPyserialInstalled) {
            tree.emptyText.appendText("Missing required Python packages")
            tree.emptyText.appendLine("Install...", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                module.mpyFacet?.installRequiredPythonPackages()
            }
        }
    }

    init {
        updateEmptyText()

        tree.setCellRenderer(object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                value as FileSystemNode
                icon = when (value) {
                    is DirNode -> AllIcons.Nodes.Folder
                    is FileNode -> FileTypeRegistry.getInstance().getFileTypeByFileName(value.name).icon
                }
                append(value.name)
                if (value is FileNode) {
                    append("  ${value.size} bytes", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }

            }
        })
        val actionManager = ActionManager.getInstance()
        EditSourceOnDoubleClickHandler.install(tree) {
            val action = actionManager.getAction("micropythontools.repl.OpenFile")
            actionManager.tryToExecute(action, null, tree, TOOLWINDOW_CONTENT, true)
        }
        val popupActions = actionManager.getAction("micropythontools.repl.FSContextMenu") as ActionGroup
        PopupHandler.installFollowingSelectionTreePopup(tree, popupActions, ActionPlaces.POPUP)
        TreeUtil.installActions(tree)

        val actions = actionManager.getAction("micropythontools.repl.FSToolbar") as ActionGroup
        val actionToolbar = actionManager.createActionToolbar(ActionPlaces.TOOLBAR, actions, true)
        actionToolbar.targetComponent = this

        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(actionToolbar.component, BorderLayout.NORTH)
        comm.stateListeners.add {
            when (it) {
                State.CONNECTED, State.TTY_DETACHED -> {
                }

                State.DISCONNECTING,
                State.DISCONNECTED, State.CONNECTING -> {
                    ApplicationManager.getApplication().invokeLater({
                        tree.model = null
                    }, ModalityState.any())
                }
            }
        }
        tree.model = null
    }

    private fun calculateCRC32(file: VirtualFile): String {
        val localFileBytes = file.contentsToByteArray()
        val crc = java.util.zip.CRC32()
        crc.update(localFileBytes)
        return String.format("%08x", crc.value)
    }

    suspend fun synchronizeAndGetAlreadyUploadedFiles(
        uploadFilesToTargetPath: MutableMap<VirtualFile, String>,
        excludedPaths: List<String>,
        shouldSynchronize: Boolean,
        shouldExcludePaths: Boolean
    ): MutableList<VirtualFile> {
        val alreadyUploadedFiles = mutableListOf<VirtualFile>()
        val fileToUploadListing = mutableListOf<String>()
        val targetPathToFile = uploadFilesToTargetPath.entries.associate { (file, path) -> path to file }

        for (file in uploadFilesToTargetPath.keys) {
            val path = uploadFilesToTargetPath[file]
            val size = file.length
            val hash = calculateCRC32(file)

            fileToUploadListing.add("""("$path", $size, "$hash")""")
        }

        val scriptFileName = "synchronize_and_skip.py"

        val synchronizeAndSkipScript = module?.mpyFacet?.retrieveMpyScriptAsString(scriptFileName)
            ?: throw Exception("Failed to find: $scriptFileName")

        val formattedScript = synchronizeAndSkipScript.format(
            if (shouldSynchronize) "True" else "False",
            fileToUploadListing.joinToString(",\n        "),
            if (excludedPaths.isNotEmpty() && shouldExcludePaths) excludedPaths.joinToString(",\n        ") { "\"$it\"" } else ""
        )

        val scriptResponse = blindExecute(30000L, formattedScript).extractSingleResponse().trim()

        val matchingTargetPaths = when {
            !scriptResponse.contains("NO MATCHES") && !scriptResponse.contains("ERROR") -> scriptResponse
                .split("&")
                .filter { it.isNotEmpty() }

            else -> emptyList()
        }

        if (scriptResponse.contains("ERROR")) {
            Notifications.Bus.notify(
                Notification(
                    NOTIFICATION_GROUP,
                    "Failed to execute synchronize and check matches script: $scriptResponse",
                    NotificationType.ERROR
                ), project
            )
        }

        for (path in matchingTargetPaths) {
            targetPathToFile[path]?.let {
                alreadyUploadedFiles.add(it)
            }
        }

        return alreadyUploadedFiles
    }

    suspend fun initializeDevice() {
        val calendar = Calendar.getInstance()

        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)

        val scriptFileName = "initialize_device.py"

        val initializeDeviceScript = module?.mpyFacet?.retrieveMpyScriptAsString(scriptFileName)
            ?: throw Exception("Failed to find: $scriptFileName")

        val formattedScript = initializeDeviceScript.format(
            "($year, $month, $day, $hour, $minute, $second, 0, 0)"
        )

        // Try to sync the RTC, temporary feature, might not work on all boards and port versions
        blindExecute(TIMEOUT, formattedScript)

    }

    suspend fun refresh(reporter: RawProgressReporter) = refresh(reporter, isInitialRefresh = false)

    suspend fun initialRefresh(reporter: RawProgressReporter) = refresh(reporter, isInitialRefresh = true)

    private suspend fun refresh(reporter: RawProgressReporter, isInitialRefresh: Boolean) {
        reporter.text("Updating file system view...")
        reporter.fraction(null)

        comm.checkConnected()
        val newModel = newTreeModel()
        val dirList: String

        val scriptFileName = "scan_file_system.py"

        val fileSystemScanScript = module?.mpyFacet?.retrieveMpyScriptAsString(scriptFileName)
            ?: throw Exception("Failed to find: $scriptFileName")

        try {
            dirList = blindExecute(LONG_TIMEOUT, fileSystemScanScript).extractSingleResponse()
        } catch (e: CancellationException) {
            disconnect(reporter)
            // If this is the initial refresh the cancellation exception should be passed on as is, the user should
            // only be informed about the connection being cancelled. However, if this is not the initial refresh, the
            // cancellation exception should instead raise a more severe exception to be shown to the user as an error.
            // If the user cancels a non-initial refresh then, even if the user voluntarily chose to do so,
            // it puts the plugin into a situation where the file system listing can go out of sync with the actual
            // file system after a plugin-initiated change took place on the board. This means the plugin should
            // disconnect from the board and show an error message to the user.
            if (isInitialRefresh) {
                throw e
            } else {
                throw IOException("Micropython filesystem scan cancelled, the board has been disconnected", e)
            }
        } catch (e: Throwable) {
            disconnect(reporter)
            throw IOException("Micropython filesystem scan failed, ${e.localizedMessage}", e)
        }
        dirList.lines().filter { it.isNotBlank() }.forEach { line ->
            line.split('&').let { fields ->
                val flags = fields[0].toInt()
                val len = fields[1].toInt()
                val fullName = fields[2]
                val names = fullName.split('/')
                val folders = if (flags and 0x4000 == 0) names.dropLast(1) else names
                var currentFolder = newModel.root as DirNode
                folders.filter { it.isNotBlank() }.forEach { name ->
                    val child =
                        currentFolder.children().asSequence().find { (it as FileSystemNode).name == name }
                    when (child) {
                        is DirNode -> currentFolder = child
                        is FileNode -> Unit
                        null -> currentFolder = DirNode(fullName, name).also { currentFolder.add(it) }
                    }
                }
                if (flags and 0x4000 == 0) {
                    currentFolder.add(FileNode(fullName, names.last(), len))
                }
            }
        }
        TreeUtil.sort(newModel, object : Comparator<FileSystemNode> {
            override fun compare(node1: FileSystemNode, node2: FileSystemNode): Int {
                if ((node1 is DirNode) == (node2 is DirNode)) {
                    return node1.name.compareTo(node2.name)
                }
                return if (node1 is DirNode) -1 else 1
            }
        })
        withContext(Dispatchers.EDT) {
            val expandedPaths = TreeUtil.collectExpandedPaths(tree)
            val selectedPath = tree.selectionPath
            tree.model = newModel
            TreeUtil.restoreExpandedPaths(tree, expandedPaths)
            TreeUtil.selectPath(tree, selectedPath)
        }
    }

    suspend fun deleteCurrent(reporter: RawProgressReporter) {
        comm.checkConnected()
        val confirmedFileSystemNodes = withContext(Dispatchers.EDT) {
            val fileSystemNodes = tree.selectionPaths?.mapNotNull { it.lastPathComponent.asSafely<FileSystemNode>() }
                ?.filter { it.fullName != "" && it.fullName != "/" } ?: emptyList()
            val title: String
            val reporterText: String
            val message: String
            if (fileSystemNodes.isEmpty()) {
                return@withContext emptyList()
            } else if (fileSystemNodes.size == 1) {
                val fileName = fileSystemNodes[0].fullName
                if (fileSystemNodes[0] is DirNode) {
                    reporterText = "Deleting folder..."
                    title = "Delete folder $fileName"
                    message =
                        "Are you sure you want to delete the folder and its subtree?\n\rThis operation cannot be undone!"
                } else {
                    reporterText = "Deleting file..."
                    title = "Delete file $fileName"
                    message = "Are you sure you want to delete this file?\n\rThis operation cannot be undone!"
                }
            } else {
                reporterText = "Deleting item(s)..."
                title = "Delete item(s)?"
                message =
                    "Are you sure you want to delete ${fileSystemNodes.size} items?\n\rThis operation cannot be undone!"
            }

            reporter.text(reporterText)

            val sure = MessageDialogBuilder.yesNo(title, message).ask(project)
            if (sure) fileSystemNodes else emptyList()
        }
        for (confirmedFileSystemNode in confirmedFileSystemNodes) {
            val commands = mutableListOf("import os")
            TreeUtil.treeNodeTraverser(confirmedFileSystemNode)
                .traverse(TreeTraversal.POST_ORDER_DFS)
                .mapNotNull {
                    when (val node = it) {
                        is DirNode -> "os.rmdir('${node.fullName}')"
                        is FileNode -> "os.remove('${node.fullName}')"
                        else -> null
                    }
                }
                .toCollection(commands)

            blindExecute(LONG_TIMEOUT, *commands.toTypedArray()).extractResponse()
        }
    }

    fun selectedFiles(): Collection<FileSystemNode> {
        return tree.selectionPaths?.mapNotNull { it.lastPathComponent.asSafely<FileSystemNode>() } ?: emptyList()
    }

    suspend fun disconnect(reporter: RawProgressReporter) {
        val settings = MpySettingsService.getInstance(project)

        reporter.text("Disconnecting from ${settings.state.portName}")
        reporter.fraction(null)
        comm.disconnect()
    }

    @Throws(IOException::class)
    suspend fun upload(relativeName: @NonNls String, contentsToByteArray: ByteArray) {
        comm.upload(relativeName, contentsToByteArray)
    }

    @Throws(IOException::class)
    suspend fun download(deviceFileName: @NonNls String): ByteArray =
        comm.download(deviceFileName)

    @Throws(IOException::class)
    suspend fun instantRun(code: @NonNls String, showCode: Boolean) {
        withContext(Dispatchers.EDT) {
            activateRepl()
        }
        if (showCode) {
            val terminal = UIUtil.findComponentOfType(terminalContent?.component, JediTermWidget::class.java)?.terminal
            terminal?.apply {
                carriageReturn()
                newLine()
                code.lines().forEach {
                    val savedStyle = styleState.current
                    val inactive = NamedColorUtil.getInactiveTextColor()
                    val color = TerminalColor(inactive.red, inactive.green, inactive.blue)
                    styleState.reset()
                    styleState.current = styleState.current.toBuilder().setForeground(color).build()
                    writeUnwrappedString(it)
                    carriageReturn()
                    newLine()
                    styleState.current = savedStyle
                }
            }
        }
        comm.instantRun(code)
    }

    @RequiresEdt
    fun activateRepl(): Content? = terminalContent?.apply {
        project.service<ToolWindowManager>().getToolWindow(TOOL_WINDOW_ID)?.activate(null, true, true)
        manager?.setSelectedContent(this)
    }

    fun reset() = comm.reset()

    @Throws(IOException::class)
    suspend fun blindExecute(commandTimeout: Long, vararg commands: String): ExecResponse {
        clearTerminalIfNeeded()
        return comm.blindExecute(commandTimeout, *commands)
    }

    internal suspend fun clearTerminalIfNeeded() {
        if (AutoClearAction.isAutoClearEnabled) {
            withContext(Dispatchers.EDT) {
                val widget =
                    UIUtil.findComponentOfType(terminalContent?.component, JediTermWidget::class.java)
                widget?.terminalPanel?.clearBuffer()
            }
        }
    }

    @Throws(IOException::class)
    suspend fun connect() = comm.connect()

    fun setConnectionParams(connectionParameters: ConnectionParameters) = comm.setConnectionParams(connectionParameters)
    fun interrupt() {
        comm.interrupt()
    }

}

sealed class FileSystemNode(@NonNls val fullName: String, @NonNls val name: String) : DefaultMutableTreeNode() {
    override fun toString(): String = name

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        return fullName == (other as FileSystemNode).fullName
    }

    override fun hashCode(): Int {
        return fullName.hashCode()
    }
}

class FileNode(fullName: String, name: String, val size: Int) : FileSystemNode(fullName, name) {
    override fun getAllowsChildren(): Boolean = false
    override fun isLeaf(): Boolean = true
}

class DirNode(fullName: String, name: String) : FileSystemNode(fullName, name) {
    override fun getAllowsChildren(): Boolean = true
}