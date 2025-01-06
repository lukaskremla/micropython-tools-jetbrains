/*
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2024-2025 Lukas Kremla
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

package dev.micropythontools.intellij.settings

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.facet.Facet
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.ui.FacetConfigurationQuickFix
import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.jetbrains.python.facet.LibraryContributingFacet
import com.jetbrains.python.packaging.PyPackageManagerUI
import com.jetbrains.python.packaging.PyRequirementParser
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.statistics.version
import java.io.File
import javax.swing.JComponent

/**
 * @author vlan, Lukas Kremla
 */
class MpyFacet(
    facetType: FacetType<out Facet<*>, *>, module: Module, name: String,
    configuration: MpyFacetConfiguration, underlyingFacet: Facet<*>?
) : LibraryContributingFacet<MpyFacetConfiguration>(facetType, module, name, configuration, underlyingFacet) {

    companion object {
        private const val PLUGIN_ID = "micropython-tools-jetbrains"

        val pythonScriptsPath: String
            get() = "${pluginDescriptor.pluginPath}/scripts/Python"

        val microPythonScriptsPath: String
            get() = "${pluginDescriptor.pluginPath}/scripts/MicroPythonOptimized"

        private val pluginDescriptor: IdeaPluginDescriptor
            get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
                ?: throw RuntimeException("The $PLUGIN_ID plugin cannot find itself")
    }

    override fun initFacet() {
        // To be re-implemented
    }

    override fun updateLibrary() {
        // To be re-implemented
    }

    override fun removeLibrary() {
        // To be re-implemented
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
                                ProjectSettingsService.getInstance(module.project).openModuleLibrarySettings(module)
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
                                ShowSettingsUtil.getInstance().showSettingsDialog(module.project, "ProjectStructure")
                            }
                        }
                    }
                )
            }
        }

        if (!isPyserialInstalled()) {
            return ValidationResult(
                "Missing required Python packages",
                object : FacetConfigurationQuickFix("Install") {
                    override fun run(place: JComponent?) {
                        installRequiredPythonPackages()
                    }
                }
            )
        }

        return ValidationResult.OK
    }

    val pythonSdkPath: String?
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

    fun isPyserialInstalled(): Boolean {
        if (findValidPyhonSdk() == null) {
            return false
        }

        var result = false
        // A very improvised way of checking if pyserial is installed
        // An alternative to the deprecated PyPackageManager
        ApplicationManager.getApplication().executeOnPooledThread {
            val command = mutableListOf(pythonSdkPath, "$pythonScriptsPath/check_pyserial.py")
            val process = CapturingProcessHandler(GeneralCommandLine(command))
            val output = process.runProcess(5000)
            result = output.stdout.trim() == "OK"
        }.get()
        return result
    }

    fun installRequiredPythonPackages() {
        val sdk = PythonSdkUtil.findPythonSdk(module)

        val requirements = PyRequirementParser.fromText("pyserial==3.5")

        sdk?.let { PyPackageManagerUI(module.project, it, null).install(requirements, emptyList()) }
    }
}

val Module.mpyFacet: MpyFacet?
    get() = FacetManager.getInstance(this).getFacetByType(ID)
