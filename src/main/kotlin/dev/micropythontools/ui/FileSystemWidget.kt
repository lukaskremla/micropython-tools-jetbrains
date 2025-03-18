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

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.dnd.TransferableWrapper
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
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
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
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import dev.micropythontools.communication.*
import dev.micropythontools.settings.DEFAULT_WEBREPL_URL
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.settings.messageForBrokenUrl
import dev.micropythontools.util.MpyPythonService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.NonNls
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

data class DeviceInformation(
    var version: String = "Unknown",
    var description: String = "Unknown",
    var hasBinascii: Boolean = false
)

data class ConnectionParameters(
    var usingUart: Boolean = true,
    var portName: String,
    var webReplUrl: String,
    var webReplPassword: String,
    var ssid: String,
    var wifiPassword: String,
    var activeStubsPackage: String? = null
) {
    constructor(portName: String) : this(
        usingUart = true,
        portName = portName,
        webReplUrl = DEFAULT_WEBREPL_URL,
        webReplPassword = "",
        ssid = "",
        wifiPassword = "",
        activeStubsPackage = ""
    )

    constructor(webReplUrl: String, webReplPassword: String) : this(
        usingUart = false,
        portName = "",
        webReplUrl = webReplUrl,
        webReplPassword = webReplPassword,
        ssid = "",
        wifiPassword = "",
        activeStubsPackage = null
    )
}

/**
 * @authors elmot, Lukas Kremla
 */
