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

package dev.micropythontools.inspections

import com.intellij.codeInspection.*
import com.intellij.facet.ui.FacetConfigurationQuickFix
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.micropythontools.util.MpyPythonService

/**
 * @author vlan
 */
class MpyRequirementsInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return null
        val pythonService = module.project.service<MpyPythonService>()
        val result = pythonService.checkValid()
        if (result.isOk) return null

        val facetFix: FacetConfigurationQuickFix? = result.quickFix

        val fix = if (facetFix != null) object : LocalQuickFix {
            @Suppress("DialogTitleCapitalization")
            // The fixButtonText follows proper capitalization in all cases, the warning can be ignored
            override fun getFamilyName() = facetFix.fixButtonText.toString()

            override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
                facetFix.run(null)
            }
        } else null

        val fixes = if (fix != null) arrayOf(fix) else emptyArray()

        return arrayOf(
            manager.createProblemDescriptor(
                file, result.errorMessage, true, fixes,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            )
        )
    }
}