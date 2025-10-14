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

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.PathUtilRt
import com.intellij.util.PathUtilRt.Platform
import com.jetbrains.python.PythonFileType
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.MpyFileTransferService
import dev.micropythontools.communication.State
import dev.micropythontools.editor.MPY_TOOLS_EDITABLE_FILE_SIGNATURE
import dev.micropythontools.editor.MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY
import dev.micropythontools.editor.ORIGINAL_CONTENT_KEY
import dev.micropythontools.editor.REMOTE_PATH_KEY
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.ui.DirNode
import dev.micropythontools.ui.FileNode
import dev.micropythontools.ui.FileSystemNode
import dev.micropythontools.ui.VolumeRootNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets

internal class MpyRefreshAction : MpyReplAction(
    MpyBundle.message("action.refresh.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.PLUGIN_ENABLED,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = false,
        requiresRefreshAfter = false, // This option isn't used, refresh is instead called explicitly, (allows cancellation)
        canRunInBackground = false,
        cancelledMessage = MpyBundle.message("action.refresh.cancelled"),
        timedOutMessage = MpyBundle.message("action.refresh.timeout")
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.StopRefresh
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        deviceService.fileSystemWidget?.refresh(reporter)
    }
}

internal class MpyDeleteAction : MpyReplAction(
    MpyBundle.message("action.delete.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        canRunInBackground = false,
        cancelledMessage = MpyBundle.message("action.delete.cancelled"),
        timedOutMessage = MpyBundle.message("action.delete.timeout")
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.GC
    }

    private data class AppropriateText(
        val reporterText: String,
        val dialogTitle: String,
        val dialogMessage: String
    )

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        val appropriateText = getAppropriateText()

        reporter.text(appropriateText.reporterText)

        val selectedFiles = deviceService.fileSystemWidget?.selectedFiles() ?: return

        val pathsToDelete = selectedFiles
            .map { it.fullName }
            .toSet()

        deviceService.recursivelySafeDeletePaths(pathsToDelete)
    }

    override fun customUpdate(e: AnActionEvent) {
        e.presentation.isEnabled = !deviceService.fileSystemWidget?.selectedFiles().isNullOrEmpty()
    }

    override fun dialogToShowFirst(e: AnActionEvent): DialogResult {
        val appropriateText = getAppropriateText()

        return DialogResult(
            MessageDialogBuilder.yesNo(appropriateText.dialogTitle, appropriateText.dialogMessage).ask(project),
            null
        )
    }

    private fun getAppropriateText(): AppropriateText {
        val selectedFiles = deviceService.fileSystemWidget?.selectedFiles() ?: emptyList()

        var volumeCount = 0
        var folderCount = 0
        var fileCount = 0

        for (file in selectedFiles) {
            when (file) {
                is VolumeRootNode -> volumeCount++
                is DirNode -> folderCount++
                else -> fileCount++
            }
        }

        return if (selectedFiles.all { it is VolumeRootNode }) {
            if (selectedFiles.size == 1) {
                if ((selectedFiles.first() as VolumeRootNode).isFileSystemRoot) {
                    AppropriateText(
                        reporterText = MpyBundle.message("action.delete.progress.device"),
                        dialogTitle = MpyBundle.message("action.delete.dialog.title.device"),
                        dialogMessage = MpyBundle.message("action.delete.dialog.message.device")
                    )
                } else {
                    AppropriateText(
                        reporterText = MpyBundle.message("action.delete.progress.volume"),
                        dialogTitle = MpyBundle.message("action.delete.dialog.title.volume"),
                        dialogMessage = MpyBundle.message("action.delete.dialog.message.volume.one")
                    )
                }
            } else {
                AppropriateText(
                    reporterText = MpyBundle.message("action.delete.progress.volume"),
                    dialogTitle = MpyBundle.message("action.delete.dialog.title.volume"),
                    dialogMessage = MpyBundle.message("action.delete.dialog.message.volume.multiple")
                )
            }
        } else if (fileCount == 0 && !selectedFiles.any { it.isRoot }) {
            if (folderCount == 1) {
                AppropriateText(
                    reporterText = MpyBundle.message("action.delete.progress.folder.one"),
                    dialogTitle = MpyBundle.message("action.delete.dialog.title.folder.one"),
                    dialogMessage = MpyBundle.message("action.delete.dialog.message.folder.one")
                )
            } else {
                AppropriateText(
                    reporterText = MpyBundle.message("action.delete.progress.folder.multiple"),
                    dialogTitle = MpyBundle.message("action.delete.dialog.title.folder.multiple"),
                    dialogMessage = MpyBundle.message("action.delete.dialog.message.folder.multiple")
                )
            }
        } else if (folderCount == 0) {
            if (fileCount == 1) {
                AppropriateText(
                    reporterText = MpyBundle.message("action.delete.progress.file.one"),
                    dialogTitle = MpyBundle.message("action.delete.dialog.title.file.one"),
                    dialogMessage = MpyBundle.message("action.delete.dialog.message.file.one")
                )
            } else {
                AppropriateText(
                    reporterText = MpyBundle.message("action.delete.progress.file.multiple"),
                    dialogTitle = MpyBundle.message("action.delete.dialog.title.file.multiple"),
                    dialogMessage = MpyBundle.message("action.delete.dialog.message.file.multiple")
                )
            }
        } else {
            AppropriateText(
                reporterText = MpyBundle.message("action.delete.progress.items"),
                dialogTitle = MpyBundle.message("action.delete.dialog.title.items"),
                dialogMessage = MpyBundle.message("action.delete.dialog.message.items")
            )
        }
    }
}

