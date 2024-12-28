/*
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2024 Lukas Kremla
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
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionPlaces.TOOLWINDOW_CONTENT
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
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
import kotlinx.coroutines.*
import org.jetbrains.annotations.NonNls
import java.awt.BorderLayout
import java.io.IOException
import java.util.*
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * @authors elmot, Lukas Kremla (synchronization and initialization script)
 */

private const val MPY_INITIALIZATION_SCRIP = """
    
import machine, os

datetime = %s

try:
    rtc = machine.RTC()
    rtc.datetime(datetime)
except Exception as e:
    pass

print(os.uname())

"""

private const val MPY_FS_SCAN = """

import os
class ___FSScan(object):

    def fld(self, name):
        for r in os.ilistdir(name):
            print(r[1],r[3] if len(r) > 3 else -1,name + r[0],sep='&')
            if r[1] & 0x4000:
                self.fld(name + r[0]+ "/")

___FSScan().fld("/")
del ___FSScan
try:
    import gc
    gc.collect()
except Exception:
        pass
  
"""

private const val MPY_SYNCHRONIZE_AND_CHECK_MATCHES = """
    
try:
    import os

    try:
        import binascii

        imported_successfully = True
    except ImportError:
        imported_successfully = False

    should_synchronize = %s

    files_to_upload = [
        %s
    ]

    paths_to_exclude = [
        %s
    ]

    local_files = set()
    local_directories = set()

    def save_all_items_on_path(dir_path) -> None:
        for entry in os.ilistdir(dir_path):
            name, kind = entry[0], entry[1]

            file_path = f"{dir_path}/{name}" if dir_path != "/" else f"/{name}"

            if any(file_path == excluded_path or file_path.startswith(excluded_path + '/') for excluded_path in paths_to_exclude):
                continue

            if kind == 0x8000:  # file
                local_files.add(file_path)
            elif kind == 0x4000:  # dir
                local_directories.add(file_path)
                save_all_items_on_path(file_path)

    if should_synchronize:
        save_all_items_on_path("/")

    try:
        chunk_size = 1024
        buffer = bytearray(chunk_size)
        already_uploaded_paths = []

        if not imported_successfully and not should_synchronize:
            raise Exception

        for remote_file_tuple in files_to_upload:
            path, remote_size, remote_hash = remote_file_tuple

            if not path.startswith("/"):
                path = "/" + path

            try:
                local_size = os.stat(path)[6]

                if should_synchronize:
                    local_files.remove(path)
            except OSError:
                continue

            if not imported_successfully or remote_size != local_size:
                continue

            crc = 0
            with open(path, 'rb') as f:
                while True:
                    n = f.readinto(buffer)
                    if n == 0:
                        break

                    if n < chunk_size:
                        crc = binascii.crc32(buffer[:n], crc)
                    else:
                        crc = binascii.crc32(buffer, crc)

                calculated_hash = "%%08x" %% (crc & 0xffffffff)

                if calculated_hash == remote_hash:
                    already_uploaded_paths.append(path)

        if should_synchronize:
            for file in local_files:
                os.remove(file)

        if should_synchronize:
            for directory in local_directories:
                try:
                    os.rmdir(directory)
                except OSError:
                    pass

        if not already_uploaded_paths:
            raise Exception

        output = "&".join(already_uploaded_paths)
        print(output)

    except Exception:
        print("NO MATCHES")

except Exception as e:
    print(f"ERROR: {e}")

"""

data class ConnectionParameters(
    var uart: Boolean = true,
    var url: String,
    var webReplPassword: String,
    var portName: String,
    var ssid: String,
    var wifiPassword: String
) {
    constructor(portName: String) : this(uart = true, url = DEFAULT_WEBREPL_URL, webReplPassword = "", portName = portName, ssid = "", wifiPassword = "")
    constructor(url: String, webReplPassword: String) : this(uart = false, url = url, webReplPassword = webReplPassword, portName = "", ssid = "", wifiPassword = "")
}

