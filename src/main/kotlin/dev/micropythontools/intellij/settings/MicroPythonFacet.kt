/*
 * Copyright 2000-2024 JetBrains s.r.o.
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
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.jetbrains.python.facet.FacetLibraryConfigurator
import com.jetbrains.python.facet.LibraryContributingFacet
import com.jetbrains.python.packaging.PyPackageManagerUI
import com.jetbrains.python.packaging.PyRequirementParser
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.statistics.version
import javax.swing.JComponent

/**
 * @author vlan
 */
class MicroPythonFacet(
    facetType: FacetType<out Facet<*>, *>, module: Module, name: String,
    configuration: MicroPythonFacetConfiguration, underlyingFacet: Facet<*>?
) : LibraryContributingFacet<MicroPythonFacetConfiguration>(facetType, module, name, configuration, underlyingFacet) {

    companion object {
        private const val PLUGIN_ID = "micropython-tools-jetbrains"

        val scriptsPath: String
            get() = "${pluginDescriptor.pluginPath}/scripts"

        val stubsPath: String
            get() = "${pluginDescriptor.pluginPath}/stubs"

        private val pluginDescriptor: IdeaPluginDescriptor
            get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: throw RuntimeException("The $PLUGIN_ID plugin cannot find itself")
    }

    override fun initFacet() {
        updateLibrary()
    }

    override fun updateLibrary() {
        val stubsPath = configuration.activeStubsPath

        if (stubsPath == "") {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            FacetLibraryConfigurator.attachPythonLibrary(module, null, "MicroPython Tools", listOf(stubsPath))
        }
    }

    override fun removeLibrary() {
        FacetLibraryConfigurator.detachPythonLibrary(module, "MicroPython Tools")
    }

    fun checkValid(): ValidationResult {
        val sdk = ProjectRootManager.getInstance(module.project).projectSdk

        if (sdk == null || sdk.version.isOlderThan(LanguageLevel.PYTHON310)) {
            return ValidationResult("MicroPython Tools support requires valid Python 3.10+ SDK")
        }

        val requirements = PyRequirementParser.fromText("pyserial==3.5")

        if (!isPyserialInstalled(module.project)) {
            return ValidationResult(
                "Packages required for MicroPython support not found: pyserial",
                object : FacetConfigurationQuickFix("Install Requirements") {
                    override fun run(place: JComponent?) {
                        PyPackageManagerUI(module.project, sdk, null).install(requirements, emptyList())
                    }
                })
        }

        return ValidationResult.OK
    }

    val pythonPath: String?
        get() = PythonSdkUtil.findPythonSdk(module)?.homePath

    fun listSerialPorts(project: Project): List<String> {
        val timeout = 1_000
        val command = mutableListOf(pythonPath, "$scriptsPath/scanSerialPorts.py")
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

    private fun isPyserialInstalled(project: Project): Boolean {
        var result = false
        // A very improvised way of checking if pyserial is installed
        // An alternative to the deprecated PyPackageManager
        ApplicationManager.getApplication().executeOnPooledThread {
            val command = mutableListOf(pythonPath, "$scriptsPath/checkPyserial.py")
            val process = CapturingProcessHandler(GeneralCommandLine(command))
            val output = process.runProcess(5000)
            result = output.stdout.trim() == "OK"
        }.get()
        println(result)
        return result
    }
}

val Module.microPythonFacet: MicroPythonFacet?
    get() = FacetManager.getInstance(this).getFacetByType(MicroPythonFacetType.ID)
