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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.readText
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.performReplAction
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.NOTIFICATION_GROUP

class MpyRunConfExecute(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<MpyRunConfExecuteOptions>(
    project,
    factory,
    name
), LocatableConfiguration {

    private fun getFileName(): String {
        val path = options.path ?: return "Unknown"
        val file = StandardFileSystems.local().findFileByPath(path) ?: return "Unknown"
        return file.name
    }

    override fun suggestedName(): String {
        val baseName = "Execute ${getFileName()}"

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

    override fun isGeneratedName(): Boolean = "Execute ${getFileName()}" in name

    val options: MpyRunConfExecuteOptions
        get() = super.getOptions() as MpyRunConfExecuteOptions

    fun saveOptions(
        path: String,
        switchToReplOnSuccess: Boolean,
    ) {
        options.path = path
        options.switchToReplOnSuccess = switchToReplOnSuccess
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

        val deviceService = project.service<MpyDeviceService>()

        val path = options.path!!
        val switchToReplOnSuccess = options.switchToReplOnSuccess

        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            val file = StandardFileSystems.local().findFileByPath(path)!!
            val code = file.readText()
            performReplAction(project, true, "Run code", false, "REPL execution cancelled", { _ ->
                deviceService.instantRun(code, false)
            })

            if (switchToReplOnSuccess) deviceService.activateRepl()

            return EmptyRunProfileState.INSTANCE
        } catch (e: Throwable) {
            Notifications.Bus.notify(
                Notification(
                    NOTIFICATION_GROUP,
                    "Failed to execute \"${name}\"",
                    "An error occurred: ${e.message ?: e.javaClass.simpleName}",
                    NotificationType.ERROR
                ), project
            )
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

        val path = options.path
        if (path == null || StandardFileSystems.local().findFileByPath(path) == null) {
            val message = when {
                path.isNullOrEmpty() -> "No file path specified. Please select a file to execute"
                !path.endsWith(".py") && !path.endsWith(".mpy") -> "The specified path is not a valid \".py\" or \".mpy\" file"
                else -> "File not found: \"$path\". Please select a valid file"
            }

            throw RuntimeConfigurationError(message)
        }
    }

    override fun getConfigurationEditor() = MpyRunConfExecuteEditor(this)
}