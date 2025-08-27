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
import com.jetbrains.python.statistics.version
import dev.micropythontools.i18n.MpyBundle

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

    fun checkValid(): ValidationResult {
        findPythonSdk() ?: return ValidationResult(MpyBundle.message("python.service.validation.requires.valid.sdk"))

        return ValidationResult.OK
    }

    private fun findPythonSdk(): Sdk? {
        return project
            .modules
            .mapNotNull { PythonSdkUtil.findPythonSdk(it) }
            .firstOrNull { it.version.isAtLeast(LanguageLevel.PYTHON310) }
    }
}