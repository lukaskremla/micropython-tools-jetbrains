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
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import dev.micropythontools.communication.MpyFileTransferService
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.MpySettingsService
import io.ktor.utils.io.*

internal class MpyRunConfUploadState(
    private val project: Project,
    private val options: MpyRunConfUploadOptions
) : RunProfileState {

    private val settings = project.service<MpySettingsService>()
    private val fileTransferService = project.service<MpyFileTransferService>()

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
        // Create console view
        val consoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console

        // Create and attach process handler
        val processHandler = NopProcessHandler()
        consoleView.attachToProcess(processHandler)
        processHandler.startNotify()

        var success = false

        try {
            with(options) {
                when (options.uploadMode) {
                    0 -> {
                        success = fileTransferService.uploadProject(
                            consoleView,
                            excludedPaths.toSet(),
                            synchronize,
                            excludePaths,
                            resetOnSuccess,
                            switchToReplOnSuccess,
                            forceBlocking
                        )
                    }

                    1 -> {
                        val toUpload = selectedPaths.mapNotNull { path ->
                            StandardFileSystems.local().findFileByPath(path)
                        }.toSet()

                        success = fileTransferService.uploadItems(
                            consoleView,
                            toUpload,
                            excludedPaths.toSet(),
                            synchronize,
                            excludePaths,
                            resetOnSuccess,
                            switchToReplOnSuccess,
                            forceBlocking
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

                        success = fileTransferService.performUpload(
                            consoleView = consoleView,
                            initialFilesToUpload = toUpload,
                            relativeToFolders = setOf(relativeToFolder),
                            targetDestination = options.uploadToPath ?: "/",
                            excludedPaths = excludedPaths.toSet(),
                            shouldSynchronize = synchronize,
                            shouldExcludePaths = excludePaths,
                            customPathFolders = customPathFolders ?: emptySet(),
                            resetOnSuccess = resetOnSuccess,
                            switchToReplOnSuccess = switchToReplOnSuccess,
                            forceBlocking = forceBlocking
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            consoleView.print(
                "${MpyBundle.message("run.conf.upload.state.error.execution", e.message ?: "")}\n",
                ConsoleViewContentType.ERROR_OUTPUT
            )
        } finally {
            processHandler.destroyProcess()
        }

        return if ((!settings.state.backgroundUploadsDownloads || options.forceBlocking) && !success) {
            null
        } else {
            DefaultExecutionResult(consoleView, processHandler)
        }
    }
}