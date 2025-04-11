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
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.readText
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.PathUtilRt
import com.intellij.util.PathUtilRt.Platform
import com.intellij.util.asSafely
import com.jetbrains.python.PythonFileType
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.communication.State
import dev.micropythontools.communication.performReplAction
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

// ===== ENUMS AND BASE CLASSES =====

enum class VisibleWhen {
    ALWAYS, PLUGIN_ENABLED, CONNECTED, DISCONNECTED
}

enum class EnabledWhen {
    ALWAYS, PLUGIN_ENABLED, CONNECTED, DISCONNECTED
}

/**
 * Data class containing options of MpyActions. [visibleWhen] and [enabledWhen] only affect the default states.
 * They can be modified as needed by overriding the customUpdate() function
 */
data class MpyActionOptions(
    val visibleWhen: VisibleWhen,
    val enabledWhen: EnabledWhen,
    val requiresConnection: Boolean,
    val requiresRefreshAfter: Boolean,
    val cancelledMessage: String? = null
)

data class DialogResult(
    val shouldExecute: Boolean,
    val resultToPass: Any?
)

abstract class MpyActionBase(
    private val text: String,
    private val options: MpyActionOptions
) : DumbAwareAction(text) {

    protected lateinit var project: Project
    protected lateinit var settings: MpySettingsService
    protected lateinit var deviceService: MpyDeviceService
    protected lateinit var transferService: MpyTransferService

    protected fun initialize(event: AnActionEvent): Boolean {
        val project = event.project ?: return false
        this.project = project
        this.settings = project.service<MpySettingsService>()
        this.deviceService = project.service<MpyDeviceService>()
        this.transferService = project.service<MpyTransferService>()
        return true
    }

    final override fun update(e: AnActionEvent) {
        super.update(e)

        // Retrieve the services manually to properly handle null states
        val project = e.project
        val settings = project?.service<MpySettingsService>()
        val deviceService = project?.service<MpyDeviceService>()

        val isPluginEnabled = settings?.state?.isPluginEnabled == true
        val isConnected = deviceService != null && (deviceService.state == State.CONNECTING || deviceService.state == State.CONNECTED || deviceService.state == State.TTY_DETACHED)

        e.presentation.isVisible = when (options.visibleWhen) {
            VisibleWhen.ALWAYS -> true
            VisibleWhen.PLUGIN_ENABLED -> isPluginEnabled
            VisibleWhen.CONNECTED -> isConnected
            VisibleWhen.DISCONNECTED -> !isConnected
        }

        e.presentation.isEnabled = when (options.enabledWhen) {
            EnabledWhen.ALWAYS -> true
            EnabledWhen.PLUGIN_ENABLED -> isPluginEnabled
            EnabledWhen.CONNECTED -> isConnected && isPluginEnabled
            EnabledWhen.DISCONNECTED -> !isConnected && isPluginEnabled
        }

        // Attempt initialization before the customUpdate method, which relies on the properties being initialized
        val wasInitialized = initialize(e)
        if (!wasInitialized) return

        customUpdate(e)
    }

    open val actionDescription: @NlsContexts.DialogMessage String
        get() = text

    open fun customUpdate(e: AnActionEvent) = Unit
}

abstract class MpyAction(
    text: String,
    protected val options: MpyActionOptions
) : MpyActionBase(
    text,
    options
) {
    final override fun actionPerformed(e: AnActionEvent) {
        val wasInitialized = initialize(e)
        if (!wasInitialized) return

        performAction(e)
    }

    abstract fun performAction(e: AnActionEvent)
}

abstract class MpyReplAction(
    text: String,
    protected val options: MpyActionOptions
) : MpyActionBase(
    text,
    options
) {
    final override fun actionPerformed(e: AnActionEvent) {
        val wasInitialized = initialize(e)
        if (!wasInitialized) return

        val dialogResult = dialogToShowFirst(e)

        if (!dialogResult.shouldExecute) return

        performReplAction(
            project,
            options.requiresConnection,
            actionDescription,
            options.requiresRefreshAfter,
            options.cancelledMessage ?: "$actionDescription cancelled",
            { reporter -> performAction(e, reporter) }
        )
    }

    open suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) = Unit

    open suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter, dialogResult: Any?) =
        performAction(e, reporter)

    open fun dialogToShowFirst(e: AnActionEvent): DialogResult = DialogResult(true, null)
}

