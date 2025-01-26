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
import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EmptyRunProfileState
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.NOTIFICATION_GROUP
import dev.micropythontools.ui.fileSystemWidget

/**
 * @authors Lukas Kremla
 */
class MpyFlashConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<MpyFlashConfigurationOptions>(
    project,
    factory,
    name
), LocatableConfiguration {

    override fun suggestedName(): String {
        val baseName = when {
            options.flashingProject -> "Flash Project"
            else -> "Flash Selection"
        }

        if (name == baseName) return baseName

        val existingNames = project.getService<RunManager>(RunManager::class.java)
            .allConfigurationsList
            .map { it.name }

        if (baseName !in existingNames) return baseName

        var counter = 1
        while ("$baseName ($counter)" in existingNames) {
            counter++
        }
        return "$baseName ($counter)"
    }

    override fun isGeneratedName(): Boolean = listOf("Flash Project", "Flash Selection").any { it in name }

    val options: MpyFlashConfigurationOptions
        get() = super.getOptions() as MpyFlashConfigurationOptions

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        try {
            checkConfiguration()
        } catch (e: RuntimeConfigurationError) {
            Notifications.Bus.notify(
                Notification(
                    NOTIFICATION_GROUP,
                    "Cannot run \"${name}\". ${e.localizedMessage}",
                    NotificationType.ERROR
                ), project
            )
            return null
        }

        val success: Boolean

        var ssid = ""
        var wifiPassword = ""

        runWithModalProgressBlocking(project, "Retrieving credentials...") {
            val wifiCredentials = project.service<MpySettingsService>().retrieveWifiCredentials()

            ssid = wifiCredentials.userName ?: ""
            wifiPassword = wifiCredentials.getPasswordAsString() ?: ""
        }

        val transferService = project.service<MpyTransferService>()

        with(options) {
            if (flashingProject) {
                success = transferService.uploadProject(
                    excludedPaths,
                    synchronize,
                    excludePaths,
                    alwaysUseFTP
                )
            } else {
                val toUpload = selectedPaths.mapNotNull { path ->
                    StandardFileSystems.local().findFileByPath(path)
                }.toSet()

                success = transferService.uploadItems(
                    toUpload,
                    excludedPaths,
                    synchronize,
                    excludePaths,
                    alwaysUseFTP
                )
            }
            if (success) {
                val fileSystemWidget = fileSystemWidget(project)
                if (resetOnSuccess) fileSystemWidget?.reset()
                if (switchToReplOnSuccess) fileSystemWidget?.activateRepl()
                return EmptyRunProfileState.INSTANCE
            } else {
                return null
            }
        }
    }

    override fun checkConfiguration() {
        super<RunConfigurationBase>.checkConfiguration()

        val settings = project.service<MpySettingsService>()

        val mpySourceFolders = settings.state.mpySourcePaths
            .mapNotNull { StandardFileSystems.local().findFileByPath(it) }

        if (!settings.state.isPluginEnabled) {
            throw RuntimeConfigurationError(
                "MicroPython support was not enabled for this project",
                Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java) }
            )
        }

        // When the whole project is being flashed
        if (options.flashingProject && mpySourceFolders.isEmpty()) {
            throw RuntimeConfigurationError("No folders were marked as MicroPython sources")
        }

        // When a selection is being flashed
        if (!options.flashingProject) {
            if (options.selectedPaths.any { StandardFileSystems.local().findFileByPath(it) == null }) {
                throw RuntimeConfigurationError(
                    "One or more of the selected MicroPython source folders no longer exist in the file system"
                )
            }

            if (options.selectedPaths.any { selectedPath ->
                    val selectedFile = StandardFileSystems.local().findFileByPath(selectedPath)
                    selectedFile?.let { file ->
                        mpySourceFolders.any { sourceFolder ->
                            sourceFolder == file
                        }
                    } == true
                }) {
                throw RuntimeConfigurationWarning(
                    "One or more of the selected folders is no longer marked as a MicroPython source folder"
                )
            }
        }
    }

    fun saveOptions(
        flashingProject: Boolean,
        selectedPaths: MutableList<String>,
        resetOnSuccess: Boolean,
        switchToReplOnSuccess: Boolean,
        alwaysUseFTP: Boolean,
        synchronize: Boolean,
        excludePaths: Boolean,
        excludedPaths: MutableList<String>
    ) {
        options.flashingProject = flashingProject
        options.selectedPaths = selectedPaths
        options.resetOnSuccess = resetOnSuccess
        options.switchToReplOnSuccess = switchToReplOnSuccess
        options.alwaysUseFTP = alwaysUseFTP
        options.synchronize = synchronize
        options.excludePaths = excludePaths
        options.excludedPaths = excludedPaths
    }

    override fun getConfigurationEditor() = MpyFlashConfigurationEditor(project, this)
}