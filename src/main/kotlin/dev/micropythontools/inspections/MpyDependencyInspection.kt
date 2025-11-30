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

package dev.micropythontools.inspections

import com.intellij.codeInspection.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.micropythontools.core.MpyPythonInterpreterService
import dev.micropythontools.settings.MpySettingsService

internal class MpyDependencyInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val settings = file.project.service<MpySettingsService>()
        val interpreterService = file.project.service<MpyPythonInterpreterService>()

        // No inspections needed if MicroPython support is disabled
        if (!settings.state.isPluginEnabled) {
            return null
        }

        // Validate interpreter
        val interpreterResult = interpreterService.checkInterpreterValid()

        if (!interpreterResult.isOk) {
            return arrayOf(
                manager.createProblemDescriptor(
                    file,
                    interpreterResult.errorMessage,
                    isOnTheFly,
                    emptyArray(),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                )
            )
        }

        // Validate dependencies
        val dependenciesResult = interpreterService.checkDependenciesValid()
        if (!dependenciesResult.isOk) {
            @Suppress("DuplicatedCode") // No value in extracting this functionality
            val fixes = dependenciesResult.quickFix?.let { quickFix ->
                arrayOf(object : LocalQuickFix {
                    @Suppress("DialogTitleCapitalization")
                    override fun getFamilyName() = quickFix.fixButtonText.toString()

                    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
                        quickFix.run(null)
                    }
                })
            } ?: emptyArray()

            return arrayOf(
                manager.createProblemDescriptor(
                    file,
                    dependenciesResult.errorMessage,
                    isOnTheFly,
                    fixes,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                )
            )
        }

        return null
    }
}