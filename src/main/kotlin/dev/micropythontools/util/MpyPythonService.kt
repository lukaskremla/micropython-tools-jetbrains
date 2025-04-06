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
import com.intellij.openapi.roots.ModifiableModelsProvider
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.python.library.PythonLibraryType
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import java.io.File
import java.io.FileNotFoundException
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
/**
 * @author Lukas Kremla
 */
class MpyPythonService(private val project: Project) {
    companion object {

        private const val PLUGIN_ID = "micropython-tools-jetbrains"

        private const val LIBRARY_NAME = "MicroPythonTools"

        /*val pythonScriptsPath: String
            get() = "${pluginDescriptor.pluginPath}/scripts/Python"*/

        val microPythonScriptsPath: String
            get() = "${pluginDescriptor.pluginPath}/scripts/MicroPythonMinified"

        val stubsPath: String
            get() = "${pluginDescriptor.pluginPath}/stubs"

        private val pluginDescriptor: IdeaPluginDescriptor
            get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
                ?: throw RuntimeException("The $PLUGIN_ID plugin cannot find itself")
    }

    fun retrieveMpyScriptAsString(scriptFileName: String): String {
        val scriptPath = "$microPythonScriptsPath/$scriptFileName"

        val retrievedScript = File(scriptPath).readText(Charsets.UTF_8)

        var i = 0
        if (!scriptFileName.contains("uftpd")) {
            val lines = retrievedScript.lines().toMutableList()

            while (i < 15) {
                lines.removeAt(0)
                i++
            }

            return lines.joinToString("\n")
        } else {
            return retrievedScript
        }
    }

    fun retrieveMpyScriptAsVirtualFile(scriptFileName: String): VirtualFile {
        val scriptPath = "$microPythonScriptsPath/$scriptFileName"
        return StandardFileSystems.local().findFileByPath(scriptPath) ?: throw FileNotFoundException()
    }

    fun getAvailableStubs(): List<String> {
        return File(stubsPath).listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it }
            ?.map { it.name }
            ?: emptyList()
    }

    fun updateLibrary() {
        val settings = project.service<MpySettingsService>()
        val activeStubPackage = settings.state.activeStubsPackage
        val availableStubs = getAvailableStubs()

        DumbService.getInstance(project).smartInvokeLater {
            ApplicationManager.getApplication().runWriteAction {
                val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                val projectLibraryModel = projectLibraryTable.modifiableModel

                for (library in projectLibraryModel.libraries) {
                    if (library.name == LIBRARY_NAME) {
                        projectLibraryTable.removeLibrary(library)
                        projectLibraryModel.removeLibrary(library)
                    }
                }

                if (settings.state.areStubsEnabled &&
                    !activeStubPackage.isNullOrBlank() &&
                    availableStubs.contains(activeStubPackage)
                ) {
                    val newLibrary = projectLibraryModel.createLibrary(LIBRARY_NAME, PythonLibraryType.getInstance().kind)
                    val newModel = newLibrary.modifiableModel

                    val rootUrl = "$stubsPath/$activeStubPackage"
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(rootUrl)

                    newModel.addRoot(virtualFile!!, OrderRootType.CLASSES)

                    val module = ModuleManager.getInstance(project).modules.first()
                    val moduleModel = ModifiableModelsProvider.getInstance().getModuleModifiableModel(module)
                    moduleModel.addLibraryEntry(newLibrary)

                    newModel.commit()
                    projectLibraryModel.commit()
                    ModifiableModelsProvider.getInstance().commitModuleModifiableModel(moduleModel)
                } else {
                    projectLibraryModel.commit()
                }
            }
        }
    }

    fun checkStubPackageValidity(): ValidationResult {
        val settings = project.service<MpySettingsService>()
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

    // These se are commented out for now, the code below will be salvaged an re-used for planned mpy-cross and
    // MicroPython firmware flashing pip script integrations

    /*
    fun findValidPyhonSdk(): Sdk? {
        val module = ModuleManager.getInstance(project).modules.first()
        val sdk = PythonSdkUtil.findPythonSdk(module)
        val interpreter = sdk?.homeDirectory

        return when {
            (sdk == null ||
                    interpreter == null ||
                    !interpreter.exists() ||
                    sdk.version.isOlderThan(LanguageLevel.PYTHON310)) -> null

            else -> sdk
        }
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

        val packageManager = PyPackageManager.getInstance(sdk)
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