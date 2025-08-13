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
import com.intellij.ide.*
import com.intellij.ide.dnd.TransferableWrapper
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ActionPlaces.TOOLWINDOW_CONTENT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.asSafely
import com.intellij.util.containers.TreeTraversal
import com.intellij.util.ui.tree.TreeUtil
import dev.micropythontools.communication.*
import dev.micropythontools.core.MpyScripts
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.icons.MpyIcons
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

private enum class ClipOp { COPY, CUT }
private data class FsClipboard(val op: ClipOp, val paths: List<String>) : java.io.Serializable

private val FS_CLIP_FLAVOR = DataFlavor(FsClipboard::class.java, "MicroPython FS Clipboard")

internal class FileSystemWidget(private val project: Project) : JBPanel<FileSystemWidget>(BorderLayout()) {
    val tree: Tree = Tree(newTreeModel())

    private val settings = project.service<MpySettingsService>()
    private val deviceService = project.service<MpyDeviceService>()
    private val transferService = project.service<MpyTransferService>()

    init {
        updateEmptyText()

        tree.setCellRenderer(object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                value as FileSystemNode
                icon = when {
                    // Only non "/" root volumes have a separate icon, otherwise it's meant to get the DirNode icon
                    value is VolumeRootNode && !value.isFileSystemRoot -> MpyIcons.Volume
                    value is DirNode -> AllIcons.Nodes.Folder
                    else -> FileTypeRegistry.getInstance().getFileTypeByFileName(value.name).icon
                }
                append(value.name)
                if (value is FileNode) {
                    append("  ${value.size} bytes", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                } else if (value is VolumeRootNode) {
                    append(
                        "  ${formatVolumeSize(value.freeSize)} free of ${formatVolumeSize(value.totalSize)}",
                        SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
                    )
                }
            }
        })
        val actionManager = ActionManager.getInstance()
        EditSourceOnDoubleClickHandler.install(tree) {
            val action = actionManager.getAction("dev.micropythontools.fs.MpyOpenFileAction")
            actionManager.tryToExecute(action, null, tree, TOOLWINDOW_CONTENT, true)
        }
        val popupActions = actionManager.getAction("dev.micropythontools.fs.FSContextMenuGroup") as ActionGroup
        PopupHandler.installFollowingSelectionTreePopup(tree, popupActions, ActionPlaces.POPUP)
        TreeUtil.installActions(tree)

        val actions = actionManager.getAction("dev.micropythontools.fs.FSToolbarGroup") as ActionGroup
        val actionToolbar = actionManager.createActionToolbar(ActionPlaces.TOOLBAR, actions, true)
        actionToolbar.targetComponent = this

        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(actionToolbar.component, BorderLayout.NORTH)
        deviceService.stateListeners.add {
            when (it) {
                State.CONNECTED, State.TTY_DETACHED -> {}

                State.DISCONNECTING, State.DISCONNECTED, State.CONNECTING -> {
                    ApplicationManager.getApplication().invokeLater({
                        tree.model = null
                    }, ModalityState.any())
                }
            }
        }
        tree.model = null
        tree.isRootVisible = false
        tree.dragEnabled = true
        tree.dropMode = DropMode.ON

        tree.transferHandler = object : TransferHandler() {
            private val nodesFlavor =
                DataFlavor(Array<FileSystemNode>::class.java, "Array of MicroPython file system nodes")
            private val virtualFileFlavor =
                DataFlavor("application/x-java-file-list; class=java.util.List", "List of virtual files")
            private val flavors = arrayOf(nodesFlavor, virtualFileFlavor)

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

                    if (nodes.any { it is VolumeRootNode }) return false

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

                        val result = moveNodesToTarget(nodes, targetNode, false)
                        if (!result) return false
                    }