class FileSystemWidget(val project: Project, newDisposable: Disposable) :
    JBPanel<FileSystemWidget>(BorderLayout()) {
    var terminalContent: Content? = null
    val ttyConnector: TtyConnector
        get() = comm.ttyConnector
    val tree: Tree = Tree(newTreeModel())

    private val comm: MpyComm = MpyComm(this).also { Disposer.register(newDisposable, it) }

    private val settings = project.service<MpySettingsService>()
    private val pythonService = project.service<MpyPythonService>()
    private val transferService = project.service<MpyTransferService>()

    val state: State
        get() = comm.state

    var deviceInformation: DeviceInformation = DeviceInformation()

    private fun newTreeModel() = DefaultTreeModel(DirNode("/", "/"), true)

    fun updateEmptyText() {
        tree.emptyText.clear()

        if (!settings.state.isPluginEnabled) {
            tree.emptyText.appendText("MicroPython support is disabled")
            tree.emptyText.appendLine("Change settings...", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java)
            }
        } else {
            tree.emptyText.appendText("No board is connected")
            tree.emptyText.appendLine("Connect...", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                performReplAction(
                    project,
                    false,
                    "Connecting...",
                    false,
                    "Connection attempt cancelled",
                    { _, reporter ->
                        doConnect(reporter)
                    }
                )
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
                icon = when {
                    value is DirNode && !value.isVolume -> AllIcons.Nodes.Folder
                    value is DirNode && value.isVolume -> IconLoader.getIcon("/icons/volume.svg", FileSystemWidget::class.java)
                    else -> FileTypeRegistry.getInstance().getFileTypeByFileName(value.name).icon
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
        tree.dragEnabled = true
        tree.dropMode = DropMode.ON

        tree.transferHandler = object : TransferHandler() {
            private val nodesFlavor = DataFlavor(Array<FileSystemNode>::class.java, "Array of MicroPython file system nodes")
            private val virtualFileFlavor = DataFlavor("application/x-java-file-list; class=java.util.List", "List of virtual files")
            private val flavors = arrayOf(nodesFlavor, virtualFileFlavor)

            private fun filterOutChildSelections(nodes: Array<FileSystemNode>): Array<FileSystemNode> {
                return nodes.filter { node ->
                    nodes.none { potentialParent ->
                        potentialParent != node && node.fullName.startsWith(potentialParent.fullName + "/")
                    }
                }.toTypedArray()
            }

            override fun createTransferable(c: JComponent): Transferable? {
                val tree = c as Tree
                val nodes = tree.selectionPaths?.mapNotNull {
                    it.lastPathComponent as? FileSystemNode
                }?.toTypedArray() ?: return null

                val filteredNodes = filterOutChildSelections(nodes)

                return object : Transferable {
                    override fun getTransferDataFlavors() = arrayOf(nodesFlavor)
                    override fun isDataFlavorSupported(flavor: DataFlavor) = nodesFlavor == flavor
                    override fun getTransferData(flavor: DataFlavor): Any {
                        if (flavor == nodesFlavor) return filteredNodes
                        throw UnsupportedFlavorException(flavor)
                    }
                }
            }

            override fun getSourceActions(c: JComponent) = MOVE

            override fun canImport(support: TransferSupport): Boolean {
                if (!support.isDrop) return false
                if (!flavors.any { support.isDataFlavorSupported(it) }) return false

                val dropLocation = support.dropLocation as? JTree.DropLocation ?: return false

                if (dropLocation.path == null) {
                    return support.isDataFlavorSupported(virtualFileFlavor)
                }

                val targetNode = dropLocation.path.lastPathComponent as? DirNode ?: return false

                if (support.isDataFlavorSupported(nodesFlavor)) {
                    val nodes = try {
                        @Suppress("UNCHECKED_CAST")
                        support.transferable.getTransferData(nodesFlavor) as Array<FileSystemNode>
                    } catch (_: Exception) {
                        return false
                    }

                    return !nodes.any { node ->
                        targetNode.fullName.startsWith(node.fullName)
                    }
                }

                if (support.isDataFlavorSupported(virtualFileFlavor)) {
                    val transferData = support.transferable.getTransferData(virtualFileFlavor) as TransferableWrapper

                    val files = transferData.asFileList()
                        ?.mapNotNull { StandardFileSystems.local().findFileByPath(it.path) }
                        ?.toSet() ?: emptySet()

                    val excludedFolders = transferService.collectExcluded()

                    return !files.all { file ->
                        excludedFolders.any { VfsUtil.isAncestor(it, file, false) }
                    }
                }

                return false
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false

                val dropLocation = support.dropLocation as? JTree.DropLocation ?: return false
                val targetNode = dropLocation.path.lastPathComponent as? DirNode ?: return false

                when {
                    support.isDataFlavorSupported(nodesFlavor) -> {
                        val nodes = try {
                            @Suppress("UNCHECKED_CAST")
                            support.transferable.getTransferData(nodesFlavor) as Array<FileSystemNode>
                        } catch (_: Exception) {
                            return false
                        }

                        val result = performReplAction(
                            project = project,
                            connectionRequired = false,
                            description = "Moving files...",
                            false,
                            cancelledMessage = "Move operation cancelled",
                            { _, reporter ->
                                var sure = false

                                withContext(Dispatchers.EDT) {
                                    sure = MessageDialogBuilder.yesNo(
                                        "Move Dropped Item(s)",
                                        "Are you sure you want to move the Dropped items?"
                                    ).ask(project)
                                }

                                if (!sure) return@performReplAction PerformReplActionResult(null, false)

                                reporter.text("Checking for move conflicts...")
                                reporter.fraction(null)

                                quietRefresh(reporter)

                                val targetChildren = targetNode.children().asSequence()
                                    .mapNotNull { it as? FileSystemNode }
                                    .toList()

                                val targetChildNames = targetChildren.map { it.name }

                                val foundConflicts = (nodes.any {
                                    targetChildNames.contains(it.name)
                                })

                                if (foundConflicts) {
                                    var wasOverwriteConfirmed = false

                                    withContext(Dispatchers.EDT) {
                                        val clickResult = MessageDialogBuilder.Message(
                                            "Overwrite Destination Paths?",
                                            "One or more of the items being moved already exists in the target location. " +
                                                    "Do you want to overwrite the destination item(s)?"
                                        ).asWarning().buttons("Replace", "Cancel").defaultButton("Cancel").show(project)

                                        wasOverwriteConfirmed = clickResult == "Replace"
                                    }

                                    if (!wasOverwriteConfirmed) return@performReplAction false
                                }

                                reporter.text("Moving file system items...")
                                reporter.fraction(null)

                                val commands = mutableListOf("import os")

                                val currentPathToNewPath = mutableMapOf<String, String>()
                                val pathsToRemove = mutableSetOf<String>()
                                nodes.forEach { node ->
                                    val newPath = "${targetNode.fullName}/${node.name}"
                                    currentPathToNewPath[node.fullName] = newPath

                                    if (foundConflicts && targetChildNames.contains(node.name)) pathsToRemove.add(newPath)
                                    commands.add("os.rename(\"${node.fullName}\", \"$newPath\")")
                                }

                                transferService.recursivelySafeDeletePaths(pathsToRemove)

                                blindExecute(LONG_TIMEOUT, *commands.toTypedArray()).extractResponse()

                                refresh(reporter)
                            },
                            { _, reporter ->
                                refresh(reporter)
                            }
                        )
                        if (result == false) return false
                    }

                    support.isDataFlavorSupported(virtualFileFlavor) -> {
                        val filesToUpload = try {
                            val transferData = support.transferable.getTransferData(virtualFileFlavor) as TransferableWrapper

                            transferData.asFileList()
                                ?.mapNotNull { StandardFileSystems.local().findFileByPath(it.path) }
                                ?.toSet() ?: emptySet()
                        } catch (e: Exception) {
                            Notifications.Bus.notify(
                                Notification(
                                    NOTIFICATION_GROUP,
                                    "Drag and drop file collection failed: $e",
                                    NotificationType.ERROR
                                ), project
                            )

                            return false
                        }

                        val transferService = project.service<MpyTransferService>()

                        val sure = MessageDialogBuilder.yesNo(
                            "Upload Dropped Item(s)",
                            "Are you sure you want to upload the dropped items?"
                        ).ask(project)

                        if (!sure) return false

                        val sanitizedFiles = filesToUpload.filter { candidate ->
                            filesToUpload.none { potentialParent ->
                                VfsUtil.isAncestor(potentialParent, candidate, true)
                            }
                        }.toSet()

                        val parentFolders = filesToUpload
                            .map { it.parent }
                            .toSet()

                        transferService.performUpload(
                            initialFilesToUpload = sanitizedFiles,
                            relativeToFolders = parentFolders,
                            targetDestination = targetNode.fullName
                        )
                    }
                }
                return true
            }
        }
    }

    private suspend fun initializeDevice() {
        /*val calendar = Calendar.getInstance()

        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)*/

        val scriptFileName = "initialize_device.py"

        val initializeDeviceScript = pythonService.retrieveMpyScriptAsString(scriptFileName)

        /*val formattedScript = initializeDeviceScript.format(
            "($year, $month, $day, $hour, $minute, $second, 0, 0)"
        )*/

        // Try to sync the RTC, temporary feature, might not work on all boards and port versions
        val scriptResponse = blindExecute(SHORT_TIMEOUT, initializeDeviceScript).extractSingleResponse()

        if (!scriptResponse.contains("ERROR")) {
            val (version, description, hasBinascii) = scriptResponse.split("&")

            deviceInformation = DeviceInformation(version, description, hasBinascii == "True")
        } else {
            deviceInformation = DeviceInformation()
        }

        if (!deviceInformation.hasBinascii) {
            MessageDialogBuilder.Message(
                "Missing MicroPython Libraries", "The connected board is missing the full binascii " +
                        "library. The already uploaded files check won't work and uploads may be slower."
            ).asWarning().buttons("Acknowledge").show(project)
        }
    }

    suspend fun doConnect(reporter: RawProgressReporter) {
        try {
            if (state == State.CONNECTED) return

            val settings = project.service<MpySettingsService>()

            val device = if (settings.state.usingUart) settings.state.portName else settings.state.webReplUrl
            reporter.text("Connecting to $device")
            reporter.fraction(null)

            var msg: String? = null
            val connectionParameters: ConnectionParameters?
            if (settings.state.usingUart) {
                val portName = settings.state.portName ?: ""
                if (portName.isBlank()) {
                    msg = "No port is selected"
                    connectionParameters = null
                } else {
                    connectionParameters = ConnectionParameters(portName)
                }

            } else {
                val url = settings.state.webReplUrl ?: DEFAULT_WEBREPL_URL
                val password = withContext(Dispatchers.EDT) {
                    runWithModalProgressBlocking(project, "Retrieving credentials...") {
                        project.service<MpySettingsService>().retrieveWebReplPassword()
                    }
                }

                msg = messageForBrokenUrl(url)
                if (password.isBlank()) {
                    msg = "Empty password"
                    connectionParameters = null
                } else {
                    connectionParameters = ConnectionParameters(url, password)
                }
            }
            if (msg != null) {
                withContext(Dispatchers.EDT) {
                    val result = Messages.showIdeaMessageDialog(
                        project,
                        msg,
                        "Cannot Connect",
                        arrayOf("OK", "Settings..."),
                        1,
                        AllIcons.General.ErrorDialog,
                        null
                    )
                    if (result == 1) {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, MpyConfigurable::class.java)
                    }
                }
            } else {
                if (connectionParameters != null) {
                    setConnectionParams(connectionParameters)
                    connect()
                    try {
                        if (state == State.CONNECTED) {
                            initializeDevice()
                            initialRefresh(reporter)
                        }
                    } finally {
                        ActivityTracker.getInstance().inc()
                    }
                }
            }
        } catch (_: CancellationException) {
            disconnect(reporter)
        }
    }

    suspend fun refresh(reporter: RawProgressReporter) =
        doRefresh(reporter, hash = false, disconnectOnCancel = true, isInitialRefresh = false, useReporter = true)

    suspend fun quietRefresh(reporter: RawProgressReporter) =
        doRefresh(reporter, hash = false, disconnectOnCancel = false, isInitialRefresh = false, useReporter = false)

    suspend fun quietHashingRefresh(reporter: RawProgressReporter) =
        doRefresh(reporter, hash = true, disconnectOnCancel = false, isInitialRefresh = false, useReporter = false)

    private suspend fun initialRefresh(reporter: RawProgressReporter) =
        doRefresh(reporter, hash = false, disconnectOnCancel = false, isInitialRefresh = true, useReporter = true)

    private suspend fun doRefresh(
        reporter: RawProgressReporter,
        hash: Boolean,
        disconnectOnCancel: Boolean,
        isInitialRefresh: Boolean,
        useReporter: Boolean
    ) {
        if (useReporter) {
            reporter.text("Updating file system view...")
            reporter.fraction(null)
        }

        comm.checkConnected()
        val newModel = newTreeModel()
        val dirList: String

        val scriptFileName = if (hash) "scan_file_system_hashing.py" else "scan_file_system.py"

        val fileSystemScanScript = pythonService.retrieveMpyScriptAsString(scriptFileName)

        try {
            dirList = blindExecute(LONG_TIMEOUT, fileSystemScanScript).extractSingleResponse()
        } catch (e: CancellationException) {
            if (disconnectOnCancel) {
                disconnect(reporter)
            }
            // If this is the initial refresh the cancellation exception should be passed on as is, the user should
            // only be informed about the connection being cancelled. However, if this is not the initial refresh, the
            // cancellation exception should instead raise a more severe exception to be shown to the user as an error.
            // If the user cancels a non-initial refresh then, even if the user voluntarily chose to do so,
            // it puts the plugin into a situation where the file system listing can go out of sync with the actual
            // file system after a plugin-initiated change took place on the board. This means the plugin should
            // disconnect from the board and show an error message to the user.
            if (isInitialRefresh || disconnectOnCancel) {
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
                val volumeID = fields[3].toInt()
                val hash = fields[4]
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
                    currentFolder.add(FileNode(fullName, names.last(), len, volumeID, hash))
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
            reporter.fraction(null)

            val sure = MessageDialogBuilder.yesNo(title, message).ask(project)
            if (sure) fileSystemNodes else emptyList()
        }

        if (confirmedFileSystemNodes.isEmpty()) return

        val pathsToDelete = confirmedFileSystemNodes
            .map { it.fullName }
            .toSet()

        transferService.recursivelySafeDeletePaths(pathsToDelete)
    }

    fun selectedFiles(): Collection<FileSystemNode> {
        return tree.selectionPaths?.mapNotNull { it.lastPathComponent.asSafely<FileSystemNode>() } ?: emptyList()
    }

    suspend fun disconnect(reporter: RawProgressReporter) {
        val settings = MpySettingsService.getInstance(project)

        reporter.text("Disconnecting from ${settings.state.portName}")
        reporter.fraction(null)
        comm.disconnect()
        deviceInformation = DeviceInformation()
    }

    @Throws(IOException::class)
    suspend fun upload(relativeName: @NonNls String, contentsToByteArray: ByteArray, progressCallback: (uploadedBytes: Int) -> Unit) {
        comm.upload(relativeName, contentsToByteArray, progressCallback)
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

    private fun setConnectionParams(connectionParameters: ConnectionParameters) = comm.setConnectionParams(connectionParameters)
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

class FileNode(fullName: String, name: String, val size: Int, val volumeID: Int, val hash: String) : FileSystemNode(fullName, name) {
    override fun getAllowsChildren(): Boolean = false
    override fun isLeaf(): Boolean = true
}

class DirNode(fullName: String, name: String, val isVolume: Boolean = false) : FileSystemNode(fullName, name) {
    override fun getAllowsChildren(): Boolean = true
}