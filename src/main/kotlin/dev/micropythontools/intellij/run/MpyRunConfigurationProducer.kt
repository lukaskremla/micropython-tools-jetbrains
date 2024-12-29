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

package dev.micropythontools.intellij.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.facet.FacetManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.testFramework.LightVirtualFile
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import dev.micropythontools.intellij.settings.ID

/**
 * @author Mikhail Golubev
 */
class MpyRunConfigurationProducer : LazyRunConfigurationProducer<MpyRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory {
        return getInstance().factory
    }

    override fun isConfigurationFromContext(configuration: MpyRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (!facetEnabledForElement(file, context.project)) return false
        if (file is LightVirtualFile) return false
        return configuration.path == file.path
    }

    override fun setupConfigurationFromContext(
        configuration: MpyRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (!facetEnabledForElement(file, context.project)) return false
        configuration.path = file.path
        configuration.setGeneratedName()
        configuration.setModule(ModuleUtilCore.findModuleForFile(file, context.project))
        return true
    }

    private fun facetEnabledForElement(virtualFile: VirtualFile, project: Project): Boolean {
        val module = ModuleUtilCore.findModuleForFile(virtualFile, project) ?: return false
        return FacetManager.getInstance(module)?.getFacetByType(ID) != null
    }

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext) =
        other.configuration is AbstractPythonRunConfiguration<*>
}
