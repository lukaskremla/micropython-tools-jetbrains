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

@file:Suppress("DEPRECATION", "REMOVAL")

package util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.facet.ui.FacetConfigurationQuickFix
import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.workspace.jps.entities.*
import com.intellij.workspaceModel.ide.legacyBridge.LegacyBridgeJpsEntitySourceFactory
import com.jetbrains.python.packaging.PyPackageManager
import com.jetbrains.python.packaging.PyPackageManagerUI
import com.jetbrains.python.packaging.PyRequirementParser
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.statistics.version
import dev.micropythontools.intellij.settings.MpyConfigurable
import dev.micropythontools.intellij.settings.MpySettingsService
import java.io.File
import javax.swing.JComponent

/**
 * @author Lukas Kremla
 */
@Service(Service.Level.PROJECT)
class MpyPythonService(private val project: Project) : DumbAware {
    companion object {

        private const val PLUGIN_ID = "micropython-tools-jetbrains"

        val pythonScriptsPath: String
            get() = "${pluginDescriptor.pluginPath}/scripts/Python"

        val microPythonScriptsPath: String
            get() = "${pluginDescriptor.pluginPath}/scripts/MicroPythonOptimized"

        val stubsPath: String
            get() = "${pluginDescriptor.pluginPath}/stubs"

        private val pluginDescriptor: IdeaPluginDescriptor
            get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
                ?: throw RuntimeException("The $PLUGIN_ID plugin cannot find itself")
    }

    private val module = ModuleManager.getInstance(project).modules.first()

    fun updateLibrary() {
        val settings = project.service<MpySettingsService>()
        val activeStubPackage = settings.state.activeStubsPackage
        val availableStubs = getAvailableStubs()

        DumbService.getInstance(project).smartInvokeLater {
            runWithModalProgressBlocking(project, "Updating libraries") {
                val workspaceModel = WorkspaceModel.getInstance(project)
                val currentSnapshot = workspaceModel.currentSnapshot
                val libraryTableId = LibraryTableId.ProjectLibraryTableId

                val libraryEntity = if (activeStubPackage != null && availableStubs.contains(activeStubPackage)) {
                    val libraryEntitySource =
                        LegacyBridgeJpsEntitySourceFactory.getInstance(project)
                            .createEntitySourceForProjectLibrary(null)

                    val libraryPathUrl = workspaceModel.getVirtualFileUrlManager().getOrCreateFromUrl("file://$stubsPath/$activeStubPackage")

                    LibraryEntity(
                        "MicroPython Tools",
                        libraryTableId,
                        listOf(
                            LibraryRoot(
                                url = libraryPathUrl,
                                type = LibraryRootTypeId("CLASSES"),
                                inclusionOptions = LibraryRoot.InclusionOptions.ROOT_ITSELF
                            )
                        ),
                        libraryEntitySource
                    )
                } else null

                workspaceModel.update("Adding new module dependency") { builder ->
                    val libraryId = LibraryId("MicroPython Tools", libraryTableId)

                    if (libraryId in currentSnapshot) {
                        val existingEntity = currentSnapshot.resolve(libraryId)

                        if (existingEntity != null) {
                            builder.removeEntity(existingEntity)
                        }
                    }

                    if (libraryEntity != null) {
                        builder.addEntity(libraryEntity)
                    }
                }

                PythonSdkUtil.findPythonSdk(module)?.let { sdk ->
                    PyPackageManager.getInstance(sdk).refreshAndGetPackages(true)
                }
            }
        }
    }

    fun getAvailableStubs(): List<String> {
        return File(stubsPath).listFiles()?.filter { it.isDirectory }
            ?.sortedBy { it }
            ?.map { it.name }
            ?: emptyList()
    }

    fun retrieveMpyScriptAsString(scriptFileName: String): String {
        val scriptPath = "$microPythonScriptsPath/$scriptFileName"
        return File(scriptPath).readText(Charsets.UTF_8)
    }

    fun findValidPyhonSdk(): Sdk? {
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
    }
}