internal class MpyDownloadAction : MpyAction(
    MpyBundle.message("action.download.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        canRunInBackground = true,
        cancelledMessage = MpyBundle.message("action.download.cancelled"),
        timedOutMessage = MpyBundle.message("action.download.timeout")
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.Download
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun customUpdate(e: AnActionEvent) {
        if (deviceService.state != State.CONNECTED) {
            e.presentation.isEnabled = false
        } else {
            val selectedFiles = deviceService.fileSystemWidget?.selectedFiles()

            if (selectedFiles.isNullOrEmpty()) {
                e.presentation.isEnabled = false
                e.presentation.text = MpyBundle.message("action.download.text")
                return
            }

            var folderCount = 0
            var fileCount = 0

            for (file in selectedFiles) {
                if (file is DirNode) {
                    folderCount++
                } else {
                    fileCount++
                }
            }

            if (selectedFiles.size == 1 && selectedFiles.first().isRoot) {
                e.presentation.text = MpyBundle.message("action.download.text.device")
            } else if (fileCount == 0 && !selectedFiles.any { it.isRoot }) {
                e.presentation.text = if (folderCount == 1) {
                    MpyBundle.message("action.download.text.folder.one", selectedFiles.first().name)
                } else {
                    MpyBundle.message("action.download.text.folder.multiple")
                }
            } else if (folderCount == 0) {
                e.presentation.text = if (fileCount == 1) {
                    MpyBundle.message("action.download.text.file.one", selectedFiles.first().name)
                } else {
                    MpyBundle.message("action.download.text.file.multiple")
                }
            } else {
                e.presentation.text = MpyBundle.message("action.download.text.items")
            }
        }
    }

    override fun performAction(e: AnActionEvent) {
        e.project?.service<MpyFileTransferService>()?.downloadDeviceFiles()
    }
}

internal class MpyOpenFileAction : MpyReplAction(
    MpyBundle.message("action.open.file.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        canRunInBackground = false,
        cancelledMessage = MpyBundle.message("action.open.file.cancelled"),
        timedOutMessage = MpyBundle.message("action.open.file.timeout")
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.MenuOpen
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        val selectedFiles = withContext(Dispatchers.EDT) {
            deviceService.fileSystemWidget?.selectedFiles()?.mapNotNull { it as? FileNode } ?: emptyList()
        }

        reporter.text(
            if (selectedFiles.size == 1) {
                MpyBundle.message("action.open.file.progress.file.one")
            } else {
                MpyBundle.message("action.open.file.progress.file.multiple")
            }
        )
        for (file in selectedFiles) {
            // Avoid opening a new editor if one already exists for this remote path
            var foundExistingEditor = false
            for (openFile in FileEditorManager.getInstance(project).openFiles) {
                val remotePath = openFile.getUserData(REMOTE_PATH_KEY)
                if (remotePath == file.fullName) {
                    val editorManager = FileEditorManager.getInstance(project)

                    val providerId = FileEditorProviderManager.getInstance()
                        .getProviderList(project, openFile)
                        .firstOrNull()?.editorTypeId

                    if (providerId != null) {
                        editorManager.setSelectedEditor(openFile, providerId)

                        ApplicationManager.getApplication().invokeLater {
                            editorManager.openFile(openFile, true, true)
                        }
                    }

                    foundExistingEditor = true
                    break
                }
            }

            // Don't download the file if it is already open
            if (foundExistingEditor) continue

            var text = deviceService.download(file.fullName).toString(StandardCharsets.UTF_8)

            var fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(file.name)
            if (fileType.isBinary) {
                fileType = PlainTextFileType.INSTANCE
            } else {
                //hack for LightVirtualFile and line endings
                text = StringUtilRt.convertLineSeparators(text)
            }
            val selectedFile = LightVirtualFile("mpy-tools: ${file.fullName}", fileType, text)

            selectedFile.isWritable = false
            selectedFile.putUserData(ORIGINAL_CONTENT_KEY, text.toByteArray(StandardCharsets.UTF_8))
            selectedFile.putUserData(REMOTE_PATH_KEY, file.fullName)
            selectedFile.putUserData(MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY, MPY_TOOLS_EDITABLE_FILE_SIGNATURE)

            withContext(Dispatchers.EDT) {
                FileEditorManager.getInstance(project).openFile(selectedFile, true, true)
            }
        }
    }

    override fun customUpdate(e: AnActionEvent) {
        val selectedFiles = deviceService.fileSystemWidget?.selectedFiles()

        e.presentation.isEnabled = !selectedFiles.isNullOrEmpty() && selectedFiles.all { it is FileNode }

        val fileNodes = selectedFiles?.filterIsInstance<FileNode>()

        when {
            fileNodes.isNullOrEmpty() || fileNodes.count() == 1 -> e.presentation.text =
                MpyBundle.message("action.open.file.text")

            else -> e.presentation.text = MpyBundle.message("action.open.file.text.multiple")
        }
    }
}

internal class MpyCopyPathActionGroup : ActionGroup(MpyBundle.message("action.copy.path.group.text"), true) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun update(e: AnActionEvent) {
        val widget = e.project?.service<MpyDeviceService>()?.fileSystemWidget
        e.presentation.isEnabled = widget?.selectedFiles()?.isNotEmpty() == true
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        arrayOf(MpyCopyAbsolutePathAction(), MpyCopyFileNameAction())
}

/** Copies each selected item's device path (one per line), preserving selection order. */
internal abstract class MpyCopyPathAction(text: String, private val copyAbsolutePath: Boolean) : AnAction(text) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun update(e: AnActionEvent) {
        val widget = e.project?.service<MpyDeviceService>()?.fileSystemWidget
        e.presentation.isEnabled = widget?.selectedFiles()?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val widget = e.project?.service<MpyDeviceService>()?.fileSystemWidget ?: return

        // Preserve selection order as reported by the Tree (matches IDEA behavior).
        val nodes: List<FileSystemNode> =
            widget.tree.selectionPaths?.mapNotNull { it.lastPathComponent as? FileSystemNode }.orEmpty()

        if (nodes.isEmpty()) return

        val payload = nodes.joinToString(separator = "\n") { if (copyAbsolutePath) it.fullName else it.name }
        CopyPasteManager.getInstance().setContents(StringSelection(payload))
    }
}

