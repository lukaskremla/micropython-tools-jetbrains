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