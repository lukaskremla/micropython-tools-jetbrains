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
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.PathUtilRt
import com.intellij.util.PathUtilRt.Platform
import com.intellij.util.asSafely
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.communication.State
import dev.micropythontools.editor.MPY_TOOLS_EDITABLE_FILE_SIGNATURE
import dev.micropythontools.editor.MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY
import dev.micropythontools.editor.ORIGINAL_CONTENT_KEY
import dev.micropythontools.editor.REMOTE_PATH_KEY
import dev.micropythontools.ui.DirNode
import dev.micropythontools.ui.FileNode
import dev.micropythontools.ui.FileSystemNode
import dev.micropythontools.ui.VolumeRootNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

internal class MpyRefreshAction : MpyReplAction(
    "Refresh",
    MpyActionOptions(
        visibleWhen = VisibleWhen.PLUGIN_ENABLED,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = false,
        requiresRefreshAfter = false, // This option isn't used, refresh is instead called explicitly, (allows cancellation)
        cancelledMessage = "Refresh operation cancelled"
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

internal class MpyCreateFolderAction : MpyReplAction(
    "New Folder",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        cancelledMessage = "New folder creation cancelled"
    )
) {
    init {
        this.templatePresentation.icon =
            AllIcons.Actions.AddDirectory // Set in Kotlin code to prevent false plugin.xml errors
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter, dialogResult: Any?) {
        val newFolderPath = dialogResult as String

        reporter.text("Creating a new folder...")
        deviceService.safeCreateDirectories(
            setOf(newFolderPath)
        )
    }

    override fun dialogToShowFirst(e: AnActionEvent): DialogResult {
        val parent =
            deviceService.fileSystemWidget?.selectedFiles()?.firstOrNull().asSafely<DirNode>() ?: return DialogResult(
                false,
                null
            )

        val validator = object : InputValidator {
            override fun checkInput(inputString: String): Boolean {
                if (!PathUtilRt.isValidFileName(inputString, Platform.UNIX, true, Charsets.US_ASCII)) return false
                return parent.children().asSequence().none { it.asSafely<FileSystemNode>()?.name == inputString }
            }

            override fun canClose(inputString: String): Boolean = checkInput(inputString)
        }

        val newName = Messages.showInputDialog(
            project,
            "Name:", "Create New Folder", AllIcons.Actions.AddDirectory,
            "new_folder", validator
        )

        return DialogResult(
            !newName.isNullOrEmpty(),
            "${parent.fullName}/$newName"
        )
    }

    override fun customUpdate(e: AnActionEvent) {
        val selectedFiles = deviceService.fileSystemWidget?.selectedFiles()

        if (selectedFiles.isNullOrEmpty() || selectedFiles.size > 1 || selectedFiles.first() is FileNode) {
            e.presentation.isEnabled = false
        }
    }
}

internal class MpyDeleteAction : MpyReplAction(
    "Delete",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        cancelledMessage = "Deletion operation cancelled"
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
                is VolumeRootNode -> {
                    volumeCount++
                }

                is DirNode -> {
                    folderCount++
                }

                else -> {
                    fileCount++
                }
            }
        }

        return if (selectedFiles.all { it is VolumeRootNode }) {
            if (selectedFiles.size == 1) {
                if ((selectedFiles.first() as VolumeRootNode).isFileSystemRoot) {
                    AppropriateText(
                        reporterText = "Deleting device contents...",
                        dialogTitle = "Delete Device Contents",
                        dialogMessage = "Are you sure you want to permanently delete the device contents?"
                    )
                } else {
                    AppropriateText(
                        reporterText = "Deleting volume contents...",
                        dialogTitle = "Delete Volume Contents",
                        dialogMessage = "Are you sure you want to permanently delete the volume contents?"
                    )
                }
            } else {
                AppropriateText(
                    reporterText = "Deleting volume contents...",
                    dialogTitle = "Delete Volume Contents",
                    dialogMessage = "Are you sure you want to permanently delete the contents of these volumes?"
                )
            }
        } else if (fileCount == 0 && !selectedFiles.any { it.isRoot }) {
            if (folderCount == 1) {
                AppropriateText(
                    reporterText = "Deleting folder...",
                    dialogTitle = "Delete Folder",
                    dialogMessage = "Are you sure you want to permanently delete this folder and all its contents?"
                )
            } else {
                AppropriateText(
                    reporterText = "Deleting folders...",
                    dialogTitle = "Delete Folders",
                    dialogMessage = "Are you sure you want to permanently delete these folders and all their contents?"
                )
            }
        } else if (folderCount == 0) {
            if (fileCount == 1) {
                AppropriateText(
                    reporterText = "Deleting file...",
                    dialogTitle = "Delete File",
                    dialogMessage = "Are you sure you want to permanently delete this file?"
                )
            } else {
                AppropriateText(
                    reporterText = "Deleting files...",
                    dialogTitle = "Delete Files",
                    dialogMessage = "Are you sure you want to permanently delete these files?"
                )
            }
        } else {
            AppropriateText(
                reporterText = "Deleting items...",
                dialogTitle = "Delete Items",
                dialogMessage = "Are you sure you want to permanently delete these items?"
            )
        }
    }
}

internal class MpyDownloadAction : MpyAction(
    "Download",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "Download operation cancelled"
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
                e.presentation.text = "Download"
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
                e.presentation.text = "Download Device Contents"
            } else if (fileCount == 0 && !selectedFiles.any { it.isRoot }) {
                if (folderCount == 1) {
                    e.presentation.text = "Download Folder \"${selectedFiles.first().name}\""
                } else {
                    e.presentation.text = "Download Folders"
                }
            } else if (folderCount == 0) {
                if (fileCount == 1) {
                    e.presentation.text = "Download File \"${selectedFiles.first().name}\""
                } else {
                    e.presentation.text = "Download Files"
                }
            } else {
                e.presentation.text = "Download Items"
            }
        }
    }

    override fun performAction(e: AnActionEvent) {
        e.project?.service<MpyTransferService>()?.downloadDeviceFiles()
    }
}

internal class MpyOpenFileAction : MpyReplAction(
    "Open File",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "Open operation cancelled"
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

        reporter.text(if (selectedFiles.size == 1) "Opening file..." else "Opening files...")
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
            fileNodes.isNullOrEmpty() || fileNodes.count() == 1 -> e.presentation.text = "Open File"
            else -> e.presentation.text = "Open Files"
        }
    }
}