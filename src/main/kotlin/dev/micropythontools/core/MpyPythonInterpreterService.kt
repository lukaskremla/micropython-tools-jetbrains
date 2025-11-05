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

@file:Suppress("removal")

package dev.micropythontools.core

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import com.intellij.openapi.projectRoots.Sdk
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.sdk.sdkSeemsValid
import com.jetbrains.python.statistics.version
import dev.micropythontools.i18n.MpyBundle

@Service(Service.Level.PROJECT)
internal class MpyPythonInterpreterService(private val project: Project) {
    private val isUv
        get() = findPythonSdk()?.sdkAdditionalData
            ?.let { it::class.java.name == "com.jetbrains.python.sdk.uv.UvSdkAdditionalData" }
            ?: false

    fun runPythonCode(args: List<String>): String {
        val sdk = findPythonSdk() ?: return ""
        val command = if (args.first().contains("uv")) {
            args
        } else {
            listOf(sdk.homePath) + args
        }

        val projectDir = project.basePath ?: "/"
        val commandLine = GeneralCommandLine(command).withWorkDirectory(projectDir)

        val process = CapturingProcessHandler(commandLine)
        val output = process.runProcess(10_000)
        return when {
            output.isCancelled -> throw ExecutionException(MpyBundle.message("python.service.code.execution.cancelled"))
            output.isTimeout -> throw ExecutionException(MpyBundle.message("python.service.code.execution.timed_out"))
            output.exitCode != 0 -> {
                val errorMsg = buildString {
                    append(MpyBundle.message("python.service.code.execution.failed"))
                    if (output.stderr.isNotBlank()) {
                        append("\nError: ${output.stderr}")
                    }
                    if (output.stdout.isNotBlank()) {
                        append("\nOutput: ${output.stdout}")
                    }
                }
                throw ExecutionException(errorMsg)
            }

            else -> {
                output.toString()
            }
        }
    }

    fun checkInterpreterValid(): ValidationResult {
        val sdk = findPythonSdk()

        return if (sdk == null || !sdk.sdkSeemsValid) {
            ValidationResult(MpyBundle.message("python.service.validation.requires.valid.sdk"))
        } else ValidationResult.OK
    }

    fun installPackage(toInstall: String, targetPath: String? = null, installDependencies: Boolean = true) {
        val prefix = if (isUv) "uv" else "-m"

        val command = mutableListOf(
            prefix, "pip", "install",
            toInstall,
            "--disable-pip-version-check",
            "--quiet",
            "--upgrade"
        )

        if (!targetPath.isNullOrBlank()) {
            command.add("--target")
            command.add(targetPath)
        }

        if (!installDependencies) command.add("--no-deps")

        runPythonCode(command)
    }

    /*fun checkPythonPackageValid(name: String, version: String): ValidationResult {
        val pythonSdk = findPythonSdk() ?: return checkInterpreterValid()
        val packageManager = PyPackageManager.getInstance(pythonSdk)

        val pyRequirements = packageManager.parseRequirements("$name==$version")

        val pythonPackage = packageManager.packages?.find { it.name == name }

        if (pythonPackage == null) {
            return ValidationResult(
                MpyBundle.message("python.service.validation.package.not.installed.message", name),
                object :
                    FacetConfigurationQuickFix(MpyBundle.message("python.service.validation.package.not.installed.install.button")) {
                    override fun run(place: JComponent?) {
                        ApplicationManager.getApplication().invokeLater {
                            runWithModalProgressBlocking(
                                project,
                                MpyBundle.message("python.service.validation.package.not.installed.progress.text")
                            ) {
                                packageManager.install(pyRequirements, emptyList())
                            }
                        }
                    }
                }
            )
        }

        if (pythonPackage.version != version) {
            return ValidationResult(
                MpyBundle.message(
                    "python.service.validation.package.not.up.to.date.message",
                    name,
                    pythonPackage.version,
                    version
                ),
                object :
                    FacetConfigurationQuickFix(MpyBundle.message("python.service.validation.package.not.up.to.date.update.button")) {
                    override fun run(place: JComponent?) {
                        ApplicationManager.getApplication().invokeLater {
                            runWithModalProgressBlocking(
                                project,
                                MpyBundle.message("python.service.validation.package.not.up.to.date.progress.text")
                            ) {
                                packageManager.install(pyRequirements, emptyList())
                            }
                        }
                    }
                }
            )
        }

        return ValidationResult.OK
    }*/

    private fun findPythonSdk(): Sdk? {
        return project
            .modules
            .mapNotNull { PythonSdkUtil.findPythonSdk(it) }
            .firstOrNull { it.version.isAtLeast(LanguageLevel.PYTHON310) }
    }
}