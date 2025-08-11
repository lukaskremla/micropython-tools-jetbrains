package dev.micropythontools.util

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
import dev.micropythontools.editor.MpyFileSaveHelper
import dev.micropythontools.settings.MPY_TOOLS_EDITABLE_FILE_SIGNATURE
import dev.micropythontools.settings.MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY

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

                MpyFileSaveHelper.saveFileFromEditor(project, file, doc, deviceService)
            }
        }
    }
}