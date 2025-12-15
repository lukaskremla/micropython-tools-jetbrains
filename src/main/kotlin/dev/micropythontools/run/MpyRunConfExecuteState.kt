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

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.readText
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.i18n.MpyBundle

internal class MpyRunConfExecuteState(
    private val project: Project,
    private val options: MpyRunConfExecuteOptions
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val deviceService = project.service<MpyDeviceService>()

        // Create console view
        val consoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console

        // Create and attach process handler
        val processHandler = MpyRunConfProcessHandler()
        consoleView.attachToProcess(processHandler)
        processHandler.startNotify()

        try {
            val path = options.path!!
            val switchToReplOnSuccess = options.switchToReplOnSuccess

            FileDocumentManager.getInstance().saveAllDocuments()
            val file = StandardFileSystems.local().findFileByPath(path)!!
            val code = file.readText()

            deviceService.performReplAction(
                project,
                connectionRequired = true,
                requiresRefreshAfter = false,
                canRunInBackground = false,
                description = MpyBundle.message("action.execute.file.text"),
                cancelledMessage = MpyBundle.message("action.execute.cancelled"),
                timedOutMessage = MpyBundle.message("action.execute.timeout"),
                action = { _ ->
                    deviceService.instantRun(code)
                }
            )

            if (switchToReplOnSuccess) deviceService.activateRepl()

            consoleView.print(
                "${MpyBundle.message("run.conf.execute.state.execution.completed")}\n",
                ConsoleViewContentType.NORMAL_OUTPUT
            )

            processHandler.completeWithSuccess()
        } catch (e: Throwable) {
            consoleView.print(
                "${
                    MpyBundle.message(
                        "run.conf.execute.state.error.execution",
                        e.message ?: e.javaClass.simpleName
                    )
                }\n", ConsoleViewContentType.ERROR_OUTPUT
            )

            processHandler.completeWithFailure()
        }

        return DefaultExecutionResult(consoleView, processHandler)
    }
}