package dev.micropythontools.inspections

import com.intellij.codeInspection.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.stubs.MpyStubPackageService

internal class MpyStubPackageInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val settings = file.project.service<MpySettingsService>()

        if (!settings.state.isPluginEnabled || !settings.state.areStubsEnabled) {
            return null
        }

        val result = file.project.service<MpyStubPackageService>().checkStubPackageValidity()

        if (result.isOk) return null

        @Suppress("DuplicatedCode") // No value in extracting this functionality
        val fixes = result.quickFix?.let { quickFix ->
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
                result.errorMessage,
                isOnTheFly,
                fixes,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            )
        )
    }
}