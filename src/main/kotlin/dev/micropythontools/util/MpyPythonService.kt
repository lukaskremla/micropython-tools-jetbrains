/*
 * Copyright 2000-2024 JetBrains s.r.o.
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
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
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
import java.io.File
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
/**
 * @author Lukas Kremla
 */
internal class MpyPythonService(private val project: Project) {
    private val settings = project.service<MpySettingsService>()

    companion object {
        private const val PLUGIN_ID = "micropython-tools-jetbrains"

        private const val LIBRARY_NAME = "MicroPythonTools"

        private val pluginDescriptor: IdeaPluginDescriptor
            get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
                ?: throw RuntimeException("The $PLUGIN_ID plugin cannot find itself")

        private val sandboxPath: String
            get() = "${pluginDescriptor.pluginPath}"

        private val scriptsPath: String
            get() = "$sandboxPath/scripts"

        private val microPythonScriptsPath: String
            get() = "$scriptsPath/MicroPythonMinified"

        val stubsPath: String
            get() = "$sandboxPath/stubs"
    }

    fun retrieveMpyScriptAsString(scriptFileName: String): String {
        val scriptPath = "$microPythonScriptsPath/$scriptFileName"

        val retrievedScript = File(scriptPath).readText(Charsets.UTF_8)

        var i = 0
        val lines = retrievedScript.lines().toMutableList()

        while (i < 15) {
            lines.removeAt(0)
            i++
        }

        return lines.joinToString("\n")
    }

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

    fun updateStubPackage(newStubPackage: String? = null) {
        removeAllMpyLibraries()

        if (!newStubPackage.isNullOrBlank()) addMpyLibrary(newStubPackage)
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
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(rootUrl)

                    newModel.addRoot(virtualFile!!, OrderRootType.CLASSES)

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
                    it.name in listOf(LIBRARY_NAME, "MicroPython Tools")
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
                        entry is LibraryOrderEntry && (entry.libraryName in listOf(
                            LIBRARY_NAME,
                            "MicroPython Tools"
                        ))
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

    fun checkStubPackageValidity(): ValidationResult {
        val activeStubsPackage = settings.state.activeStubsPackage

        var stubValidationText: String? = null

        if (settings.state.areStubsEnabled) {
            if (activeStubsPackage.isNullOrBlank()) {
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

    /*fun checkInterpreterValidity(): ValidationResult {
        val sdks = getAllValidSdks()

        return if (sdks.isEmpty()) {
            ValidationResult("MicroPython support requires a valid python 3.10+ interpreter")
        } else {
            ValidationResult.OK
        }
    }

    fun checkPackageValidity(): ValidationResult {
        val sdks = getAllValidSdks()

        if (sdks.isEmpty()) return ValidationResult.OK

        PyRequirementParser.fromText()

        PythonPackageSpecificationBase("mpflash", "", null, null)

        for (sdk in sdks) {
            val packageManager = PythonPackageManager.forSdk(project, sdk)

            packageManager.installPackage()

            for (installedPackage in packageManager.installedPackages) {
                installedPackage.
            }
        }


    }

    fun packages(sdk: Sdk) {
        val packageManager = PythonPackageManager.forSdk(project, sdk)
        packageManager.installedPackages
    }

    fun getAllValidSdks(): List<Sdk> {
        val validSdks = mutableListOf<Sdk>()

        for (module in ModuleManager.getInstance(project).modules) {
            val sdk = PythonSdkUtil.findPythonSdk(module) ?: continue
            val interpreter = sdk.homeDirectory ?: continue

            if (!interpreter.exists() || sdk.version.isOlderThan(LanguageLevel.PYTHON310)) {
                validSdks.add(sdk)
            }
        }

        return validSdks
    }

    fun checkValid(): ValidationResult {
        val settings = project.service<MpySettingsService>()
        val activeStubsPackage = settings.state.activeStubsPackage

        if (findValidPyhonSdk() == null) {
            return if (
                PluginManager.isPluginInstalled(PluginId.getId("com.intellij.modules.java")) ||
                PluginManager.isPluginInstalled(PluginId.getId("com.intellij.java"))
            ) {
                ValidationResult(
                    "MicroPython Tools plugin requires a valid Python 3.10+ SDK",
                    object : FacetConfigurationQuickFix("Configure") {
                        override fun run(place: JComponent?) {
                            ApplicationManager.getApplication().invokeLater {
                                ProjectSettingsService.getInstance(project).openModuleLibrarySettings(module)
                            }
                        }
                    }
                )
            } else {
                ValidationResult(
                    "MicroPython Tools plugin requires a valid Python 3.10+ SDK",
                    object : FacetConfigurationQuickFix("Configure") {
                        override fun run(place: JComponent?) {
                            ApplicationManager.getApplication().invokeLater {
                                ShowSettingsUtil.getInstance().showSettingsDialog(project, "ProjectStructure")
                            }
                        }
                    }
                )
            }
        }

        if (isPyserialInstalled() == false) {
            return ValidationResult(
                "Missing required Python packages",
                object : FacetConfigurationQuickFix("Install") {
                    override fun run(place: JComponent?) {
                        installRequiredPythonPackages()
                    }
                }
            )
        }

        if (activeStubsPackage != null && !getAvailableStubs().contains(activeStubsPackage)) {
            return ValidationResult(
                "Invalid stub package selected",
                object : FacetConfigurationQuickFix("Change Settings") {
                    override fun run(place: JComponent?) {
                        ApplicationManager.getApplication().invokeLater {
                            ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java)
                        }
                    }
                }
            )
        }

        return ValidationResult.OK
    }

    private val pythonSdkPath: String?
        get() = PythonSdkUtil.findPythonSdk(module)?.homePath

    fun listSerialPorts(project: Project): List<String> {
        if (findValidPyhonSdk() == null) {
            return emptyList()
        }

        val timeout = 1_000
        val command = mutableListOf(pythonSdkPath, "$pythonScriptsPath/scan_serial_ports.py")
        var result = listOf<String>()

        runWithModalProgressBlocking(project, "Listing serial ports...") {
            val process = CapturingProcessHandler(GeneralCommandLine(command))
            val output = process.runProcess(timeout)
            result = when {
                output.isCancelled -> emptyList()
                output.isTimeout -> emptyList()
                output.exitCode != 0 -> emptyList()
                else -> {
                    output.stdoutLines.flatMap { line ->
                        line.split("&").filter { it.isNotEmpty() }
                    }
                }
            }
        }
        return result
    }

    fun isPyserialInstalled(): Boolean? {
        val sdk = findValidPyhonSdk() ?: return false

        val packageManager = PythonPackageManager.getInstance(sdk)
        val packages = packageManager.packages ?: return null

        val requirements = PyRequirementParser.fromText("pyserial==3.5")

        val missingPackages = requirements.filter { it.match(packages) == null }.toList()

        return missingPackages.isEmpty()
    }

    fun installRequiredPythonPackages() {
        val sdk = PythonSdkUtil.findPythonSdk(module)

        val requirements = PyRequirementParser.fromText("pyserial==3.5")

        sdk?.let { PyPackageManagerUI(module.project, it, null).install(requirements, emptyList()) }
    }*/
}