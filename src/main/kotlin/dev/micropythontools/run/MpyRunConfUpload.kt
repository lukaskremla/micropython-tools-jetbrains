/*
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
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.settings.isRunConfTargetPathValid
import dev.micropythontools.ui.NOTIFICATION_GROUP

/**
 * @authors Lukas Kremla
 */
internal class MpyRunConfUpload(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<MpyRunConfUploadOptions>(
    project,
    factory,
    name
), LocatableConfiguration {

    private val settings = project.service<MpySettingsService>()
    private val transferService = project.service<MpyTransferService>()

    /**
     * Method for determining the filename of an existing file from its path.
     *
     * @param path Specify a path from which the filename is to be determined. If none is specified, the path saved
     * in the run configuration is used
     *
     * @return The file name or "" if it can't be determined
     */
    fun getFileName(path: String? = null): String {
        val path = path ?: options.path
        if (path.isNullOrBlank()) return ""
        val file = StandardFileSystems.local().findFileByPath(path) ?: return ""
        return file.name
    }

    override fun suggestedName(): String {
        val baseName = when (options.uploadMode) {
            0 -> "Upload Project"
            1 -> "Upload Selection"
            else -> "Upload ${getFileName()}"
        }

        if (name == baseName) return baseName

        val existingNames = project.getService(RunManager::class.java)
            .allConfigurationsList
            .map { it.name }

        if (baseName !in existingNames) return baseName

        var counter = 1
        while ("$baseName ($counter)" in existingNames) {
            counter++
        }
        return "$baseName ($counter)"
    }

    override fun isGeneratedName(): Boolean {
        val isCustomPathGenerated = "Upload ${getFileName()}" == name
        val isProjectOrSelectedGenerated = listOf("Upload Project", "Upload Selection").any { it in name }

        return isCustomPathGenerated || isProjectOrSelectedGenerated
    }

    val options: MpyRunConfUploadOptions
        get() = super.getOptions() as MpyRunConfUploadOptions

    fun saveOptions(
        uploadMode: Int,
        selectedPaths: MutableList<String>,
        path: String,
        uploadToPath: String,
        resetOnSuccess: Boolean,
        switchToReplOnSuccess: Boolean,
        synchronize: Boolean,
        excludePaths: Boolean,
        excludedPaths: MutableList<String>
    ) {
        options.uploadMode = uploadMode
        options.selectedPaths = selectedPaths
        options.path = path
        options.uploadToPath = uploadToPath
        options.switchToReplOnSuccess = switchToReplOnSuccess
        options.resetOnSuccess = resetOnSuccess
        options.synchronize = synchronize
        options.excludePaths = excludePaths
        options.excludedPaths = excludedPaths
    }

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

        return MpyRunConfUploadState(project, options)
    }

    override fun checkConfiguration() {
        super<RunConfigurationBase>.checkConfiguration()

        val mpySourceFolders = transferService.collectMpySourceRoots()

        if (!settings.state.isPluginEnabled) {
            throw RuntimeConfigurationError(
                "MicroPython support was not enabled for this project",
                Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java) }
            )
        }

        // When a selection is being uploaded
        if (options.uploadMode == 1) {
            if (options.selectedPaths.any { StandardFileSystems.local().findFileByPath(it) == null }) {
                throw RuntimeConfigurationError(
                    "One or more of the selected MicroPython Sources Roots no longer exists"
                )
            }

            if (options.selectedPaths.any { selectedPath ->
                    val selectedFile = StandardFileSystems.local().findFileByPath(selectedPath)
                    selectedFile != null && !mpySourceFolders.any { sourceFolder ->
                        sourceFolder == selectedFile
                    }
                }) {
                throw RuntimeConfigurationError(
                    "One or more of the selected roots is no longer marked as a MicroPython Sources Root"
                )
            }
        }

        // When a custom path is being uploaded
        if (options.uploadMode == 2) {
            val path = options.path
            val file = StandardFileSystems.local().findFileByPath(path ?: "")
            if (path == null || file == null) {
                throw RuntimeConfigurationError(
                    "File not found: \"$path\". Please select a valid file or folder"
                )
            }
            if (transferService.collectExcluded().contains(file)) {
                throw RuntimeConfigurationError(
                    "File excluded: \"$path\". Excluded folders and their children can't be uploaded"
                )
            }
            val targetPath = options.uploadToPath
            val validationResult = isRunConfTargetPathValid(targetPath ?: "/")
            if (validationResult != null) {
                throw RuntimeConfigurationError(
                    validationResult
                )
            }
        }
    }

    override fun getConfigurationEditor() = MpyRunConfUploadEditor(this)
}