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

package dev.micropythontools.listeners

import com.intellij.ide.actions.SaveAllAction
import com.intellij.ide.actions.SaveDocumentAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.editor.MPY_TOOLS_EDITABLE_FILE_SIGNATURE
import dev.micropythontools.editor.MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY
import dev.micropythontools.editor.MpyEditableFileController

class MpySaveKeyListener : AnActionListener {
    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (action is SaveDocumentAction || action is SaveAllAction) {
            ApplicationManager.getApplication().invokeLater {
                val project = event.project ?: return@invokeLater
                val activeEditor = FileEditorManager.getInstance(project).selectedEditor ?: return@invokeLater
                val file = activeEditor.file ?: return@invokeLater

                if (file.getUserData(MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY) != MPY_TOOLS_EDITABLE_FILE_SIGNATURE) return@invokeLater
                if (!file.isWritable) return@invokeLater

                val doc = FileDocumentManager.getInstance().getDocument(file)
                val deviceService = project.service<MpyDeviceService>()

                MpyEditableFileController.saveFromEditor(project, file, doc, deviceService)
            }
        }
    }
}