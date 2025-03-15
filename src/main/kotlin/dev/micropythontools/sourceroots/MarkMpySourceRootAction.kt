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

package dev.micropythontools.sourceroots

import com.intellij.ide.projectView.actions.MarkRootActionBase
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ui.configuration.ModuleSourceRootEditHandler
import com.intellij.openapi.vfs.VirtualFile
import dev.micropythontools.settings.MpySettingsService
import java.util.*

class MarkMpySourceRootAction : MarkRootActionBase() {
    private val rootType = MpySourceRootType.SOURCE

    init {
        val presentation = getTemplatePresentation()
        val editHandler: ModuleSourceRootEditHandler<*>? = ModuleSourceRootEditHandler.getEditHandler(rootType)
        Logger.getInstance(MarkMpySourceRootAction::class.java).assertTrue(editHandler != null)
        presentation.setIcon(editHandler!!.rootIcon)
        presentation.setText(editHandler.fullRootTypeName)
        presentation.setDescription(
            ProjectBundle.messagePointer(
                "module.toggle.sources.action.description",
                editHandler.fullRootTypeName.lowercase(Locale.getDefault())
            )
        )
    }

    override fun modifyRoots(vFile: VirtualFile, entry: ContentEntry) {
        entry.addSourceFolder(vFile, rootType)
    }

    override fun isEnabled(selection: RootsSelection, module: Module): Boolean {
        val moduleType = ModuleType.get(module)

        val settings = module.project.service<MpySettingsService>()

        if (!settings.state.isPluginEnabled || ModuleSourceRootEditHandler.getEditHandler(rootType) == null || (selection.myHaveSelectedFilesUnderSourceRoots && !moduleType.isMarkInnerSupportedFor(rootType))) {
            return false
        }

        if (!selection.mySelectedDirectories.isEmpty()) {
            return true
        }

        for (root in selection.mySelectedRoots) {
            if (rootType != root.rootType) {
                return true
            }
        }
        return false
    }
}