                    support.isDataFlavorSupported(virtualFileFlavor) -> {
                        val filesToUpload = try {
                            val transferData =
                                support.transferable.getTransferData(virtualFileFlavor) as TransferableWrapper

                            transferData.asFileList()
                                ?.mapNotNull { StandardFileSystems.local().findFileByPath(it.path) }
                                ?.toSet() ?: emptySet()
                        } catch (e: Exception) {
                            Notifications.Bus.notify(
                                Notification(
                                    MpyBundle.message("notification.group.name"),
                                    "Drag and drop file collection failed: $e",
                                    NotificationType.ERROR
                                ), project
                            )

                            return false
                        }

                        val transferService = project.service<MpyTransferService>()

                        val sure = if (!settings.state.showUploadPreviewDialog) {
                            MessageDialogBuilder.yesNo(
                                "Upload Dropped Item(s)",
                                "Are you sure you want to upload the dropped items?"
                            ).ask(project)
                        } else {
                            true
                        }

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
        // Register providers on the tree:
        DataManager.registerDataProvider(tree) { dataId ->
            when {
                PlatformDataKeys.COPY_PROVIDER.`is`(dataId) -> copyProvider
                PlatformDataKeys.CUT_PROVIDER.`is`(dataId) -> cutProvider
                PlatformDataKeys.PASTE_PROVIDER.`is`(dataId) -> pasteProvider
                else -> null
            }
        }
    }

