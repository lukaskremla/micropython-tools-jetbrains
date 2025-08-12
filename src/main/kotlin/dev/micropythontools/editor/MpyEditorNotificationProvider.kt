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

package dev.micropythontools.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.util.ui.JBUI
import dev.micropythontools.communication.MpyDeviceService
import java.nio.charset.StandardCharsets
import java.util.function.Function
import javax.swing.*

internal class MpyEditorNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (!MpyEditableFileController.isEditableMpyToolsFile(file)) return null

        return Function { editor: FileEditor ->
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = JBUI.Borders.empty(5)
            }

            val doc = FileDocumentManager.getInstance().getDocument(file)

            // Add a listener to refresh the notification-added toolbar on each file change
            if (doc != null && file.getUserData(LISTENER_ADDED_KEY) != true) {
                file.putUserData(LISTENER_ADDED_KEY, true)
                doc.addDocumentListener(object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        EditorNotifications.getInstance(project).updateNotifications(file)
                    }
                }, editor)
            }

            val deviceService = project.service<MpyDeviceService>()

            // Ensure the editor is set to the appropriate view mode as well
            MpyEditableFileController.applyEditorViewMode(editor, file)

            val isModified = MpyEditableFileController.isModified(file, doc)

            if (!file.isWritable) {
                // READ-ONLY MODE
                panel.add(JLabel("Viewing file in read-only mode"))
                panel.add(Box.createHorizontalStrut(10))
                panel.add(Box.createHorizontalGlue())
                panel.add(JButton("Edit").apply {
                    addActionListener {
                        // Re-open the file as writable
                        MpyEditableFileController.reOpenFile(project, file, doc, makeWritable = true)
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
                        MpyEditableFileController.saveFromEditor(project, file, doc, deviceService)
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

                        val originalContent = file.getUserData(ORIGINAL_CONTENT_KEY)
                        if (originalContent != null && doc != null) {
                            ApplicationManager.getApplication().runWriteAction {
                                doc.setText(String(originalContent, StandardCharsets.UTF_8))
                            }
                        }
                        // Re-open as view-only
                        MpyEditableFileController.reOpenFile(project, file, doc, makeWritable = false)
                    }
                })
            }

            panel.add(JButton("Refresh").apply {
                addActionListener {
                    var canContinue = true
                    if (MpyEditableFileController.isModified(file, doc)) {
                        canContinue = MessageDialogBuilder.yesNo(
                            "Refresh open file",
                            "You have unsaved changes. Refreshing the file will discard them. Are you sure you want to continue?"
                        ).ask(project)
                    }
                    if (!canContinue) return@addActionListener

                    MpyEditableFileController.refreshFromDevice(project, file, doc, deviceService)
                }
            })

            panel
        }
    }
}