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
import dev.micropythontools.core.MpyProjectFileService
import dev.micropythontools.core.MpyValidators
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import persistence.MpyRunConfUploadOptions

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
    private val projectFileService = project.service<MpyProjectFileService>()

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
            0 -> MpyBundle.message("run.conf.upload.name.project")
            1 -> MpyBundle.message("run.conf.upload.name.selection")
            else -> MpyBundle.message("run.conf.upload.name.item", getFileName())
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
        val isCustomPathGenerated = MpyBundle.message("run.conf.upload.name.item", getFileName()) == name
        val isProjectOrSelectedGenerated = listOf(
            MpyBundle.message("run.conf.upload.name.project"),
            MpyBundle.message("run.conf.upload.name.selection")
        ).any { it in name }

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
                    MpyBundle.message("notification.group.name"),
                    MpyBundle.message("run.conf.error.cannot.run", name, e.localizedMessage),
                    NotificationType.ERROR
                ), project
            )
            return null
        }

        return MpyRunConfUploadState(project, options)
    }

    override fun checkConfiguration() {
        super<RunConfigurationBase>.checkConfiguration()

        val mpySourceFolders = projectFileService.collectMpySourceRoots()

        if (!settings.state.isPluginEnabled) {
            throw RuntimeConfigurationError(
                MpyBundle.message("run.conf.error.mpy.support.not.enabled"),
                Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java) }
            )
        }

        // When a selection is being uploaded
        if (options.uploadMode == 1) {
            if (options.selectedPaths.any { StandardFileSystems.local().findFileByPath(it) == null }) {
                throw RuntimeConfigurationError(
                    MpyBundle.message("run.conf.upload.error.selected.root.does.not.exist")
                )
            }

            if (options.selectedPaths.any { selectedPath ->
                    val selectedFile = StandardFileSystems.local().findFileByPath(selectedPath)
                    selectedFile != null && !mpySourceFolders.any { sourceFolder ->
                        sourceFolder == selectedFile
                    }
                }) {
                throw RuntimeConfigurationError(
                    MpyBundle.message("run.conf.upload.error.selected.root.no.longer.marked")
                )
            }
        }

        // When a custom path is being uploaded
        if (options.uploadMode == 2) {
            val path = options.path
            val file = StandardFileSystems.local().findFileByPath(path ?: "")
            if (path == null || file == null) {
                throw RuntimeConfigurationError(
                    MpyBundle.message("run.conf.upload.error.file.not.found", path ?: "\"\"")
                )
            }
            if (projectFileService.collectExcluded().contains(file)) {
                throw RuntimeConfigurationError(
                    MpyBundle.message("run.conf.upload.error.file.excluded", path)
                )
            }
            val targetPath = options.uploadToPath
            val validationResult = MpyValidators.isRunConfTargetPathValid(targetPath ?: "/")
            if (validationResult != null) {
                throw RuntimeConfigurationError(
                    validationResult
                )
            }
        }
    }

    override fun getConfigurationEditor() = MpyRunConfUploadEditor(this)
}