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
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import dev.micropythontools.communication.MpyFileTransferService

internal class MpyRunConfUploadState(
    project: Project,
    private val options: MpyRunConfUploadOptions
) : RunProfileState {

    private val fileTransferService = project.service<MpyFileTransferService>()

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
        val success: Boolean

        println(runner.javaClass)

        with(options) {
            when (options.uploadMode) {
                0 -> {
                    success = fileTransferService.uploadProject(
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

            // Return an execution result with no console (null console view, NopProcessHandler)
            return if (success) {
                val processHandler = NopProcessHandler().apply {
                    startNotify()
                    destroyProcess()
                }
                DefaultExecutionResult(null, processHandler)
            } else null
        }
    }
}