    fun updateEmptyText() {
        tree.emptyText.clear()

        if (!settings.state.isPluginEnabled) {
            tree.emptyText.appendText("MicroPython support is disabled")
            tree.emptyText.appendLine("Change settings...", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java)
            }
        } else {
            tree.emptyText.appendText("No device connected")
            tree.emptyText.appendLine("Connect device", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                performReplAction(
                    project,
                    false,
                    "Connecting...",
                    false,
                    "Connection attempt cancelled",
                    { reporter ->
                        deviceService.doConnect(reporter, isConnectAction = true)
                    }
                )
            }

            tree.emptyText.appendLine("")
            tree.emptyText.appendLine("Find usage tips, report bugs or ask questions on our ")
            tree.emptyText.appendText("GitHub", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                BrowserUtil.browse("https://github.com/lukaskremla/micropython-tools-jetbrains")
            }
        }
    }

    fun selectedFiles(): Collection<FileSystemNode> {
        return tree.selectionPaths?.mapNotNull { it.lastPathComponent.asSafely<FileSystemNode>() } ?: emptyList()
    }

    fun allNodes(): Collection<FileSystemNode> {
        val allNodes = mutableListOf<FileSystemNode>()
        val root = tree.model.root as DirNode
        TreeUtil.treeNodeTraverser(root)
            .traverse(TreeTraversal.POST_ORDER_DFS)
            .mapNotNull {
                when (it) {
                    is InvisibleRootNode -> null
                    is DirNode -> it
                    is FileNode -> it
                    else -> null
                }
            }
            .toCollection(allNodes)

        return allNodes
    }

    private fun filterOutChildSelections(nodes: Array<FileSystemNode>): Array<FileSystemNode> {
        return nodes.filter { node ->
            nodes.none { potentialParent ->
                potentialParent != node && node.fullName.startsWith(potentialParent.fullName + "/")
            }
        }.toTypedArray()
    }

    private fun toClipboardContent(op: ClipOp, items: List<String>): Transferable {
        val human = buildString {
            append(if (op == ClipOp.CUT) "Cut " else "Copied ")
            append(items.size).append(if (items.size == 1) " item: " else " items: ")
            append(items.take(3).joinToString(", "))
            if (items.size > 3) append(" …")
        }
        return object : Transferable {
            override fun getTransferDataFlavors() =
                arrayOf(DataFlavor.stringFlavor, FS_CLIP_FLAVOR)

            override fun isDataFlavorSupported(flavor: DataFlavor) =
                flavor == DataFlavor.stringFlavor || flavor == FS_CLIP_FLAVOR

            override fun getTransferData(flavor: DataFlavor): Any =
                when (flavor) {
                    DataFlavor.stringFlavor -> human
                    FS_CLIP_FLAVOR -> FsClipboard(op, items)
                    else -> throw UnsupportedFlavorException(flavor)
                }
        }
    }

    // Resolve clipboard paths into current nodes (after a refresh, nodes are new instances)
    private fun resolveNodes(paths: List<String>): Array<FileSystemNode> {
        val byPath = allNodes().associateBy { it.fullName }
        return paths.mapNotNull { byPath[it] }.toTypedArray()
    }

    private fun singleTargetDirForSelection(): DirNode? {
        val sel = selectedFiles()
        if (sel.isEmpty()) return null
        val targets =
            sel.map { (it as? DirNode)?.fullName ?: (it.parent as FileSystemNode).fullName }.distinct()
        if (targets.size != 1) return null
        return allNodes().firstOrNull { it is DirNode && it.fullName == targets.single() } as? DirNode
    }

    private val copyProvider = object : CopyProvider {
        override fun isCopyEnabled(ctx: DataContext) =
            selectedFiles().isNotEmpty() && selectedFiles().none { it is VolumeRootNode }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isCopyVisible(ctx: DataContext) = true
        override fun performCopy(ctx: DataContext) {
            val items = filterOutChildSelections(selectedFiles().toTypedArray()).map { it.fullName }
            CopyPasteManager.getInstance().setContents(toClipboardContent(ClipOp.COPY, items))
        }
    }

    private val cutProvider = object : CutProvider {
        override fun isCutEnabled(ctx: DataContext) =
            selectedFiles().isNotEmpty() && selectedFiles().none { it is VolumeRootNode }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isCutVisible(ctx: DataContext) = true
        override fun performCut(ctx: DataContext) {
            val items = filterOutChildSelections(selectedFiles().toTypedArray()).map { it.fullName }
            CopyPasteManager.getInstance().setContents(toClipboardContent(ClipOp.CUT, items))
        }
    }

    private val pasteProvider = object : PasteProvider {
        override fun isPastePossible(context: DataContext) = true
        override fun isPasteEnabled(context: DataContext): Boolean {
            val clip = CopyPasteManager.getInstance().contents ?: return false
            if (!clip.isDataFlavorSupported(FS_CLIP_FLAVOR)) return false
            return singleTargetDirForSelection() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun performPaste(context: DataContext) {
            val copyPasteManager = CopyPasteManager.getInstance()
            val clip = copyPasteManager.contents?.getTransferData(FS_CLIP_FLAVOR) as? FsClipboard ?: return
            val target = singleTargetDirForSelection() ?: return

            // Resolve fresh nodes by path (selections can be stale after refresh)
            val nodes = resolveNodes(clip.paths)
            if (nodes.isEmpty()) return

            ApplicationManager.getApplication().invokeLater {
                kotlinx.coroutines.runBlocking {
                    val ok = moveNodesToTarget(
                        nodes, target,
                        shouldCopy = clip.op == ClipOp.COPY
                    )
                    if (ok && clip.op == ClipOp.CUT) {
                        // optional: clear clipboard to avoid “repeat move” confusion
                        CopyPasteManager.getInstance().setContents(StringSelection(""))
                    }
                }
            }
        }
    }

    private fun moveNodesToTarget(
        nodes: Array<FileSystemNode>,
        targetNode: DirNode,
        shouldCopy: Boolean
    ): Boolean {
        val confirmTitle = if (shouldCopy) "Copy Items" else "Move Items"
        val progressTitle = if (shouldCopy) "Copying items..." else "Moving items..."

        val result = performReplAction(
            project = project,
            connectionRequired = false,
            description = progressTitle,
            requiresRefreshAfter = true,
            cancelledMessage = "Move operation cancelled",
            { reporter ->
                var sure = false

                withContext(Dispatchers.EDT) {
                    sure = MessageDialogBuilder.yesNo(
                        confirmTitle,
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

                reporter.text(progressTitle)
                reporter.fraction(null)

                val isCrossVolumeTransfer = allNodes()
                    .filter { it is VolumeRootNode && !it.isFileSystemRoot }
                    .map { it.fullName }
                    .any { volumePath ->
                        targetNode.fullName.startsWith(volumePath) ||
                                nodes.any { node ->
                                    node.fullName.startsWith(volumePath)
                                }
                    }

                val commands = if (isCrossVolumeTransfer || shouldCopy) {
                    mutableListOf(
                        MpyScripts.retrieveMpyScriptAsString("move_file_base.py")
                    )
                } else {
                    mutableListOf("import os")
                }

                val currentPathToNewPath = mutableMapOf<String, String>()
                val pathsToRemove = mutableSetOf<String>()
                nodes.forEach { node ->
                    val newPath = "${targetNode.fullName}/${node.name}"
                    currentPathToNewPath[node.fullName] = newPath

                    if (foundConflicts && targetChildNames.contains(node.name)) pathsToRemove.add(
                        newPath
                    )

                    commands.add(
                        if (isCrossVolumeTransfer || shouldCopy) {
                            "___m(\"${node.fullName}\", \"$newPath\", ${if (shouldCopy) "True" else "False"})"
                        } else {
                            "os.rename(\"${node.fullName}\", \"$newPath\")"
                        }
                    )
                }

                if (isCrossVolumeTransfer) {
                    commands.add("del ___m")
                    commands.add("import gc")
                    commands.add("gc.collect()")
                }

                print(commands.joinToString("\n"))

                deviceService.recursivelySafeDeletePaths(pathsToRemove)

                deviceService.blindExecute(commands)
            }
        )
        return result != false
    }

    suspend fun refresh(reporter: RawProgressReporter) =
        doRefresh(reporter, hash = false, disconnectOnCancel = true, isInitialRefresh = false, useReporter = true)

    suspend fun quietRefresh(reporter: RawProgressReporter) =
        doRefresh(reporter, hash = false, disconnectOnCancel = false, isInitialRefresh = false, useReporter = false)

    suspend fun quietHashingRefresh(reporter: RawProgressReporter) =
        doRefresh(reporter, hash = true, disconnectOnCancel = false, isInitialRefresh = false, useReporter = false)

    suspend fun initialRefresh(reporter: RawProgressReporter) =
        doRefresh(reporter, hash = false, disconnectOnCancel = false, isInitialRefresh = true, useReporter = true)

    private fun newTreeModel() = DefaultTreeModel(InvisibleRootNode(), true)

    private fun formatVolumeSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000_000 -> "%.2f TB".format(bytes / 1_000_000_000_000.0)
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes bytes"
        }
    }

    private suspend fun doRefresh(
        reporter: RawProgressReporter,
        hash: Boolean,
        disconnectOnCancel: Boolean,
        isInitialRefresh: Boolean,
        useReporter: Boolean
    ): Int? {
        if (useReporter) {
            reporter.text("Updating file system view...")
            reporter.fraction(null)
        }

        deviceService.checkConnected()
        val newModel = newTreeModel()
        val executionResult: String

        val fileSystemScanScript = MpyScripts.retrieveMpyScriptAsString("scan_file_system.py")
            .replace(
                "___l=False",
                "___l=${if (settings.state.legacyVolumeSupportEnabled) "True" else "False"}"
            )
            .replace(
                "___h=False",
                "___h=${if (hash) "True" else "False"}"
            ) +
                // Print free memory (used by uploads)
                "\nprint(gc.mem_free())"

        try {
            // If not using the reporter, this refresh is a part of some complex operation that needs
            // information about the file system state, such as uploads
            // In that scenario it shouldn't return to the connected state to prevent emitting extra MicroPython banners
            executionResult = deviceService.blindExecute(fileSystemScanScript, !useReporter)
        } catch (e: CancellationException) {
            if (disconnectOnCancel) {
                deviceService.disconnect(reporter)
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
            } else if (useReporter) {
                throw IOException("Micropython filesystem scan cancelled, the board has been disconnected", e)
            } else {
                return null
            }
        } catch (e: Throwable) {
            deviceService.disconnect(reporter)
            throw IOException("Micropython filesystem scan failed, ${e.localizedMessage}", e)
        }

        val freeMemBytes = executionResult.lines().last().toInt()
        val dirList = executionResult.lines().subList(0, executionResult.lines().size - 1)

        val volumeRootNodes = mutableListOf<VolumeRootNode>()
        dirList.filter { it.isNotBlank() }.forEach { line ->
            line.split('&').let { fields ->
                if (fields.count() == 3) {
                    val fullName = fields[0]
                    val isFileSystemRoot = fullName == "/"
                    val name = if (isFileSystemRoot) fullName else fullName.removePrefix("/")
                    val freeSize = fields[1].toLong()
                    val totalSize = fields[2].toLong()

                    val volumeRootNode = VolumeRootNode(fullName, name, freeSize, totalSize, isFileSystemRoot)

                    volumeRootNodes.add(volumeRootNode)
                    (newModel.root as InvisibleRootNode).add(volumeRootNode)
                } else {
                    val fullName = fields[0]
                    val fileType = fields[1].toInt() // 0 - file, 1 - folder
                    val size = fields[2].toLong()
                    val crc32 = fields[3]

                    // Avoid creating directories for VolumeRoot paths
                    if (fileType == 1 && volumeRootNodes.any { it.fullName == fullName }) {
                        return@let
                    }

                    // All mounted volumes are discoverable with os.listdir("/"), however, unlike normal directories
                    // their names can contain multiple slashes, so "mt/logs/sd/" is a valid mount point name.
                    // There can never be a situation where the mount point path can conflict with a path structure
                    // that's already created/mounted. At that point the mount operation would fail.
                    // All paths can be considered unique thanks to that fact.
                    val parentVolumeRoot = volumeRootNodes
                        // Check longest volumes first to prevent matching similarly named, but shorter volumes
                        .sortedByDescending { it.fullName.length }
                        // Append leading slash, to prevent situations where a file with full path of:
                        // "/sdsm.py" from getting split like: "/sd/sm.py"
                        .find { fullName.startsWith(if (it.isFileSystemRoot) "/" else "${it.fullName}/") }
                        ?: throw IllegalStateException("Couldn't find parent root node")

                    // Collect names of all directories/files in the full path
                    val names = fullName
                        .removePrefix("${parentVolumeRoot.fullName}/")
                        .split('/')

                    // Extract only the directory structure
                    val folders = if (fileType == 0) names.dropLast(1) else names

                    var currentFolder: DirNode = parentVolumeRoot
                    folders.filter { it.isNotBlank() }.forEach { name ->
                        val child =
                            currentFolder.children().asSequence().find { (it as FileSystemNode).name == name }
                        when (child) {
                            is DirNode -> currentFolder = child
                            is FileNode -> Unit
                            null -> currentFolder = DirNode(fullName, name).also { currentFolder.add(it) }
                        }
                    }
                    if (fileType == 0) {
                        currentFolder.add(FileNode(fullName, names.last(), size, crc32))
                    }
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
            val currentRoot = tree.model?.root as? InvisibleRootNode

            val collapsedVolumeRootNodeNames = currentRoot
                ?.children()
                ?.asSequence()
                ?.filterIsInstance<VolumeRootNode>()
                ?.filter { !tree.isExpanded(TreeUtil.getPathFromRoot(it)) }
                ?.map { it.name }
                ?.toSet() ?: emptySet()


            val selectedPath = tree.selectionPath
            tree.model = newModel

            val newRoot = tree.model?.root as? InvisibleRootNode
            val newVolumeRootNodesToExpand = newRoot
                ?.children()
                ?.asSequence()
                ?.filterIsInstance<VolumeRootNode>()
                ?.filter { !collapsedVolumeRootNodeNames.contains(it.name) }
                ?: emptySequence()

            val newVolumeRootNodeTreePathsToExpand = newVolumeRootNodesToExpand.map {
                TreeUtil.getPathFromRoot(it)
            }

            TreeUtil.restoreExpandedPaths(tree, expandedPaths)
            TreeUtil.restoreExpandedPaths(tree, newVolumeRootNodeTreePathsToExpand.toMutableList())
            TreeUtil.selectPath(tree, selectedPath)
        }

        return if (useReporter) null else freeMemBytes
    }
}

internal sealed class FileSystemNode(val fullName: String, val name: String) :
    DefaultMutableTreeNode() {
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

internal class FileNode(
    fullName: String,
    name: String,
    val size: Long,
    val crc32: String
) : FileSystemNode(
    fullName,
    name
) {
    override fun getAllowsChildren(): Boolean = false
    override fun isLeaf(): Boolean = true
}

internal open class DirNode(
    fullName: String,
    name: String,
) : FileSystemNode(
    fullName,
    name
) {
    override fun getAllowsChildren(): Boolean = true
}

internal class VolumeRootNode(
    fullName: String,
    name: String,
    val freeSize: Long,
    val totalSize: Long,
    val isFileSystemRoot: Boolean = false
) : DirNode(
    fullName,
    name
)

internal class InvisibleRootNode : DirNode("", "")