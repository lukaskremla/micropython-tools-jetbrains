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

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import dev.micropythontools.settings.MpySettingsService
import javax.swing.Icon

class MpySourceIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        val settings = project?.service<MpySettingsService>()

        if (project == null || settings == null || !settings.state.isPluginEnabled || !file.isDirectory) return null

        return if (settings.state.mpySourcePaths.contains(file.path)) {
            IconLoader.getIcon("icons/MpySource.svg", MpySourceIconProvider::class.java)
        } else null
    }
}