internal class MpyCopyFileNameAction : MpyCopyPathAction(MpyBundle.message("action.copy.path.file.name.text"), false)

internal class MpyCopyAbsolutePathAction :
    MpyCopyPathAction(MpyBundle.message("action.copy.path.absolute.path.text"), true) {
    init {
        val base = ActionManager.getInstance().getAction("CopyAbsolutePath")
        shortcutSet = base.shortcutSet
    }
}

internal class MpyRenameAction : MpyReplAction(
    MpyBundle.message("action.rename.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        canRunInBackground = false,
        cancelledMessage = MpyBundle.message("action.rename.cancelled"),
        timedOutMessage = MpyBundle.message("action.rename.timeout")
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun customUpdate(e: AnActionEvent) {
        val selectedFiles = deviceService.fileSystemWidget?.selectedFiles() ?: return
        e.presentation.isEnabled = selectedFiles.size == 1 && selectedFiles.first() !is VolumeRootNode
    }

    override fun dialogToShowFirst(e: AnActionEvent): DialogResult {
        val selection = deviceService.fileSystemWidget?.selectedFiles()?.singleOrNull()
            ?: return DialogResult(false, null)
        if (selection is VolumeRootNode) return DialogResult(false, null)

        // Parent dir and current name
        val parentDir = selection.parent as? DirNode ?: return DialogResult(false, null)
        val currentName = selection.name

        // Validate like "new file/folder": no slashes, valid UNIX file name, and no sibling clash
        val validator = object : InputValidator {
            override fun checkInput(inputString: String): Boolean {
                if (inputString.isBlank()) return false
                if (inputString.contains('/') || inputString.contains('\\')) return false
                if (!PathUtilRt.isValidFileName(inputString, Platform.UNIX, true, Charsets.US_ASCII)) return false
                // name must be unique among siblings (ignore the currently selected node)
                val clash = parentDir.children().asSequence()
                    .mapNotNull { it as? FileSystemNode }
                    .any { it !== selection && it.name == inputString }
                return !clash
            }

            override fun canClose(inputString: String) = checkInput(inputString)
        }

        val newName = Messages.showInputDialog(
            project,
            MpyBundle.message("action.rename.dialog.message"),
            MpyBundle.message("action.rename.dialog.title"),
            AllIcons.Actions.Edit,
            currentName,
            validator
        ) ?: return DialogResult(false, null)

        return DialogResult(true, newName)
    }

    override suspend fun performAction(
        e: AnActionEvent,
        reporter: RawProgressReporter,
        dialogResult: Any?
    ) {
        val selection = deviceService.fileSystemWidget?.selectedFiles()?.singleOrNull() ?: return
        val parentDir = selection.parent as? DirNode ?: return
        val newName = dialogResult as? String ?: return
        if (newName == selection.name) return  // no-op

        reporter.text(MpyBundle.message("action.rename.progress"))

        val oldPath = selection.fullName
        val parentPath = parentDir.fullName
        val newPath = if (parentPath == "/") "/$newName" else "$parentPath/$newName"

        deviceService.blindExecute(
            listOf(
                "import os",
                "os.rename(${StringUtil.escapeStringCharacters(oldPath).let { "\"$it\"" }}, " +
                        "${StringUtil.escapeStringCharacters(newPath).let { "\"$it\"" }})"
            )
        )
    }
}

