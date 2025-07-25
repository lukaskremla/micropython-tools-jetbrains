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

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.readText
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.performReplAction
import dev.micropythontools.ui.NOTIFICATION_GROUP

internal class MpyRunConfExecuteState(
    private val project: Project,
    private val options: MpyRunConfExecuteOptions,
    private val fileName: String
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
        val deviceService = project.service<MpyDeviceService>()

        val path = options.path!!
        val switchToReplOnSuccess = options.switchToReplOnSuccess

        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            val file = StandardFileSystems.local().findFileByPath(path)!!
            val code = file.readText()
            performReplAction(project, true, "Run code", false, "REPL execution cancelled", { _ ->
                deviceService.instantRun(code)
            })

            if (switchToReplOnSuccess) deviceService.activateRepl()

            return null
        } catch (e: Throwable) {
            Notifications.Bus.notify(
                Notification(
                    NOTIFICATION_GROUP,
                    "Failed to execute \"${fileName}\"",
                    "An error occurred: ${e.message ?: e.javaClass.simpleName}",
                    NotificationType.ERROR
                ), project
            )
            return null
        }
    }
}