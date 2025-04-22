/*
 * Copyright 2025 Lukas Kremla
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
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.OnePixelDivider
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.util.ui.tree.TreeUtil
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.MpyTransferService
import java.awt.Color
import java.awt.Dimension
import java.util.*
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel


class MpyUploadPreview(
    project: Project,
    private val allItemsToUpload: Set<VirtualFile>,
    private val fileToTargetPath: MutableMap<VirtualFile, String>,
    private val folderToTargetPath: MutableMap<VirtualFile, String>,
    private val targetPathsToRemove: Set<String>
) : DialogWrapper(true) {

    private val deviceService = project.service<MpyDeviceService>()
    private val transferService = project.service<MpyTransferService>()
    private val projectDir = project.guessProjectDir() ?: throw IllegalStateException("Can't guess project dir")
    private val mpySourceIcon
        get() = IconLoader.getIcon("/icons/MpySource.svg", this::class.java)

    private fun getFolderIcon(virtualFile: VirtualFile): Icon {
        val excludedRoots = transferService.collectExcluded()
        val sourceRoots = transferService.collectMpySourceRoots()
        val testRoots = transferService.collectTestRoots()

        return when {
            // Excluded roots icons apply to children in the project file tree as well
            excludedRoots.any { VfsUtil.isAncestor(it, virtualFile, false) } -> AllIcons.Modules.ExcludeRoot
            sourceRoots.any { it == virtualFile } -> mpySourceIcon
            testRoots.any { it == virtualFile } -> AllIcons.Modules.TestRoot
            else -> AllIcons.Nodes.Folder
        }
    }

    private fun createProjectTreeModel(): DefaultTreeModel {
        val previewNodes = mutableSetOf<PreviewNode>()
        VfsUtilCore.iterateChildrenRecursively(projectDir, null) { file ->
            val path = VfsUtil.getRelativePath(file, projectDir, '/') ?: file.name

            val fileStatus = if (allItemsToUpload.contains(file)) FileStatus.NOT_CHANGED else FileStatus.DELETED_FROM_FS

            val node = if (file.isDirectory) {
                val icon = getFolderIcon(file)
                PreviewDirNode(path, file.name, fileStatus, icon)
            } else {
                PreviewFileNode(path, file.name, fileStatus)
            }

            if (file != projectDir && file.name !in listOf(".DS_Store")
            ) {
                previewNodes.add(node)
            }
            true
        }

        val root = PreviewDirNode(projectDir.name, projectDir.name, FileStatus.NOT_CHANGED, AllIcons.Nodes.Folder)
        val pathToDir = mutableMapOf<String, PreviewDirNode>()
        pathToDir[""] = root

        val sortedNodes = previewNodes.sortedBy { it.fullName.count { char -> char == '/' } }

        for (node in sortedNodes) {
            val parentPath = node.fullName.substringBeforeLast('/', "")
            val parent = pathToDir[parentPath] ?: run {
                val parts = parentPath.split('/').filter { it.isNotEmpty() }
                var currentPath = ""
                var currentNode = root

                for (part in parts) {
                    currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                    val nextNode = pathToDir.getOrPut(currentPath) {
                        val newDir = PreviewDirNode(currentPath, part, node.fileStatus, (node as PreviewDirNode).icon)
                        currentNode.add(newDir)
                        newDir
                    }
                    currentNode = nextNode
                }
                currentNode
            }

            when (node) {
                is PreviewDirNode -> {
                    parent.add(node)
                    pathToDir[node.fullName] = node
                }

                is PreviewFileNode -> parent.add(node)
            }
        }

        val nodesToProcess = Stack<PreviewDirNode>()
        // Collect all leaf nodes
        pathToDir.values.filter { it.childCount > 0 }.forEach { nodesToProcess.push(it) }

        // Process nodes from bottom up
        while (nodesToProcess.isNotEmpty()) {
            val currentNode = nodesToProcess.pop()
            var hasChanges = false

            // Check if any child has changes
            for (i in 0 until currentNode.childCount) {
                val child = currentNode.getChildAt(i) as PreviewNode
                if (allItemsToUpload.any {
                        (VfsUtil.getRelativePath(it, projectDir, '/') ?: it.name) == child.fullName
                    } || child.fileStatus == FileStatus.NOT_CHANGED) {
                    hasChanges = true
                    break
                }
            }

            // Update directory status if it contains changes
            if (hasChanges && currentNode.fileStatus == FileStatus.DELETED_FROM_FS && currentNode.fullName != projectDir.name) {
                currentNode.fileStatus = FileStatus.NOT_CHANGED
            }

            // Find parent to process next
            val parentPath = currentNode.fullName.substringBeforeLast('/', "/")
            if (parentPath != currentNode.fullName) {
                pathToDir[parentPath]?.let { parent ->
                    if (!nodesToProcess.contains(parent)) {
                        nodesToProcess.push(parent)
                    }
                }
            }
        }

        val defaultTreeModel = DefaultTreeModel(root, true)

        TreeUtil.sort(defaultTreeModel, object : Comparator<PreviewNode> {
            override fun compare(node1: PreviewNode, node2: PreviewNode): Int {
                if ((node1 is PreviewDirNode) == (node2 is PreviewDirNode)) {
                    return node1.name.compareTo(node2.name)
                }
                return if (node1 is PreviewDirNode) -1 else 1
            }
        })

        return defaultTreeModel
    }

    private fun createDeviceTreeModel(): DefaultTreeModel {
        val existingNodes = deviceService.fileSystemWidget?.allNodes()?.toMutableSet() ?: mutableSetOf()
        val existingNodesRoot = deviceService.fileSystemWidget?.tree?.model?.root
        existingNodes.remove(existingNodesRoot)

        val uploadTargetPaths = (fileToTargetPath.values + folderToTargetPath.values).toMutableSet()

        val existingTargeUploadPaths = mutableSetOf<String>()
        val previewNodes = existingNodes.map { node ->
            val fileChangeType = when {
                uploadTargetPaths.contains(node.fullName) -> {
                    existingTargeUploadPaths.add(node.fullName)
                    FileStatus.MODIFIED
                }

                targetPathsToRemove.contains(node.fullName) -> FileStatus.MERGED_WITH_CONFLICTS
                else -> FileStatus.NOT_CHANGED
            }

            when (node) {
                is DirNode -> PreviewDirNode(node.fullName, node.name, fileChangeType, AllIcons.Nodes.Folder)
                is FileNode -> PreviewFileNode(node.fullName, node.name, fileChangeType)
            }
        }.toMutableSet()

        fileToTargetPath.values.removeAll(existingTargeUploadPaths)
        folderToTargetPath.values.removeAll(existingTargeUploadPaths)

        fileToTargetPath.forEach { file, targetPath ->
            previewNodes.add(PreviewFileNode(targetPath, file.name, FileStatus.ADDED))
        }

        folderToTargetPath.forEach { file, targetPath ->
            previewNodes.add(PreviewDirNode(targetPath, file.name, FileStatus.ADDED, AllIcons.Nodes.Folder))
        }

        val root = PreviewDirNode("/", "/", FileStatus.NOT_CHANGED, AllIcons.Nodes.Folder)
        val pathToDir = mutableMapOf<String, PreviewDirNode>()
        pathToDir["/"] = root

        // Sort so that parents come before children
        val sortedNodes = previewNodes.sortedBy { it.fullName.count { char -> char == '/' } }

        for (node in sortedNodes) {
            val parentPath = node.fullName.substringBeforeLast('/', "/")
            val parent = pathToDir[parentPath] ?: run {
                // Create intermediate directories if necessary
                val parts = parentPath.trim('/').split('/')
                var currentPath = ""
                var currentNode = root

                for (part in parts) {
                    currentPath += "/$part"
                    val nextNode = pathToDir.getOrPut(currentPath) {
                        val newDir = PreviewDirNode(currentPath, part, node.fileStatus, AllIcons.Nodes.Folder)
                        currentNode.add(newDir)
                        newDir
                    }
                    currentNode = nextNode
                }
                currentNode
            }

            when (node) {
                is PreviewDirNode -> {
                    parent.add(node)
                    pathToDir[node.fullName] = node
                }

                is PreviewFileNode -> parent.add(node)
            }
        }

        val nodesToProcess = Stack<PreviewDirNode>()
        // Collect all leaf nodes
        pathToDir.values.filter { it.childCount > 0 }.forEach { nodesToProcess.push(it) }

        // Process nodes from bottom up
        while (nodesToProcess.isNotEmpty()) {
            val currentNode = nodesToProcess.pop()
            var hasChanges = false

            // Check if any child has changes
            for (i in 0 until currentNode.childCount) {
                val child = currentNode.getChildAt(i) as PreviewNode
                if (child.fileStatus != FileStatus.NOT_CHANGED) {
                    hasChanges = true
                    break
                }
            }

            // Update directory status if it contains changes
            if (hasChanges && currentNode.fileStatus == FileStatus.NOT_CHANGED && currentNode.fullName != "/") {
                currentNode.fileStatus = FileStatus.MODIFIED
            }

            // Find parent to process next
            val parentPath = currentNode.fullName.substringBeforeLast('/', "/")
            if (parentPath != currentNode.fullName) {
                pathToDir[parentPath]?.let { parent ->
                    if (!nodesToProcess.contains(parent)) {
                        nodesToProcess.push(parent)
                    }
                }
            }
        }

        val defaultTreeModel = DefaultTreeModel(root, true)

        TreeUtil.sort(defaultTreeModel, object : Comparator<PreviewNode> {
            override fun compare(node1: PreviewNode, node2: PreviewNode): Int {
                if ((node1 is PreviewDirNode) == (node2 is PreviewDirNode)) {
                    return node1.name.compareTo(node2.name)
                }
                return if (node1 is PreviewDirNode) -1 else 1
            }
        })

        return defaultTreeModel
    }

    private val projectTree = com.intellij.ui.treeStructure.Tree(createProjectTreeModel())
    private val deviceTree = com.intellij.ui.treeStructure.Tree(createDeviceTreeModel())

    init {
        title = "Upload Preview"

        projectTree.setCellRenderer(object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                value as PreviewNode
                icon = if (value is PreviewFileNode) {
                    FileTypeRegistry.getInstance().getFileTypeByFileName(value.name).icon
                } else {
                    (value as PreviewDirNode).icon
                }

                append(value.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, value.fileStatus.color))
            }
        })

        deviceTree.setCellRenderer(object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                value as PreviewNode
                icon = when {
                    value is PreviewDirNode -> AllIcons.Nodes.Folder
                    else -> FileTypeRegistry.getInstance().getFileTypeByFileName(value.name).icon
                }

                append(value.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, value.fileStatus.color))
            }
        })

        TreeUtil.treeNodeTraverser(projectTree.model.root as PreviewDirNode)
            .traverse()
            .forEach { node ->
                if (node is PreviewDirNode &&
                    node.fileStatus == FileStatus.NOT_CHANGED &&
                    node.icon == mpySourceIcon &&
                    allItemsToUpload.any {
                        (VfsUtil.getRelativePath(it, projectDir, '/') ?: it.name) == node.fullName
                    }
                ) {
                    projectTree.expandPath(TreeUtil.getPathFromRoot(node))
                }
            }

        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                class InvisibleDividerSplitter : OnePixelSplitter(false, 0.5f) {
                    override fun createDivider() = object : OnePixelDivider(false, null) {
                        override fun setVisible(isVisible: Boolean) {
                            super.setVisible(false)
                        }

                        override fun getPreferredSize() = Dimension(0, 0)

                        override fun paint(g: java.awt.Graphics?) {
                            // do nothing
                        }
                    }
                }

                cell(InvisibleDividerSplitter().apply {
                    firstComponent = JBScrollPane(projectTree).apply {
                        border = BorderFactory.createTitledBorder("Project Structure")
                        minimumSize = Dimension(450, 400)
                    }
                    secondComponent = JBScrollPane(deviceTree).apply {
                        border = BorderFactory.createTitledBorder("Device Preview")
                        minimumSize = Dimension(450, 400)
                    }
                    setResizeEnabled(false)
                })
            }.bottomGap(BottomGap.NONE)

            panel {
                row {
                    comment("Upload preview dialog can be disabled in the settings")
                }

                row {
                    cell(createLegendItem("Added", FileStatus.ADDED.color)).gap(RightGap.SMALL)
                    cell(createLegendItem("Changed", FileStatus.MODIFIED.color)).gap(RightGap.SMALL)
                    cell(createLegendItem("Deleted", FileStatus.MERGED_WITH_CONFLICTS.color)).gap(RightGap.SMALL)
                    cell(createLegendItem("Unchanged", FileStatus.NOT_CHANGED.color)).gap(RightGap.SMALL)
                    cell(createLegendItem("Skipped/Ignored", FileStatus.DELETED_FROM_FS.color)).gap(RightGap.SMALL)
                }
            }.customize(UnscaledGaps(0, 4, 0, 0))
        }
    }

    private fun createLegendItem(text: String, color: Color?): JPanel {
        return panel {
            row {
                cell(JLabel("■").apply { foreground = color }).gap(RightGap.SMALL)
                cell(JLabel(text)).gap(RightGap.SMALL)
            }
        }
    }
}

private sealed class PreviewNode(
    val fullName: String,
    val name: String,
    var fileStatus: FileStatus
) : DefaultMutableTreeNode() {
    override fun toString(): String = name

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        return fullName == (other as PreviewNode).fullName
    }

    override fun hashCode(): Int {
        return fullName.hashCode()
    }
}

private class PreviewFileNode(
    fullName: String,
    name: String,
    fileStatus: FileStatus
) : PreviewNode(
    fullName,
    name,
    fileStatus
) {
    override fun getAllowsChildren(): Boolean = false
    override fun isLeaf(): Boolean = true
}

private class PreviewDirNode(
    fullName: String,
    name: String,
    fileStatus: FileStatus,
    val icon: Icon
) : PreviewNode(
    fullName,
    name,
    fileStatus
) {
    override fun getAllowsChildren(): Boolean = true
}