internal class MpyNewActionGroup : ActionGroup(MpyBundle.message("action.new.group.text"), true) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun update(e: AnActionEvent) {
        val deviceService = e.project?.service<MpyDeviceService>() ?: return
        val selectedFiles = deviceService.fileSystemWidget?.selectedFiles() ?: return

        val targetPaths = selectedFiles.map { (it as? DirNode)?.fullName ?: (it.parent as FileSystemNode).fullName }

        e.presentation.isEnabled = targetPaths.all { it == targetPaths.first() }
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        arrayOf(MpyCreatePythonFileAction(), MpyCreateFileAction(), MpyCreateFolderAction())
}

internal class MpyCreatePythonFileAction : MpyReplAction(
    MpyBundle.message("action.create.file.text.python"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        canRunInBackground = false,
        cancelledMessage = MpyBundle.message("action.create.file.cancelled"),
        timedOutMessage = MpyBundle.message("action.create.file.timeout")
    )
) {
    init {
        templatePresentation.icon = PythonFileType.INSTANCE.icon
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun dialogToShowFirst(e: AnActionEvent): DialogResult {
        val parent = deviceService.fileSystemWidget?.selectedFiles()?.firstOrNull()
        val parentPath = (parent as? DirNode)?.fullName ?: "/"
        val validator = object : InputValidator {
            override fun checkInput(inputString: String): Boolean {
                if (inputString.isBlank()) return false
                if (inputString.contains('/') || inputString.contains('\\')) return false
                if (!PathUtilRt.isValidFileName(inputString, Platform.UNIX, true, Charsets.US_ASCII)) return false
                val clash = (parent as? DirNode)?.children()
                    ?.asSequence()
                    ?.mapNotNull { it as? FileSystemNode }
                    ?.any { it.name == inputString || it.name == ensurePy(inputString) } ?: false
                return !clash
            }

            override fun canClose(inputString: String) = checkInput(inputString)
        }
        val raw = Messages.showInputDialog(
            project,
            MpyBundle.message("action.create.dialog.message"),
            MpyBundle.message("action.create.file.dialog.title.python"),
            PythonFileType.INSTANCE.icon,
            "",
            validator
        )
        val name = raw?.let(::ensurePy)
        return DialogResult(!name.isNullOrBlank(), "$parentPath/$name")
    }

    override suspend fun performAction(
        e: AnActionEvent,
        reporter: RawProgressReporter,
        dialogResult: Any?
    ) {
        val remotePath = dialogResult as? String ?: return
        reporter.text(MpyBundle.message("action.create.file.progress"))

        deviceService.upload(
            remotePath,
            ByteArray(0),
            progressCallback = {},
            freeMemBytes = deviceService.deviceInformation.defaultFreeMem
        )
    }

    private fun ensurePy(name: String) =
        if (name.endsWith(".py", ignoreCase = true)) name else "$name.py"
}

internal class MpyCreateFileAction : MpyReplAction(
    MpyBundle.message("action.create.file.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        canRunInBackground = false,
        cancelledMessage = MpyBundle.message("action.create.file.cancelled"),
        timedOutMessage = MpyBundle.message("action.create.file.timeout")
    )
) {
    init {
        templatePresentation.icon = AllIcons.FileTypes.Any_type
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun dialogToShowFirst(e: AnActionEvent): DialogResult {
        val parent = deviceService.fileSystemWidget?.selectedFiles()?.firstOrNull()
        val parentPath = (parent as? DirNode)?.fullName ?: "/"
        val validator = object : InputValidator {
            override fun checkInput(inputString: String): Boolean {
                if (inputString.isBlank()) return false
                if (inputString.contains('/') || inputString.contains('\\')) return false
                return PathUtilRt.isValidFileName(inputString, Platform.UNIX, true, Charsets.US_ASCII)
            }

            override fun canClose(inputString: String) = checkInput(inputString)
        }
        val name = Messages.showInputDialog(
            project,
            MpyBundle.message("action.create.dialog.message"),
            MpyBundle.message("action.create.file.dialog.title"),
            AllIcons.Actions.New,
            "",
            validator
        )
        return DialogResult(!name.isNullOrBlank(), "$parentPath/$name")
    }

    override suspend fun performAction(
        e: AnActionEvent,
        reporter: RawProgressReporter,
        dialogResult: Any?
    ) {
        val remotePath = dialogResult as? String ?: return
        reporter.text(MpyBundle.message("action.create.file.progress"))
        deviceService.upload(
            remotePath,
            ByteArray(0),
            progressCallback = {},
            freeMemBytes = deviceService.deviceInformation.defaultFreeMem
        )
    }
}

internal class MpyCreateFolderAction : MpyReplAction(
    MpyBundle.message("action.create.folder.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        canRunInBackground = false,
        cancelledMessage = MpyBundle.message("action.create.folder.cancelled"),
        timedOutMessage = MpyBundle.message("action.create.folder.timeout")
    )
) {
    init {
        this.templatePresentation.icon =
            AllIcons.Actions.AddDirectory
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter, dialogResult: Any?) {
        val newFolderPath = dialogResult as String

        reporter.text(MpyBundle.message("action.create.folder.progress"))
        deviceService.safeCreateDirectories(
            setOf(newFolderPath)
        )
    }

    override fun dialogToShowFirst(e: AnActionEvent): DialogResult {
        val selection =
            deviceService.fileSystemWidget?.selectedFiles()?.firstOrNull() ?: return DialogResult(false, null)

        // Resolve target directory from selection (folder OR file’s parent)
        val targetDir: DirNode = when (selection) {
            is DirNode -> selection
            is FileNode -> selection.parent as? DirNode
        } ?: return DialogResult(false, null)

        fun joinPath(parent: String, child: String): String =
            if (parent == "/") "/$child" else "$parent/$child"

        val validator = object : InputValidator {
            override fun checkInput(inputString: String): Boolean {
                if (!PathUtilRt.isValidFileName(
                        inputString,
                        Platform.UNIX,
                        true,
                        Charsets.US_ASCII
                    )
                ) return false
                return targetDir.children().asSequence().none { (it as? FileSystemNode)?.name == inputString }
            }

            override fun canClose(inputString: String) = checkInput(inputString)
        }

        val newName = Messages.showInputDialog(
            project,
            MpyBundle.message("action.create.dialog.message"),
            MpyBundle.message("action.create.folder.dialog.title"),
            AllIcons.Actions.AddDirectory,
            "",
            validator
        ) ?: return DialogResult(false, null)

        val newFolderPath = joinPath(targetDir.fullName, newName)
        return DialogResult(true, newFolderPath)
    }
}