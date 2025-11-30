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
import com.intellij.execution.process.*
import com.intellij.facet.ui.FacetConfigurationQuickFix
import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Key
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.sdk.sdkSeemsValid
import com.jetbrains.python.statistics.version
import dev.micropythontools.core.MpyPaths.ESPTOOL_PACKAGE_NAME
import dev.micropythontools.core.MpyPaths.ESPTOOL_VERSION
import dev.micropythontools.freemium.MpyProServiceInterface
import dev.micropythontools.i18n.MpyBundle
import java.io.File
import javax.swing.JComponent
import kotlin.io.path.absolutePathString

@Service(Service.Level.PROJECT)
internal class MpyPythonInterpreterService(private val project: Project) {
    private val proService = project.service<MpyProServiceInterface>()

    private val isUv
        get() = findPythonSdk()?.sdkAdditionalData
            ?.let { it::class.java.name == "com.jetbrains.python.sdk.uv.UvSdkAdditionalData" }
            ?: false

    fun checkInterpreterValid(): ValidationResult {
        val sdk = findPythonSdk()

        return if (sdk == null || !sdk.sdkSeemsValid) {
            ValidationResult(MpyBundle.message("python.service.validation.requires.valid.sdk"))
        } else ValidationResult.OK
    }

    fun checkDependenciesValid(): ValidationResult {
        // Validate pro dependencies
        val proDependenciesValidationResult = proService.checkProDependenciesValid(project)

        return if (!proDependenciesValidationResult.isOk || checkMissingPythonPackages().isNotEmpty()) {
            ValidationResult(
                "Some of the plugin's dependencies are missing or require updates",
                object :
                    FacetConfigurationQuickFix(MpyBundle.message("python.service.validation.package.not.installed.install.button")) {
                    override fun run(place: JComponent?) {
                        ApplicationManager.getApplication().invokeLater {
                            runWithModalProgressBlocking(
                                project,
                                "Installing/Updating MicroPython Tools dependencies..."
                            ) {
                                ensureDependenciesInstalled()
                            }
                        }
                    }
                }
            )
        } else ValidationResult.OK
    }

    fun ensureDependenciesInstalled() {
        val missingPythonPackages = checkMissingPythonPackages()

        missingPythonPackages.forEach { (name, version) ->
            val targetPath = getPackagePythonPath(name, version)

            installPackage("$name==$version", targetPath)
        }

        // Ensure pro dependencies are installed
        proService.ensureProDependenciesInstalled(project)
    }

    private fun checkMissingPythonPackages(): List<Pair<String, String>> {
        val packagesToInstall = mutableListOf<Pair<String, String>>()

        // List of python package dependencies to check
        val packagesToCheck = listOf(
            Pair(ESPTOOL_PACKAGE_NAME, ESPTOOL_VERSION)
        )

        // Check all python package dependencies
        packagesToCheck.forEach { (name, version) ->
            val packageDir = MpyPaths.packagesBaseDir.resolve("$name/$version").toFile()

            val distInfoDir = packageDir.resolve("$name-$version.dist-info")

            if (!distInfoDir.exists()) {
                packagesToInstall.add(Pair(name, version))
            }
        }

        return packagesToInstall
    }

    fun installPackage(toInstall: String, targetPath: String, installDependencies: Boolean = true) {
        // Ensure the directory is clean first
        val targetDir = File(targetPath)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }

        val prefix = if (isUv) "uv" else "-m"

        val command = mutableListOf(
            prefix, "pip", "install",
            toInstall,
            "--disable-pip-version-check",
            "--quiet",
            "--target",
            targetPath
        )

        if (!installDependencies) command.add("--no-deps")

        runPythonCode(command)

        // Replace version specifiers internally to allow requiring simpler parameters
        val distInfoPart = toInstall
            .replace("==", "-")
            .replace("~=", "-")

        val distInfoDir = targetDir.resolve("$distInfoPart.dist-info")

        if (!distInfoDir.exists()) {
            throw RuntimeException("Error installing package \"$toInstall\": Failed to verify installation succeeded")
        }
    }

    fun getPackagePythonPath(packageName: String, version: String): String {
        return MpyPaths.packagesBaseDir.resolve("$packageName/$version").absolutePathString()
    }

    /**
     * Runs Python code with real-time line-by-line output callback.
     *
     * @param args Command line arguments
     * @param env Environment variables
     * @param onOutput Callback invoked for each line of output (both stdout and stderr)
     * @return The OSProcessHandler for the running process (can be used for cancellation)
     * @throws ExecutionException if the process fails or exits with non-zero code
     */
    fun runPythonCodeWithCallback(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        onOutput: (String) -> Unit
    ): OSProcessHandler {
        val sdk = findPythonSdk() ?: throw ExecutionException("No Python SDK found")
        val command = if (args.first().contains("uv")) {
            args
        } else {
            listOf(sdk.homePath) + args
        }

        val projectDir = project.basePath ?: "/"
        val commandLine = GeneralCommandLine(command)
            .withWorkDirectory(projectDir)
            .withEnvironment(env)

        val processHandler = OSProcessHandler(commandLine)

        val errorOutput = StringBuilder()
        var hasError = false

        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text?.trim() ?: return
                if (text.isBlank()) return

                when (outputType) {
                    ProcessOutputTypes.STDOUT -> {
                        onOutput(text)
                    }

                    ProcessOutputTypes.STDERR -> {
                        errorOutput.append(text).append("\n")
                        onOutput(text) // Also report stderr to callback
                    }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                if (event.exitCode != 0) {
                    hasError = true
                }
            }
        })

        processHandler.startNotify()
        processHandler.waitFor()

        if (hasError) {
            val errorMsg = buildString {
                append(MpyBundle.message("python.service.code.execution.failed"))
                if (errorOutput.isNotBlank()) {
                    append("\n").append(errorOutput.toString())
                }
            }
            throw ExecutionException(errorMsg)
        }

        return processHandler
    }

    private fun findPythonSdk(): Sdk? {
        return project
            .modules
            .mapNotNull { PythonSdkUtil.findPythonSdk(it) }
            .firstOrNull { it.version.isAtLeast(LanguageLevel.PYTHON310) }
    }

    private fun runPythonCode(args: List<String>): String {
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

}