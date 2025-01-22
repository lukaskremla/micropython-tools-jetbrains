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

import com.intellij.openapi.components.service
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider
import dev.micropythontools.settings.MpySettingsService

class MpyRefactoringListenerProvider : RefactoringElementListenerProvider {
    override fun getListener(originalElement: PsiElement?): RefactoringElementListener? {
        if (originalElement !is PsiDirectory) return null

        val settings = originalElement.project.service<MpySettingsService>()

        // No need to listen if the plugin is disabled
        return if (settings.state.isPluginEnabled) MpyRefactoringListener(originalElement.virtualFile.path) else null
    }
}

class MpyRefactoringListener(private val oldPath: String) : RefactoringElementListener {
    override fun elementMoved(newElement: PsiElement) = handleReformatting(newElement)

    override fun elementRenamed(newElement: PsiElement) = handleReformatting(newElement)

    private fun handleReformatting(newElement: PsiElement) {
        if (newElement !is PsiDirectory) return

        val settings = newElement.project.service<MpySettingsService>()
        val newPath = newElement.virtualFile.path

        settings.state.mpySourcePaths.let { paths ->
            if (paths.contains(oldPath)) {
                paths.remove(oldPath)
                paths.add(newPath)
            }
        }
    }
}