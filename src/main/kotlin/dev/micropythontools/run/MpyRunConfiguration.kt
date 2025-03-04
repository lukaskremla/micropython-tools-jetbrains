/*
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2024-2025 Lukas Kremla
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

package dev.micropythontools.run

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EmptyRunProfileState
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.util.PathUtil
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.NOTIFICATION_GROUP
import dev.micropythontools.ui.fileSystemWidget

/**
 * @authors Lukas Kremla
 */
class MpyRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<MpyRunConfigurationOptions>(
    project,
    factory,
    name
), LocatableConfiguration {

    private var myGeneratedName = true

    override fun suggestedName(): String {
        val currentPath = options.path
        return when {
            currentPath.isNullOrBlank() || currentPath == "" -> "Flash Project"
            else -> "Flash ${PathUtil.getFileName(currentPath)}"
        }
    }

    override fun isGeneratedName(): Boolean {
        return myGeneratedName
    }

    override fun getOptions(): MpyRunConfigurationOptions {
        return super.getOptions() as MpyRunConfigurationOptions
    }

    fun saveOptions(
        path: String,
        runReplOnSuccess: Boolean,
        resetOnSuccess: Boolean,
        synchronize: Boolean,
        excludePaths: Boolean,
        excludedPaths: MutableList<String>
    ) {
        suggestedName()

        options.path = path
        options.runReplOnSuccess = runReplOnSuccess
        options.resetOnSuccess = resetOnSuccess
        options.synchronize = synchronize
        options.excludePaths = excludePaths
        options.excludedPaths = excludedPaths
    }

    fun getOptionsObject(): MpyRunConfigurationOptions {
        return options
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        try {
            checkConfiguration()
        } catch (_: RuntimeConfigurationError) {
            Notifications.Bus.notify(
                Notification(
                    NOTIFICATION_GROUP,
                    "Cannot run \"${name}\". MicroPython support was not enabled!",
                    NotificationType.ERROR
                ), project
            )

            return null
        }

        val path: String = options.path ?: ""
        val runReplOnSuccess = options.runReplOnSuccess
        val resetOnSuccess = options.resetOnSuccess
        val synchronize = options.synchronize
        val excludePaths = options.excludePaths
        val excludedPaths = options.excludedPaths

        val success: Boolean
        val projectDir = project.guessProjectDir()
        val projectPath = projectDir?.path

        val transferService = project.service<MpyTransferService>()

        if (path.isBlank() || (projectPath != null && path == projectPath)) {
            success = transferService.uploadProject(
                excludedPaths,
                synchronize,
                excludePaths
            )
        } else {
            val toUpload = StandardFileSystems.local().findFileByPath(path) ?: return null
            success = transferService.uploadFileOrFolder(
                toUpload,
                excludedPaths,
                synchronize,
                excludePaths
            )
        }
        if (success) {
            val fileSystemWidget = fileSystemWidget(project)
            if (resetOnSuccess) fileSystemWidget?.reset()
            if (runReplOnSuccess) fileSystemWidget?.activateRepl()
            return EmptyRunProfileState.INSTANCE
        } else {
            return null
        }
    }

    override fun checkConfiguration() {
        super<RunConfigurationBase>.checkConfiguration()

        if (!project.service<MpySettingsService>().state.isPluginEnabled) {
            throw RuntimeConfigurationError(
                "MicroPython support was not enabled for this project",
                Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java) }
            )
        }
    }

    override fun getConfigurationEditor() = MpyRunConfigurationEditor(this)
}