abstract class MpyUploadActionBase(
    text: String
) : MpyAction(
    text,
    MpyActionOptions(
        visibleWhen = VisibleWhen.PLUGIN_ENABLED,
        enabledWhen = EnabledWhen.PLUGIN_ENABLED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        cancelledMessage = "Upload operation cancelled"
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT
}

// ===== CONNECTION MANAGEMENT ACTIONS =====

class MpyConnectAction : MpyReplAction(
    "Connect",
    MpyActionOptions(
        visibleWhen = VisibleWhen.DISCONNECTED,
        enabledWhen = EnabledWhen.DISCONNECTED,
        requiresConnection = false,
        requiresRefreshAfter = false,
        cancelledMessage = "Connection attempt cancelled"
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        deviceService.doConnect(reporter)
    }
}

class MpyDisconnectAction : MpyReplAction(
    "Disconnect",
    MpyActionOptions(
        visibleWhen = VisibleWhen.CONNECTED,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = false,
        requiresRefreshAfter = false,
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        deviceService.disconnect(reporter)
    }

    override fun customUpdate(e: AnActionEvent) {
        if (deviceService.state != State.CONNECTED) {
            e.presentation.isEnabledAndVisible = false
        } else {
            Toggleable.setSelected(e.presentation, true)
        }
    }
}

// ===== FILE SYSTEM OPERATIONS | MIXED TOOLBAR ACTIONS =====

class MpyRefreshAction : MpyReplAction(
    "Refresh",
    MpyActionOptions(
        visibleWhen = VisibleWhen.PLUGIN_ENABLED,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = false,
        requiresRefreshAfter = false,
        cancelledMessage = "Refresh operation cancelled"
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        deviceService.fileSystemWidget?.refresh(reporter)
    }
}

class MpyCreateFolderAction : MpyReplAction(
    "New Folder",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        cancelledMessage = "New folder creation cancelled"
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter, dialogResult: Any?) {
        performReplAction(
            project,
            options.requiresConnection,
            actionDescription,
            options.requiresRefreshAfter,
            options.cancelledMessage ?: "$actionDescription cancelled",
            { reporter ->
                val newFolderPath = dialogResult as String

                reporter.text("Creating a new folder...")
                deviceService.safeCreateDirectories(
                    setOf(newFolderPath)
                )
            },
        )
    }

    override fun dialogToShowFirst(e: AnActionEvent): DialogResult {
        val parent = deviceService.fileSystemWidget?.selectedFiles()?.firstOrNull().asSafely<DirNode>() ?: return DialogResult(false, null)

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

class MpyDeleteAction : MpyReplAction(
    "Delete",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = true,
        cancelledMessage = "Deletion operation cancelled"
    )
) {
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

    override fun dialogToShowFirst(e: AnActionEvent): DialogResult {
        val appropriateText = getAppropriateText()

        return DialogResult(
            MessageDialogBuilder.yesNo(appropriateText.dialogTitle, appropriateText.dialogMessage).ask(project),
            null
        )
    }

    private fun getAppropriateText(): AppropriateText {
        val selectedFiles = deviceService.fileSystemWidget?.selectedFiles() ?: emptyList()

        var folderCount = 0
        var fileCount = 0

        for (file in selectedFiles) {
            if (file is DirNode) {
                folderCount++
            } else {
                fileCount++
            }
        }

        return if (selectedFiles.size == 1 && selectedFiles.first().isRoot) {
            AppropriateText(
                reporterText = "Deleting device contents...",
                dialogTitle = "Delete Device Contents",
                dialogMessage = "Are you sure you want to permanently delete the device contents?"
            )
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

class MpyDownloadAction : MpyAction(
    "Download",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "Download operation cancelled"
    )
) {
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

class MpyOpenFileAction : MpyReplAction(
    "Open File",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "Open operation cancelled"
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        val selectedFiles = withContext(Dispatchers.EDT) {
            deviceService.fileSystemWidget?.selectedFiles()?.mapNotNull { it as? FileNode } ?: emptyList()
        }

        reporter.text(if (selectedFiles.size == 1) "Opening file..." else "Opening files...")
        for (file in selectedFiles) {
            var text = deviceService.download(file.fullName).toString(StandardCharsets.UTF_8)
            withContext(Dispatchers.EDT) {
                var fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(file.name)
                if (fileType.isBinary) {
                    fileType = PlainTextFileType.INSTANCE
                } else {
                    //hack for LightVirtualFile and line endings
                    text = StringUtilRt.convertLineSeparators(text)
                }
                val selectedFile = LightVirtualFile("micropython: ${file.fullName}", fileType, text)
                selectedFile.isWritable = false
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

// ===== REPL TOOLBAR ACTIONS =====

class MpyInterruptAction : MpyReplAction(
    "Interrupt",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        reporter.text("Interrupting...")
        deviceService.interrupt()
    }
}

class MpySoftResetAction : MpyReplAction(
    "Reset",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "Reset cancelled"
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        reporter.text("Resetting...")
        deviceService.reset()
        deviceService.clearTerminalIfNeeded()
    }
}

// ===== EXECUTE ACTIONS =====

class MpyExecuteFileInReplAction : MpyReplAction(
    "Execute File in REPL",
    MpyActionOptions(
        visibleWhen = VisibleWhen.PLUGIN_ENABLED,
        enabledWhen = EnabledWhen.PLUGIN_ENABLED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "REPL execution cancelled"
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        ApplicationManager.getApplication().invokeLater {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        val code = e.getData(CommonDataKeys.VIRTUAL_FILE)?.readText() ?: return
        reporter.text("Executing file in REPL...")
        deviceService.instantRun(code, false)
    }

    override fun customUpdate(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        val file = files.firstOrNull() ?: return

        val excludedItems = transferService.collectExcluded()

        if (excludedItems.any { excludedItem ->
                files.any { candidate ->
                    VfsUtil.isAncestor(excludedItem, candidate, false)
                }
            }
        ) {
            e.presentation.isEnabled = false
            e.presentation.text = "Execute File in REPL"
            return
        }

        e.presentation.isEnabled = files.size == 1 && !file.isDirectory &&
                (file.fileType == PythonFileType.INSTANCE || file.extension == "mpy")
    }
}

class MpyExecuteFragmentInReplAction : MpyReplAction(
    "Execute Fragment in REPL",
    MpyActionOptions(
        visibleWhen = VisibleWhen.PLUGIN_ENABLED,
        enabledWhen = EnabledWhen.PLUGIN_ENABLED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "REPL execution cancelled"
    )
) {
    private fun editor(project: Project?): Editor? =
        project?.let { FileEditorManager.getInstance(it).selectedTextEditor }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        val code = withContext(Dispatchers.EDT) {
            val editor = editor(project) ?: return@withContext null
            val emptySelection = editor.selectionModel.getSelectedText(true).isNullOrBlank()
            reporter.text(if (emptySelection) "Executing Line in REPL..." else "Executing Selection in REPL...")

            var text = editor.selectionModel.getSelectedText(true)
            if (text.isNullOrBlank()) {
                try {
                    val range = EditorUtil.calcCaretLineTextRange(editor)
                    if (!range.isEmpty) {
                        text = editor.document.getText(range).trim()
                    }
                } catch (_: Throwable) {
                }
            }
            text
        }
        if (!code.isNullOrBlank()) {
            deviceService.instantRun(code, false)
        }
    }

    override fun customUpdate(e: AnActionEvent) {
        val editor = editor(e.project)
        if (editor == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val emptySelection = editor.selectionModel.getSelectedText(true).isNullOrBlank()
        e.presentation.text =
            if (emptySelection) "Execute Line in REPL" else "Execute Selection in REPL"
    }
}

// ===== UPLOAD ACTIONS =====

class MpyUploadActionGroup : ActionGroup("Upload Item(s) to MicroPython Device", true) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val transferService = project?.service<MpyTransferService>()

        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val excludedItems = transferService?.collectExcluded()

        if (project?.service<MpySettingsService>()?.state?.isPluginEnabled != true) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        if (files.isNullOrEmpty() || excludedItems == null || excludedItems.any { excludedItem ->
                files.any { candidate ->
                    VfsUtil.isAncestor(excludedItem, candidate, false)
                }
            }) {
            e.presentation.isEnabled = false
            e.presentation.text = "Upload Item(s) to MicroPython Device"
            return
        }

        var folderCount = 0
        var fileCount = 0

        for (file in files.iterator()) {
            if (file == null || !file.isInLocalFileSystem || ModuleUtil.findModuleForFile(file, project) == null) {
                return
            }

            if (file.isDirectory) {
                folderCount++
            } else {
                fileCount++
            }
        }

        if (fileCount == 0) {
            if (folderCount == 1) {
                e.presentation.text = "Upload Folder to MicroPython Device"
            } else {
                e.presentation.text = "Upload Folders to MicroPython Device"
            }
        } else if (folderCount == 0) {
            if (fileCount == 1) {
                e.presentation.text = "Upload File to MicroPython Device"
            } else {
                e.presentation.text = "Upload Files to MicroPython Device"
            }
        } else {
            e.presentation.text = "Upload Items to MicroPython Device"
        }
    }

    override fun getChildren(e: AnActionEvent?): Array<out AnAction?> {
        return arrayOf(MpyUploadRelativeToDeviceRootAction(), MpyUploadRelativeToParentAction())
    }
}

class MpyUploadRelativeToDeviceRootAction : MpyUploadActionBase("Upload to Device Root \"/\"") {
    init {
        templatePresentation.icon = AllIcons.Actions.Upload
    }

    override fun performAction(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (files.isNullOrEmpty()) return

        val sanitizedFiles = files.filter { candidate ->
            files.none { potentialParent ->
                VfsUtil.isAncestor(potentialParent, candidate, true)
            }
        }.toSet()

        val parentFolders = files
            .map { it.parent }
            .toSet()

        transferService.performUpload(
            initialFilesToUpload = sanitizedFiles,
            relativeToFolders = parentFolders,
            targetDestination = "/"
        )
    }
}

class MpyUploadRelativeToParentAction : MpyUploadActionBase("Upload Relative to") {
    init {
        templatePresentation.icon = AllIcons.Actions.Upload
    }

    override fun performAction(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (files.isNullOrEmpty()) return

        transferService.uploadItems(files.toSet())
    }

    override fun customUpdate(e: AnActionEvent) {
        val sourcesRoots = transferService.collectMpySourceRoots()
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (files.isNullOrEmpty()) {
            e.presentation.isEnabled = false
            return
        }

        if (files.all { sourcesRoots.contains(it) }) {
            e.presentation.isEnabledAndVisible = false
        }
        if (files.none { candidateFile ->
                sourcesRoots.any { sourceRoot ->
                    VfsUtil.isAncestor(sourceRoot, candidateFile, false)
                }
            }) {
            e.presentation.text = "Upload Relative to Project Root"
        } else if (files.all { candidateFile ->
                sourcesRoots.any { sourceRoot ->
                    VfsUtil.isAncestor(sourceRoot, candidateFile, false)
                }
            }) {
            e.presentation.text = "Upload Relative to MicroPython Sources Root(s)"
        } else {
            e.presentation.text = "Upload relative to... (mixed selection)"
        }
    }
}

// ===== SETTINGS ACTIONS =====

class MpyOpenSettingsAction : MpyAction(
    "Settings",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.ALWAYS,
        requiresConnection = false,
        requiresRefreshAfter = false
    )
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun performAction(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java)
    }
}