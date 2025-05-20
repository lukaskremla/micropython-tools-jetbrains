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

import com.intellij.facet.ui.FacetConfigurationQuickFix
import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModifiableModelsProvider
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.python.library.PythonLibraryType
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.settings.stubsPath
import java.io.File
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
/**
 * @author Lukas Kremla
 */
internal class MpyStubPackageService(private val project: Project) {
    companion object {
        private const val LIBRARY_NAME = "MicroPythonToolsStubs"
        private val LIBRARIES_TO_REMOVE = listOf(
            LIBRARY_NAME,
            // Legacy library names to clean up
            "MicroPython Tools",
            "MicroPythonTools"
        )
    }

    private val settings = project.service<MpySettingsService>()

    fun getAvailableStubs(): List<String> {
        return File(stubsPath).listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it }
            ?.map { it.name }
            ?: emptyList()
    }

    fun getExistingStubPackage(): String {
        val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        return projectLibraryTable.modifiableModel.libraries.find { it.name == LIBRARY_NAME }?.modifiableModel?.getFiles(
            OrderRootType.CLASSES
        )?.firstOrNull()?.name
            ?: ""
    }

    fun updateLibrary(newStubPackage: String? = null) {
        removeAllMpyLibraries()

        if (!newStubPackage.isNullOrBlank()) addMpyLibrary(newStubPackage)
    }

    fun checkStubPackageValidity(): ValidationResult {
        val activeStubsPackage = getExistingStubPackage()

        var stubValidationText: String? = null

        if (settings.state.areStubsEnabled) {
            if (activeStubsPackage.isBlank()) {
                stubValidationText = "No stub package selected"
            } else if (activeStubsPackage.isNotBlank() && !getAvailableStubs().contains(activeStubsPackage)) {
                stubValidationText = "Invalid stub package selected"
            }
        }

        return if (stubValidationText != null) {
            return ValidationResult(
                stubValidationText,
                object : FacetConfigurationQuickFix("Change Settings") {
                    override fun run(place: JComponent?) {
                        ApplicationManager.getApplication().invokeLater {
                            ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java)
                        }
                    }
                }
            )
        } else {
            ValidationResult.OK
        }
    }

    private fun addMpyLibrary(newStubPackage: String) {
        DumbService.getInstance(project).smartInvokeLater {
            ApplicationManager.getApplication().runWriteAction {
                val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                val projectLibraryModel = projectLibraryTable.modifiableModel

                if (settings.state.areStubsEnabled &&
                    getAvailableStubs().contains(newStubPackage)
                ) {
                    val newLibrary =
                        projectLibraryModel.createLibrary(LIBRARY_NAME, PythonLibraryType.getInstance().kind)
                    val newModel = newLibrary.modifiableModel

                    val rootUrl = "$stubsPath/$newStubPackage"
                    val stdlibUrl = "$rootUrl/stdlib"

                    val rootFile = LocalFileSystem.getInstance().findFileByPath(rootUrl)
                    val stdlibFile = LocalFileSystem.getInstance().findFileByPath(stdlibUrl)

                    newModel.addRoot(rootFile!!, OrderRootType.CLASSES)
                    newModel.addRoot(stdlibFile!!, OrderRootType.CLASSES)

                    for (module in ModuleManager.getInstance(project).modules) {
                        val moduleModel = ModifiableModelsProvider.getInstance().getModuleModifiableModel(module)
                        moduleModel.addLibraryEntry(newLibrary)

                        newModel.commit()
                        projectLibraryModel.commit()
                        ModifiableModelsProvider.getInstance().commitModuleModifiableModel(moduleModel)
                    }
                }
            }
        }
    }

    private fun removeAllMpyLibraries() {
        DumbService.getInstance(project).smartInvokeLater {
            ApplicationManager.getApplication().runWriteAction {
                // Clean up library table
                val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                val projectLibraryModel = projectLibraryTable.modifiableModel

                val librariesToRemove = projectLibraryModel.libraries.filter {
                    it.name in LIBRARIES_TO_REMOVE
                }

                librariesToRemove.forEach { library ->
                    projectLibraryModel.removeLibrary(library)
                }

                projectLibraryModel.commit()

                // Clean up order entries
                val moduleManager = ModuleManager.getInstance(project)
                val modules = moduleManager.modules

                modules.forEach { module ->
                    val moduleRootManager = ModuleRootManager.getInstance(module)
                    val moduleRootModel = moduleRootManager.modifiableModel

                    // Find all order entries related to MicroPython libraries
                    val entriesToRemove = moduleRootModel.orderEntries.filter { entry ->
                        entry is LibraryOrderEntry && (entry.libraryName in LIBRARIES_TO_REMOVE)
                    }

                    if (entriesToRemove.isNotEmpty()) {
                        entriesToRemove.forEach { entry ->
                            moduleRootModel.removeOrderEntry(entry)
                        }

                        moduleRootModel.commit()
                    }
                }
            }
        }
    }
}