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

import com.intellij.execution.RunManager
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.vfs.VfsUtil
import dev.micropythontools.run.MpyRunConfType
import dev.micropythontools.run.MpyRunConfUpload
import dev.micropythontools.run.MpyRunConfUploadFactory
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.sourceroots.MpySourceRootType

class MpyModuleBuilder : ModuleBuilder() {
    override fun getModuleType(): ModuleType<*> {
        return MpyModuleType.instance
    }

    override fun setupRootModel(modifiableRootModel: ModifiableRootModel) {
        val contentEntry = doAddContentEntry(modifiableRootModel) ?: return
        val project = modifiableRootModel.project

        // Create src folder
        val srcDir = VfsUtil.createDirectoryIfMissing(contentEntry.file, "src")

        // Mark as MicroPython Sources Root
        contentEntry.addSourceFolder(srcDir, MpySourceRootType.SOURCE)

        // Create default files
        srcDir.createChildData(this, "main.py")
        srcDir.createChildData(this, "boot.py")

        // Enable MicroPython support
        val settings = project.service<MpySettingsService>()
        settings.state.isPluginEnabled = true

        // Get upload run configuration factory
        val runConfigurationFactory = MpyRunConfUploadFactory(MpyRunConfType())

        // Create Upload Project run configuration
        val runConfiguration = MpyRunConfUpload(
            project,
            runConfigurationFactory,
            "Upload Project"
        ).apply {
            saveOptions(
                uploadMode = 0,
                selectedPaths = emptyList<String>().toMutableList(),
                path = "",
                resetOnSuccess = true,
                switchToReplOnSuccess = true,
                synchronize = true,
                excludePaths = false,
                excludedPaths = emptyList<String>().toMutableList()
            )
        }

        // Create the final run configuration that can be added to the project
        val runnerAndConfigSettings = RunManager.getInstance(project).createConfiguration(
            runConfiguration,
            runConfigurationFactory
        )

        // Add the configuration
        RunManagerImpl.getInstanceImpl(project).addConfiguration(runnerAndConfigSettings)
    }

    override fun getCustomOptionsStep(context: WizardContext?, parentDisposable: Disposable?): ModuleWizardStep? {
        return MpyWizardStep()
    }
}