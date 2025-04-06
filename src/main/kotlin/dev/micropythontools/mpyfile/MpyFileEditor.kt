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

package dev.micropythontools.mpyfile

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

class MpyFileEditor(private val file: VirtualFile) :
    UserDataHolderBase(), FileEditor {

    private val component = JPanel(BorderLayout())

    init {
        val textArea = JTextArea()
        textArea.isEditable = false

        try {
            val bytes = file.contentsToByteArray()
            textArea.text = bytes.joinToString("") {
                String.format("%02X ", it)
            }
        } catch (e: Exception) {
            textArea.text = "Unable to read .mpy file: ${e.message}"
        }

        component.add(textArea, BorderLayout.CENTER)
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent? = component

    override fun getName(): String = "MicroPython Bytecode File Editor"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {}

    override fun getFile(): VirtualFile {
        return file
    }
}