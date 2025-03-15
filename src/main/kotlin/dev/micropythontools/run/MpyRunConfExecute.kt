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
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.NOTIFICATION_GROUP
import dev.micropythontools.ui.fileSystemWidget
import dev.micropythontools.ui.performReplAction

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
        val path = options.path ?: return ""
        val parts = path.split("/")
        return parts[parts.size - 1]
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
        resetOnSuccess: Boolean,
        switchToReplOnSuccess: Boolean,
    ) {
        options.path = path
        options.switchToReplOnSuccess = switchToReplOnSuccess
        options.resetOnSuccess = resetOnSuccess
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

        val path = options.path!!
        val switchToReplOnSuccess = options.switchToReplOnSuccess
        val resetOnSuccess = options.resetOnSuccess

        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            val file = StandardFileSystems.local().findFileByPath(path)!!
            val code = file.readText()
            performReplAction(project, true, "Run code", false, { fileSystemWidget, _ ->
                fileSystemWidget.instantRun(code, false)
            })

            val fileSystemWidget = fileSystemWidget(project)
            if (resetOnSuccess) fileSystemWidget?.reset()
            if (switchToReplOnSuccess) fileSystemWidget?.activateRepl()

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
                path.isNullOrEmpty() -> "No file path specified. Please select a file to execute."
                else -> "File not found: $path. Please select a valid file."
            }

            throw RuntimeConfigurationError(
                message,
                Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java) }
            )
        }
    }

    override fun getConfigurationEditor() = MpyRunConfExecuteEditor(this)
}