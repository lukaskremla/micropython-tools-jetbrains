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

package dev.micropythontools.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.util.ui.JBUI
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.performReplAction
import dev.micropythontools.settings.*
import java.nio.charset.StandardCharsets
import java.util.function.Function
import javax.swing.*

class MpyEditorNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (file.getUserData(MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY) != MPY_TOOLS_EDITABLE_FILE_SIGNATURE) return null

        return Function { editor: FileEditor ->
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = JBUI.Borders.empty(5)
            }

            val doc = FileDocumentManager.getInstance().getDocument(file)

            // Add a listener to refresh the notification-added toolbar on each file change
            if (doc != null && file.getUserData(LISTENER_ADDED_KEY) != true) {
                file.putUserData(LISTENER_ADDED_KEY, true)
                doc.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
                    override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                        EditorNotifications.getInstance(project).updateNotifications(file)
                    }
                }, editor)
            }

            val deviceService = project.service<MpyDeviceService>()

            val currentContent = doc?.text?.toByteArray(StandardCharsets.UTF_8)
            val originalContent = file.getUserData(ORIGINAL_CONTENT_KEY)
            val isModified =
                currentContent != null && originalContent != null && !currentContent.contentEquals(originalContent)

            // Ensure the editor is set to the appropriate view mode as well
            updateEditorViewMode(editor, file)

            if (!file.isWritable) {
                // READ-ONLY MODE
                panel.add(JLabel("Viewing file in read-only mode"))
                panel.add(Box.createHorizontalStrut(10))
                panel.add(Box.createHorizontalGlue())
                panel.add(JButton("Edit").apply {
                    addActionListener {
                        // Re-open the file as writable
                        reOpenFile(project, file, doc, true)
                    }
                })
            } else {
                // EDIT MODE
                panel.add(JLabel("Editing file"))
                panel.add(Box.createHorizontalStrut(10))
                panel.add(Box.createHorizontalGlue())
                panel.add(JButton("Save").apply {
                    isEnabled = isModified
                    addActionListener {
                        val updatedContent = doc?.text?.toByteArray(StandardCharsets.UTF_8) ?: return@addActionListener
                        val remotePath = file.getUserData(REMOTE_PATH_KEY) ?: return@addActionListener

                        performReplAction(
                            project,
                            true,
                            "Saving edited file",
                            true,
                            action = { reporter ->
                                reporter.details(remotePath)

                                var uploadProgress = 0.0
                                var uploadedKB = 0.0

                                // Calculate the total binary size of the upload
                                val totalBytes = updatedContent.size.toDouble()

                                fun progressCallbackHandler(uploadedBytes: Double) {
                                    // Floating point arithmetic can be inaccurate,
                                    // ensures the uploaded size won't go over the actual file size
                                    uploadedKB += (uploadedBytes / 1000).coerceIn(
                                        (uploadedBytes / 1000),
                                        totalBytes / 1000
                                    )
                                    // Convert to double for maximal accuracy
                                    uploadProgress += (uploadedBytes / totalBytes)
                                    // Ensure that uploadProgress never goes over 1.0
                                    // as floating point arithmetic can have minor inaccuracies
                                    uploadProgress = uploadProgress.coerceIn(0.0, 1.0)

                                    reporter.text(
                                        "Saving edited file | ${
                                            "%.2f".format(
                                                uploadedKB
                                            )
                                        } KB of ${"%.2f".format(totalBytes / 1000)} KB"
                                    )
                                    reporter.fraction(uploadProgress)
                                }

                                deviceService.upload(
                                    remotePath,
                                    updatedContent,
                                    ::progressCallbackHandler,
                                    deviceService.deviceInformation.defaultFreeMem
                                        ?: throw RuntimeException("DefaultFreeMem is undefined")
                                )

                                // Reset edit state
                                file.putUserData(ORIGINAL_CONTENT_KEY, updatedContent)
                                // Re-open as view-only
                                reOpenFile(project, file, doc, false)
                            }
                        )
                    }
                })
                panel.add(JButton("Cancel").apply {
                    addActionListener {

                        var canContinue = true
                        if (isModified) {
                            canContinue = MessageDialogBuilder.yesNo(
                                "Discard changes",
                                "You have unsaved changes. Are you sure you want to discard them?"
                            ).ask(project)
                        }

                        if (!canContinue) return@addActionListener

                        if (originalContent != null && doc != null) {
                            ApplicationManager.getApplication().runWriteAction {
                                doc.setText(String(originalContent, StandardCharsets.UTF_8))
                            }
                        }
                        // Re-open as view-only
                        reOpenFile(project, file, doc, false)
                    }
                })
            }
            panel.add(JButton("Refresh").apply {
                addActionListener {
                    var canContinue = true
                    if (isModified) {
                        canContinue = MessageDialogBuilder.yesNo(
                            "Refresh open file",
                            "You have unsaved changes. Refreshing the file will discard them. Are you sure you want to continue?"
                        ).ask(project)
                    }

                    if (!canContinue) return@addActionListener

                    val remotePath = file.getUserData(REMOTE_PATH_KEY) ?: return@addActionListener

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

                            val newContent = text.toByteArray(StandardCharsets.UTF_8)

                            file.putUserData(ORIGINAL_CONTENT_KEY, newContent)

                            reOpenFile(project, file, doc, file.isWritable, text)
                        }
                    )
                }
            })

            panel
        }
    }

    private fun reOpenFile(
        project: Project,
        file: VirtualFile,
        doc: Document?,
        makeWritable: Boolean,
        newContent: String? = null
    ) {
        // Create new editable file
        val newFile = LightVirtualFile(file.name, file.fileType, newContent ?: doc?.text ?: "")
        newFile.isWritable = makeWritable

        // Copy the metadata
        newFile.putUserData(REMOTE_PATH_KEY, file.getUserData(REMOTE_PATH_KEY))
        newFile.putUserData(ORIGINAL_CONTENT_KEY, file.getUserData(ORIGINAL_CONTENT_KEY))
        newFile.putUserData(MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY, MPY_TOOLS_EDITABLE_FILE_SIGNATURE)

        val fileEditorManager = FileEditorManager.getInstance(project)
        ApplicationManager.getApplication().invokeLater {
            fileEditorManager.closeFile(file)
            fileEditorManager.openFile(newFile, true)
        }

        EditorNotifications.getInstance(project).updateNotifications(file)
    }

    private fun updateEditorViewMode(fileEditor: FileEditor, file: VirtualFile) {
        val textEditor = fileEditor as? TextEditor
        val editor = textEditor?.editor
        if (editor is EditorEx) {
            editor.isViewer = !file.isWritable
        }
    }
}