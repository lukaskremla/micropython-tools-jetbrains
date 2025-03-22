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

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import dev.micropythontools.settings.MpySettingsService


class MpyAdditionalLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val settings = project.service<MpySettingsService>()
        val pythonService = project.service<MpyPythonService>()

        // This is temporarily not used
        return emptyList()

        if (!settings.state.areStubsEnabled || settings.state.activeStubsPackage.isNullOrBlank()) {
            println("return 1")
            return emptyList()
        }

        val availableStubs = pythonService.getAvailableStubs()
        val activeStubPackage = settings.state.activeStubsPackage

        if (!availableStubs.contains(activeStubPackage) || activeStubPackage == null) {
            println("return 2")
            return emptyList()
        }

        val rootPath = "${MpyPythonService.stubsPath}/$activeStubPackage"
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(rootPath)

        if (virtualFile == null) {
            println("return 3")
            return emptyList()
        }

        println("Attaching stub package: $virtualFile")

        return listOf(
            SyntheticLibrary.newImmutableLibrary(
                listOf(virtualFile),
                emptyList(),
                emptySet(),
                null
            )
        )
    }
}