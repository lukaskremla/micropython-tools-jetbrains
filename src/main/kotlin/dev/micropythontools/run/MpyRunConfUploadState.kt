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
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.MpyFileTransferService
import dev.micropythontools.freemium.MpyProServiceInterface
import kotlinx.coroutines.*

internal class RunConfigurationUploadContext(val consoleView: ConsoleView) {
    private val completionDeferred = CompletableDeferred<Boolean>()

    val isComplete: Boolean
        get() = completionDeferred.isCompleted

    @OptIn(ExperimentalCoroutinesApi::class)
    val result: Boolean?
        get() = if (completionDeferred.isCompleted) {
            completionDeferred.getCompleted()
        } else {
            null
        }

    fun markComplete(success: Boolean) {
        completionDeferred.complete(success)
    }
}

internal class MpyRunConfUploadState(
    private val project: Project,
    private val options: MpyRunConfUploadOptions
) : RunProfileState {

    private val proService = project.service<MpyProServiceInterface>()
    private val deviceService = project.service<MpyDeviceService>()
    private val fileTransferService = project.service<MpyFileTransferService>()

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        // Create console view
        val consoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console

        // Create and attach process handler
        val processHandler = MpyRunConfProcessHandler()
        consoleView.attachToProcess(processHandler)
        processHandler.startNotify()

        val runConfigurationUploadContext = RunConfigurationUploadContext(consoleView)

        try {
            with(options) {
                when (options.uploadMode) {
                    0 -> {
                        fileTransferService.uploadProject(
                            runConfigurationUploadContext,
                            excludedPaths.toSet(),
                            synchronize,
                            excludePaths,
                            resetOnSuccess,
                            switchToReplOnSuccess
                        )
                    }

                    1 -> {
                        val toUpload = selectedPaths.mapNotNull { path ->
                            StandardFileSystems.local().findFileByPath(path)
                        }.toSet()

                        fileTransferService.uploadItems(
                            runConfigurationUploadContext,
                            toUpload,
                            excludedPaths.toSet(),
                            synchronize,
                            excludePaths,
                            resetOnSuccess,
                            switchToReplOnSuccess
                        )
                    }

                    else -> {
                        val file = StandardFileSystems.local().findFileByPath(options.path!!)!!

                        val toUpload = if (file.isDirectory) {
                            file.children.toSet()
                        } else setOf(file)

                        val relativeToFolder = if (file.isDirectory) {
                            file
                        } else file.parent

                        val customPathFolders = options.uploadToPath
                            ?.split("/")
                            ?.filter { it.isNotBlank() }
                            ?.foldIndexed(mutableListOf<String>()) { index, acc, folder ->
                                val path = if (index == 0) "/$folder" else "${acc[index - 1]}/$folder"
                                acc.add(path)
                                acc
                            }?.toSet()

                        fileTransferService.performUpload(
                            runConfigurationUploadContext = runConfigurationUploadContext,
                            initialFilesToUpload = toUpload,
                            relativeToFolders = setOf(relativeToFolder),
                            targetDestination = options.uploadToPath ?: "/",
                            excludedPaths = excludedPaths.toSet(),
                            shouldSynchronize = synchronize,
                            shouldExcludePaths = excludePaths,
                            customPathFolders = customPathFolders ?: emptySet(),
                            resetOnSuccess = resetOnSuccess,
                            switchToReplOnSuccess = switchToReplOnSuccess
                        )
                    }
                }
            }

            deviceService.cs.launch(Dispatchers.IO) {
                // Keep monitoring while upload is running
                while (!runConfigurationUploadContext.isComplete) {
                    // Check if the run configuration was terminated
                    if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
                        proService.ensureBackgroundReplJobCancelled() // Cancel the coroutine first
                        break
                    }

                    delay(200)
                }

                // Notify of the result
                if (runConfigurationUploadContext.result ?: false) {
                    processHandler.completeWithSuccess()
                } else {
                    processHandler.completeWithFailure()
                }
            }
        } catch (e: Throwable) {
            processHandler.completeWithFailure()
            throw e
        }

        return DefaultExecutionResult(consoleView, processHandler)
    }
}