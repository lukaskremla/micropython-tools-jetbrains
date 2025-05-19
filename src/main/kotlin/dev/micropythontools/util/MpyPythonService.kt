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

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.micropythontools.settings.microPythonScriptsPath
import java.io.File

@Service(Service.Level.PROJECT)
/**
 * @author Lukas Kremla
 */
internal class MpyPythonService(private val project: Project) {
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