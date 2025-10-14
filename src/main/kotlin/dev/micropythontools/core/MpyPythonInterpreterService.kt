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
import com.intellij.facet.ui.FacetConfigurationQuickFix
import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.jetbrains.python.packaging.PyPackageManager
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.statistics.version
import dev.micropythontools.i18n.MpyBundle
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
internal class MpyPythonInterpreterService(private val project: Project) {
    fun runPythonCode(args: List<String>): String {
        val sdk = findPythonSdk() ?: return ""
        val command = listOf(sdk.homePath) + args

        val process = CapturingProcessHandler(GeneralCommandLine(command))
        val output = process.runProcess(10_000)
        return when {
            output.isCancelled -> throw ExecutionException(MpyBundle.message("python.service.code.execution.cancelled"))
            output.isTimeout -> throw ExecutionException(MpyBundle.message("python.service.code.execution.timed_out"))
            output.exitCode != 0 -> throw ExecutionException(MpyBundle.message("python.service.code.execution.failed"))
            else -> {
                output.toString()
            }
        }
    }

    fun checkInterpreterValid(): ValidationResult {
        findPythonSdk() ?: return ValidationResult(MpyBundle.message("python.service.validation.requires.valid.sdk"))

        return ValidationResult.OK
    }

    fun checkPythonPackageValid(name: String, version: String): ValidationResult {
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
    }

    private fun findPythonSdk(): Sdk? {
        return project
            .modules
            .mapNotNull { PythonSdkUtil.findPythonSdk(it) }
            .firstOrNull { it.version.isAtLeast(LanguageLevel.PYTHON310) }
    }
}