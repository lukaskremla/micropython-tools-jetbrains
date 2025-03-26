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

package dev.micropythontools.templates

import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon


class MpyModuleType : ModuleType<MpyModuleBuilder>(ID) {
    override fun createModuleBuilder(): MpyModuleBuilder {
        return MpyModuleBuilder()
    }

    override fun getName(): String {
        return "MicroPython Project"
    }

    override fun getDescription(): String {
        return "MicroPython project template"
    }

    override fun getNodeIcon(b: Boolean): Icon {
        return IconLoader.getIcon("/icons/pluginIcon.svg", this::class.java)
    }

    override fun createWizardSteps(
        wizardContext: WizardContext,
        moduleBuilder: MpyModuleBuilder,
        modulesProvider: ModulesProvider
    ): Array<ModuleWizardStep?> {
        return super.createWizardSteps(wizardContext, moduleBuilder, modulesProvider)
    }

    companion object {
        private const val ID = "MPY_TOOLS_PROJECT_TEMPLATE_MODULE_BUILDER_TYPE"

        val instance: MpyModuleType
            get() = ModuleTypeManager.getInstance().findByID(ID) as MpyModuleType
    }
}