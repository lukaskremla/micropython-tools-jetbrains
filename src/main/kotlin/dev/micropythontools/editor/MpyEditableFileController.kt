package dev.micropythontools.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotifications
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.performReplAction
import java.nio.charset.StandardCharsets

/**
 * Centralizes common actions for editable device-backed files:
 * - Toggle view/edit by reopening a LightVirtualFile copy
 * - Save current editor contents to device (with progress)
 * - Refresh contents from device (with discard confirmation)
 */
internal object MpyEditableFileController {
    fun isEditableMpyToolsFile(file: VirtualFile): Boolean =
        file.getUserData(MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY) == MPY_TOOLS_EDITABLE_FILE_SIGNATURE

    fun isModified(file: VirtualFile, doc: Document?): Boolean {
        val current = doc?.text?.toByteArray(StandardCharsets.UTF_8) ?: return false
        val original = file.getUserData(ORIGINAL_CONTENT_KEY) ?: return false
        return !current.contentEquals(original)
    }

    fun reOpenFile(
        project: Project,
        file: VirtualFile,
        doc: Document?,
        makeWritable: Boolean,
        newContent: String? = null
    ) {
        val newFile = LightVirtualFile(
            file.name,
            file.fileType,
            newContent ?: doc?.text ?: ""
        ).apply {
            isWritable = makeWritable
            // Preserve metadata
            putUserData(REMOTE_PATH_KEY, file.getUserData(REMOTE_PATH_KEY))
            putUserData(ORIGINAL_CONTENT_KEY, file.getUserData(ORIGINAL_CONTENT_KEY))
            putUserData(MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY, MPY_TOOLS_EDITABLE_FILE_SIGNATURE)
        }

        val fileEditorManager = FileEditorManager.getInstance(project)
        ApplicationManager.getApplication().invokeLater {
            fileEditorManager.closeFile(file)
            fileEditorManager.openFile(newFile, true)
            EditorNotifications.getInstance(project).updateNotifications(newFile)
        }
        // Update old reference so any listeners refresh state immediately
        EditorNotifications.getInstance(project).updateNotifications(file)
    }

    fun saveFromEditor(
        project: Project,
        file: VirtualFile,
        doc: Document?,
        deviceService: MpyDeviceService
    ) {
        val proceed = MessageDialogBuilder.yesNo(
            "Save changes",
            "This will replace the version of the file on the device. Are you sure?"
        ).ask(project)
        if (!proceed) return

        val updatedContent = doc?.text?.toByteArray(StandardCharsets.UTF_8) ?: return
        val remotePath = file.getUserData(REMOTE_PATH_KEY) ?: return

        performReplAction(
            project,
            true,
            "Saving the edited file",
            true,
            action = { reporter ->
                reporter.details(remotePath)

                val totalBytes = updatedContent.size.toDouble()
                var uploadedKB = 0.0
                var progress = 0.0

                fun progressCallback(uploadedBytes: Double) {
                    uploadedKB += (uploadedBytes / 1000).coerceIn(uploadedBytes / 1000, totalBytes / 1000)
                    progress = (progress + uploadedBytes / totalBytes).coerceIn(0.0, 1.0)
                    reporter.text(
                        "Saving the edited file | ${
                            "%.2f".format(uploadedKB)
                        } KB of ${"%.2f".format(totalBytes / 1000)} KB"
                    )
                    reporter.fraction(progress)
                }

                deviceService.upload(
                    remotePath,
                    updatedContent,
                    ::progressCallback,
                    deviceService.deviceInformation.defaultFreeMem
                        ?: throw RuntimeException("DefaultFreeMem is undefined")
                )

                // Mark saved state and switch to view mode
                file.putUserData(ORIGINAL_CONTENT_KEY, updatedContent)
                reOpenFile(project, file, doc, makeWritable = false)
            }
        )
    }

    fun refreshFromDevice(
        project: Project,
        file: VirtualFile,
        doc: Document?,
        deviceService: MpyDeviceService
    ) {
        val modified = isModified(file, doc)
        val proceed = if (modified) {
            MessageDialogBuilder.yesNo(
                "Refresh open file",
                "You have unsaved changes. Refreshing the file will discard them. Are you sure you want to continue?"
            ).ask(project)
        } else true

        if (!proceed) return
        val remotePath = file.getUserData(REMOTE_PATH_KEY) ?: return

        performReplAction(
            project,
            true,
            "Refresh edited file",
            false,
            action = { reporter ->
                reporter.text("Updating opened file...")

                var text = deviceService.download(remotePath).toString(StandardCharsets.UTF_8)
                if (file.fileType != PlainTextFileType.INSTANCE) {
                    text = StringUtilRt.convertLineSeparators(text)
                }
                val bytes = text.toByteArray(StandardCharsets.UTF_8)

                // Update original content value
                file.putUserData(ORIGINAL_CONTENT_KEY, bytes)

                // Reopen preserving current writability
                reOpenFile(project, file, doc, makeWritable = file.isWritable, newContent = text)
            }
        )
    }

    fun applyEditorViewMode(fileEditor: Any?, file: VirtualFile) {
        val editor = (fileEditor as? TextEditor)?.editor
        if (editor is EditorEx) {
            editor.isViewer = !file.isWritable
        }
    }
}