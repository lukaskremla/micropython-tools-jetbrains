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

package dev.micropythontools.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.settings.MpySettingsService

internal abstract class MpyUploadActionBase(
    text: String
) : MpyAction(
    text,
    MpyActionOptions(
        visibleWhen = VisibleWhen.PLUGIN_ENABLED,
        enabledWhen = EnabledWhen.PLUGIN_ENABLED,
        requiresConnection = true,
        requiresRefreshAfter = false, // A manual performReplAction is called by the uploading class
        cancelledMessage = "Upload cancelled"
    )
) {
    init {
        templatePresentation.icon = AllIcons.Actions.Upload
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT
}

@Suppress("DialogTitleCapitalization")
internal class MpyUploadActionGroup : ActionGroup("Upload Item(s) to MicroPython Device", true) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val transferService = project?.service<MpyTransferService>()
        val projectDir = project?.guessProjectDir() ?: return

        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val excludedItems = transferService?.collectExcluded()

        // If the plugin is disabled
        // if files are empty or the selection contains excluded items
        // If the selection contains the project directory
        if (!project.service<MpySettingsService>().state.isPluginEnabled ||
            files.isNullOrEmpty() || excludedItems == null || excludedItems.any { excludedItem ->
                files.any { candidate ->
                    VfsUtil.isAncestor(excludedItem, candidate, false)
                }
            } || files.any { it == projectDir }
        ) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        var folderCount = 0
        var fileCount = 0

        for (file in files.iterator()) {
            if (file == null || !file.isInLocalFileSystem || ModuleUtil.findModuleForFile(file, project) == null) {
                return
            }

            if (file.isDirectory) {
                folderCount++
            } else {
                fileCount++
            }
        }

        if (fileCount == 0) {
            if (folderCount == 1) {
                e.presentation.text = "Upload Folder to MicroPython Device"
            } else {
                e.presentation.text = "Upload Folders to MicroPython Device"
            }
        } else if (folderCount == 0) {
            if (fileCount == 1) {
                e.presentation.text = "Upload File to MicroPython Device"
            } else {
                e.presentation.text = "Upload Files to MicroPython Device"
            }
        } else {
            e.presentation.text = "Upload Items to MicroPython Device"
        }
    }

    override fun getChildren(e: AnActionEvent?): Array<out AnAction?> {
        return arrayOf(MpyUploadRelativeToDeviceRootAction(), MpyUploadRelativeToParentAction())
    }
}

internal class MpyUploadRelativeToDeviceRootAction : MpyUploadActionBase("Upload to Device Root \"/\"") {
    override fun performAction(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (files.isNullOrEmpty()) return

        val sanitizedFiles = files.filter { candidate ->
            files.none { potentialParent ->
                VfsUtil.isAncestor(potentialParent, candidate, true)
            }
        }.toSet()

        val parentFolders = files
            .map { it.parent }
            .toSet()

        transferService.performUpload(
            initialFilesToUpload = sanitizedFiles,
            relativeToFolders = parentFolders,
            targetDestination = "/"
        )
    }
}

@Suppress("DialogTitleCapitalization")
internal class MpyUploadRelativeToParentAction : MpyUploadActionBase("Upload Relative to") {
    override fun performAction(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (files.isNullOrEmpty()) return

        transferService.uploadItems(files.toSet())
    }

    override fun customUpdate(e: AnActionEvent) {
        val sourcesRoots = transferService.collectMpySourceRoots()
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (files.isNullOrEmpty()) {
            e.presentation.isEnabled = false
            return
        }

        if (files.all { sourcesRoots.contains(it) }) {
            e.presentation.isEnabledAndVisible = false
        }
        if (files.none { candidateFile ->
                sourcesRoots.any { sourceRoot ->
                    VfsUtil.isAncestor(sourceRoot, candidateFile, false)
                }
            }) {
            e.presentation.text = "Upload Relative to Project Root"
        } else if (files.all { candidateFile ->
                sourcesRoots.any { sourceRoot ->
                    VfsUtil.isAncestor(sourceRoot, candidateFile, false)
                }
            }) {
            e.presentation.text = "Upload Relative to MicroPython Sources Root(s)"
        } else {
            e.presentation.text = "Upload relative to... (mixed selection)"
        }
    }
}

internal class MpyUploadProjectAction : MpyUploadActionBase("Upload Project") {
    init {
        this.templatePresentation.icon = AllIcons.Actions.Upload
    }

    override fun performAction(e: AnActionEvent) {
        transferService.uploadProject()
    }

    override fun customUpdate(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val projectDir = e.project?.guessProjectDir() ?: return

        if (files?.any { it == projectDir } == false) {
            e.presentation.isEnabledAndVisible = false
        }
    }
}