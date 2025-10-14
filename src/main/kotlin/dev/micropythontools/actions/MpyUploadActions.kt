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
import dev.micropythontools.core.MpyProjectFileService
import dev.micropythontools.i18n.MpyBundle
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
        canRunInBackground = true,
        cancelledMessage = MpyBundle.message("action.upload.cancelled"),
        timedOutMessage = MpyBundle.message("action.upload.timeout")
    )
) {
    init {
        templatePresentation.icon = AllIcons.Actions.Upload
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT
}

internal class MpyUploadActionGroup : ActionGroup(MpyBundle.message("action.upload.group.text"), true) {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val projectFileService = project?.service<MpyProjectFileService>()
        val projectDir = project?.guessProjectDir() ?: return

        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val excludedItems = projectFileService?.collectExcluded()

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
                e.presentation.text = MpyBundle.message("action.upload.group.text.folder.one")
            } else {
                e.presentation.text = MpyBundle.message("action.upload.group.text.folder.multiple")
            }
        } else if (folderCount == 0) {
            if (fileCount == 1) {
                e.presentation.text = MpyBundle.message("action.upload.group.text.file.one")
            } else {
                e.presentation.text = MpyBundle.message("action.upload.group.text.file.multiple")
            }
        } else {
            e.presentation.text = MpyBundle.message("action.upload.group.text.items")
        }
    }

    override fun getChildren(e: AnActionEvent?): Array<out AnAction?> {
        return arrayOf(MpyUploadRelativeToDeviceRootAction(), MpyUploadRelativeToParentAction())
    }
}

internal class MpyUploadRelativeToDeviceRootAction :
    MpyUploadActionBase(MpyBundle.message("action.upload.relative.to.text.root")) {
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

        fileTransferService.performUpload(
            initialFilesToUpload = sanitizedFiles,
            relativeToFolders = parentFolders,
            targetDestination = "/"
        )
    }
}

internal class MpyUploadRelativeToParentAction :
    MpyUploadActionBase(MpyBundle.message("action.upload.relative.to.text")) {
    override fun performAction(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (files.isNullOrEmpty()) return

        fileTransferService.uploadItems(files.toSet())
    }

    @Suppress("DialogTitleCapitalization")
    override fun customUpdate(e: AnActionEvent) {
        val sourcesRoots = projectFileService.collectMpySourceRoots()
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (files.isNullOrEmpty()) {
            e.presentation.isEnabled = false
            return
        }

        if (files.all { it in sourcesRoots }) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val rootsHit = files.mapNotNull { f ->
            sourcesRoots.firstOrNull { root -> VfsUtil.isAncestor(root, f, false) }
        }.toSet()

        when {
            rootsHit.isEmpty() -> {
                e.presentation.text = MpyBundle.message("action.upload.relative.to.text.project.root")
            }

            files.all { f -> sourcesRoots.any { r -> VfsUtil.isAncestor(r, f, false) } } -> {
                e.presentation.text = if (rootsHit.size == 1) {
                    MpyBundle.message("action.upload.relative.to.text.sources.root.one")
                } else {
                    MpyBundle.message("action.upload.relative.to.text.sources.root.multiple")
                }
            }

            else -> {
                e.presentation.text = MpyBundle.message("action.upload.relative.to.text.mixed.selection")
            }
        }
    }
}

internal class MpyUploadProjectAction : MpyUploadActionBase(MpyBundle.message("action.upload.project.text")) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.Upload
    }

    override fun performAction(e: AnActionEvent) {
        fileTransferService.uploadProject()
    }

    override fun customUpdate(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val projectDir = e.project?.guessProjectDir() ?: return

        if (files?.any { it == projectDir } == false) {
            e.presentation.isEnabledAndVisible = false
        }
    }
}