class FileSystemWidget(val project: Project, newDisposable: Disposable) :
    JBPanel<FileSystemWidget>(BorderLayout()) {
    var terminalContent: Content? = null
    val ttyConnector: TtyConnector
        get() = comm.ttyConnector
    private val tree: Tree = Tree(newTreeModel())

    private val comm: MpyComm = MpyComm().also { Disposer.register(newDisposable, it) }

    val state: State
        get() = comm.state

    private fun newTreeModel() = DefaultTreeModel(DirNode("/", "/"), true)

    init {
        tree.emptyText.appendText("No board is connected")
        tree.emptyText.appendLine("Connect...", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            performReplAction(project, false, "Connecting...") { doConnect(it) }
        }
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
            val action = actionManager.getAction("micropython.repl.OpenFile")
            actionManager.tryToExecute(action, null, tree, TOOLWINDOW_CONTENT, true)
        }
        val popupActions = actionManager.getAction("micropython.repl.FSContextMenu") as ActionGroup
        PopupHandler.installFollowingSelectionTreePopup(tree, popupActions, ActionPlaces.POPUP)
        TreeUtil.installActions(tree)

        val actions = actionManager.getAction("micropython.repl.FSToolbar") as ActionGroup
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
                    project.service<MpySupportService>().cs.launch(Dispatchers.EDT + ModalityState.any().asContextElement(), start = CoroutineStart.ATOMIC) {
                        tree.model = null
                    }
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

    suspend fun synchronizeAndGetAlreadyUploadedFiles(uploadFilesToTargetPath: MutableMap<VirtualFile, String>, excludedPaths: List<String>, shouldSynchronize: Boolean, shouldExcludePaths: Boolean): MutableList<VirtualFile> {
        val alreadyUploadedFiles = mutableListOf<VirtualFile>()
        val fileToUploadListing = mutableListOf<String>()
        val targetPathToFile = uploadFilesToTargetPath.entries.associate { (file, path) -> path to file }

        for (file in uploadFilesToTargetPath.keys) {
            val path = uploadFilesToTargetPath[file]
            val size = file.length
            val hash = calculateCRC32(file)

            fileToUploadListing.add("""("$path", $size, "$hash")""")
        }

        println(excludedPaths)
        println(shouldExcludePaths)

        val formattedScript = MPY_SYNCHRONIZE_AND_CHECK_MATCHES.format(
            if (shouldSynchronize) "True" else "False",
            fileToUploadListing.joinToString(",\n        "),
            if (excludedPaths.isNotEmpty() && shouldExcludePaths) excludedPaths.joinToString(",\n        ") { "\"$it\"" } else ""
        )

        println(formattedScript)

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

        val formattedScript = MPY_INITIALIZATION_SCRIP.format(
            "($year, $month, $day, $hour, $minute, $second, 0, 0)"
        )

        //println(formattedScript)

        val scriptResponse = blindExecute(TIMEOUT, formattedScript).extractSingleResponse().trim()

        //println(scriptResponse)
    }

    suspend fun refresh() {
        comm.checkConnected()
        val newModel = newTreeModel()
        val dirList: String
        try {
            dirList = blindExecute(LONG_TIMEOUT, MPY_FS_SCAN).extractSingleResponse()
        } catch (e: CancellationException) {
            disconnect()
            throw IOException("Micropython filesystem scan cancelled, the board has been disconnected", e)
        } catch (e: Throwable) {
            disconnect()
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

    suspend fun deleteCurrent() {

        comm.checkConnected()
        val confirmedFileSystemNodes = withContext(Dispatchers.EDT) {
            val fileSystemNodes = tree.selectionPaths?.mapNotNull { it.lastPathComponent.asSafely<FileSystemNode>() }
                ?.filter { it.fullName != "" && it.fullName != "/" } ?: emptyList()
            val title: String
            val message: String
            if (fileSystemNodes.isEmpty()) {
                return@withContext emptyList()
            } else if (fileSystemNodes.size == 1) {
                val fileName = fileSystemNodes[0].fullName
                if (fileSystemNodes[0] is DirNode) {
                    title = "Delete folder $fileName"
                    message = "Are you sure you want to delete the folder and its subtree?\n\rThis operation cannot be undone!"
                } else {
                    title = "Delete file $fileName"
                    message = "Are you sure you want to delete this file?\n\rThis operation cannot be undone!"
                }
            } else {
                title = "Delete multiple objects"
                message =
                    "Are you sure you want to delete ${fileSystemNodes.size} items?\n\rThis operation cannot be undone!"
            }

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
            try {
                blindExecute(LONG_TIMEOUT, *commands.toTypedArray())
                    .extractResponse()
            } catch (_: CancellationException) {
                Notifications.Bus.notify(
                    Notification(
                        NOTIFICATION_GROUP,
                        "Deletion cancelled",
                        NotificationType.INFORMATION
                    ), project
                )
            }
        }
    }

    fun selectedFiles(): Collection<FileSystemNode> {
        return tree.selectionPaths?.mapNotNull { it.lastPathComponent.asSafely<FileSystemNode>() } ?: emptyList()
    }

    suspend fun disconnect() {
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