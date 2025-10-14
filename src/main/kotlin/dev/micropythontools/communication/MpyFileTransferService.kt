/*
 * Copyright 2000-2024 JetBrains s.r.o.
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

package dev.micropythontools.communication

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findOrCreateDirectory
import com.intellij.openapi.vfs.findOrCreateFile
import dev.micropythontools.core.*
import dev.micropythontools.freemium.MpyProServiceInterface
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@Service(Service.Level.PROJECT)
internal class MpyFileTransferService(private val project: Project) {
    private val projectFileService = project.service<MpyProjectFileService>()
    private val deviceService = project.service<MpyDeviceService>()

    fun uploadProject(
        excludedPaths: Set<String> = emptySet(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false
    ): Boolean {

        return performUpload(
            initialIsProjectUpload = true,
            excludedPaths = excludedPaths,
            shouldSynchronize = shouldSynchronize,
            shouldExcludePaths = shouldExcludePaths,
        )
    }

    fun uploadItems(
        filesToUpload: Set<VirtualFile>,
        excludedPaths: Set<String> = emptySet(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false
    ): Boolean {

        return performUpload(
            initialFilesToUpload = filesToUpload,
            excludedPaths = excludedPaths,
            shouldSynchronize = shouldSynchronize,
            shouldExcludePaths = shouldExcludePaths
        )
    }

    fun performUpload(
        initialFilesToUpload: Set<VirtualFile> = emptySet(),
        initialIsProjectUpload: Boolean = false,
        relativeToFolders: Set<VirtualFile> = emptySet(),
        targetDestination: String = "/",
        excludedPaths: Set<String> = emptySet(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false,
        customPathFolders: Set<String> = emptySet()
    ): Boolean {
        val settings = project.service<MpySettingsService>()

        val pathsToExclude = excludedPaths.toMutableSet()

        var startedUploading = false
        var uploadedSuccessfully = false

        // Define the initially collected maps here to allow final verification in the clean-up action
        var fileToTargetPath = mutableMapOf<VirtualFile, String>()
        var folderToTargetPath = mutableMapOf<VirtualFile, String>()

        deviceService.performReplAction(
            project = project,
            connectionRequired = true,
            requiresRefreshAfter = false,
            canRunInBackground = true,
            description = MpyBundle.message("upload.operation.description"),
            cancelledMessage = MpyBundle.message("upload.operation.cancelled"),
            timedOutMessage = MpyBundle.message("upload.operation.timeout"),
            action = { reporter ->
                val (filesToUpload, foldersToUpload) = projectFileService.collectFilesAndFolders(
                    initialFilesToUpload,
                    initialIsProjectUpload
                )

                val allItemsToUpload = filesToUpload.toSet() + foldersToUpload

                fileToTargetPath = projectFileService.createVirtualFileToTargetPathMap(
                    filesToUpload.toSet(),
                    targetDestination,
                    relativeToFolders
                )

                fileToTargetPath.forEach { (file, _) ->
                    file.putSnapshot(
                        CachedSnapshot(
                            file.contentsToByteArray(),
                            file.length,
                            file.crc32
                        )
                    )
                }

                folderToTargetPath = projectFileService.createVirtualFileToTargetPathMap(
                    foldersToUpload,
                    targetDestination,
                    relativeToFolders
                )

                reporter.text(MpyBundle.message("upload.progress.analyzing.and.preparing"))
                val freeMemBytes = if (deviceService.deviceInformation.hasCRC32) {
                    deviceService.fileSystemWidget?.quietHashingRefresh(reporter)
                } else {
                    deviceService.fileSystemWidget?.quietRefresh(reporter)
                } ?: deviceService.deviceInformation.defaultFreeMem

                // Traverse and collect all file system nodes
                val allNodes = deviceService.fileSystemWidget?.allNodes() ?: emptyList()

                val volumeRootPaths = allNodes
                    .filterIsInstance<VolumeRootNode>()
                    .map { it.fullName }
                    .toSet()

                // Map target paths to file system nodes
                val targetPathToNode = mutableMapOf<String, FileSystemNode>()
                allNodes.forEach { node ->
                    targetPathToNode[node.fullName] = node
                }

                // Map all existing target paths
                val targetPathsToRemove = if (shouldSynchronize) {
                    allNodes
                        .map { it.fullName }
                        .toMutableSet()
                } else {
                    mutableSetOf()
                }

                // Iterate over files that are being uploaded
                // exempt them from synchronization
                // and remove those that are already uploaded
                val alreadyUploadedFiles = mutableSetOf<VirtualFile>()
                fileToTargetPath.keys.forEach { file ->
                    val path = fileToTargetPath[file]

                    val cachedSnapshot = file.getSnapshot()
                    val size = cachedSnapshot.length
                    val hash = cachedSnapshot.crc32

                    val matchingNode = targetPathToNode[path]

                    if (matchingNode != null) {
                        if (matchingNode is FileNode) {
                            if (size == matchingNode.size && hash == matchingNode.crc32) {
                                // If binascii is missing the hash is "0"
                                if (matchingNode.crc32 != "0") {
                                    // Remove already uploaded files
                                    alreadyUploadedFiles.add(file)
                                }
                            }
                        }
                        // This target path is being uploaded, it shouldn't be deleted by synchronization
                        targetPathsToRemove.remove(matchingNode.fullName)
                    }
                }

                // Iterate over folders that are being uploaded and exempt them from synchronization
                val alreadyExistingFolders = mutableSetOf<VirtualFile>()
                folderToTargetPath.keys.forEach { folder ->
                    val path = folderToTargetPath[folder]

                    val matchingNode = targetPathToNode[path]

                    if (matchingNode != null) {
                        alreadyExistingFolders.add(folder)
                        targetPathsToRemove.remove(matchingNode.fullName)
                    }
                }

                // Remove already existing file system entries
                fileToTargetPath.keys.removeAll(alreadyUploadedFiles)
                folderToTargetPath.keys.removeAll(alreadyExistingFolders)

                // Remove explicitly excluded paths
                if (shouldSynchronize && shouldExcludePaths && pathsToExclude.isNotEmpty()) {
                    val additionalPathsToRemove = targetPathsToRemove.filter { targetPath ->
                        pathsToExclude.any { pathToExclude ->
                            targetPath.startsWith(pathToExclude)
                        }
                    }

                    pathsToExclude.addAll(additionalPathsToRemove)

                    targetPathsToRemove.removeAll(pathsToExclude)
                }

                // Ensure volume root nodes won't be erased
                targetPathsToRemove.removeAll(volumeRootPaths)

                if (fileToTargetPath.isEmpty() && folderToTargetPath.isEmpty() && targetPathsToRemove.isEmpty()) {
                    Notifications.Bus.notify(
                        Notification(
                            MpyBundle.message("notification.group.name"),
                            MpyBundle.message("upload.notification.up.to.date"),
                            NotificationType.INFORMATION
                        ), project
                    )

                    uploadedSuccessfully = true
                    deviceService.state = State.CONNECTED
                    deviceService.writeOffTtyBufferToTerminal()
                    return@performReplAction PerformReplActionResult(null, false)
                }

                val proService = project.service<MpyProServiceInterface>()

                val nominalTotalSize = fileToTargetPath.keys.sumOf { it.length }.toDouble()

                val compressedTotalSize =
                    if (proService.isActive && settings.state.compressUploads) proService.getCompressUploadTotalSize(
                        fileToTargetPath
                    ) else null

                val totalSize = compressedTotalSize ?: nominalTotalSize

                if (settings.state.showUploadPreviewDialog) {
                    val shouldContinue = withContext(Dispatchers.EDT) {
                        val uploadPreview = MpyUploadPreview(
                            project,
                            shouldSynchronize,
                            shouldExcludePaths,
                            allItemsToUpload,
                            pathsToExclude,
                            targetPathsToRemove,
                            fileToTargetPath,
                            folderToTargetPath,
                            customPathFolders,
                            nominalTotalSize,
                            compressedTotalSize
                        )

                        return@withContext uploadPreview.showAndGet()
                    }

                    if (!shouldContinue) {
                        deviceService.state = State.CONNECTED
                        return@performReplAction PerformReplActionResult(null, false)
                    }
                }

                // A file system refresh should happen on cancellation now
                @Suppress("AssignedValueIsNeverRead") // it is used
                startedUploading = true

                // Perform synchronization
                if (shouldSynchronize && targetPathsToRemove.isNotEmpty()) {
                    reporter.text(MpyBundle.message("upload.progress.synchronizing"))

                    // Delete remaining existing target paths that aren't a part of the upload
                    deviceService.recursivelySafeDeletePaths(targetPathsToRemove)
                }

                var uploadProgress = 0.0
                var uploadedKB = 0.0
                var uploadedFiles = 1

                fun progressCallbackHandler(uploadedBytes: Double) {
                    // Floating point arithmetic can be inaccurate,
                    // ensures the uploaded size won't go over the actual file size
                    uploadedKB += (uploadedBytes / 1000).coerceIn((uploadedBytes / 1000), totalSize / 1000)
                    // Convert to double for maximal accuracy
                    uploadProgress += (uploadedBytes / totalSize)
                    // Ensure that uploadProgress never goes over 1.0
                    // as floating point arithmetic can have minor inaccuracies
                    uploadProgress = uploadProgress.coerceIn(0.0, 1.0)

                    reporter.text(
                        MpyBundle.message(
                            "upload.progress.uploading",
                            uploadedFiles,
                            fileToTargetPath.size,
                            "%.2f".format(uploadedKB),
                            "%.2f".format(totalSize / 1000)
                        )
                    )
                    reporter.fraction(uploadProgress)
                }

                fileToTargetPath.forEach { (file, path) ->
                    reporter.details(path)

                    deviceService.upload(
                        path,
                        file.getSnapshot().content,
                        ::progressCallbackHandler,
                        freeMemBytes
                    )

                    uploadedFiles++
                    checkCanceled()
                }

                reporter.details(null)

                // The upload methods handle creating parent files internally,
                // however, this is necessary to ensure that empty folders get created too
                if (folderToTargetPath.isNotEmpty()) {
                    reporter.text(MpyBundle.message("upload.progress.creating.directories"))
                    deviceService.safeCreateDirectories(folderToTargetPath.values.toSet())
                }

                uploadedSuccessfully = true
            },
            cleanUpAction = { reporter ->
                if (startedUploading) deviceService.fileSystemWidget?.refresh(reporter)
            },
            finalCheckAction = {
                if (uploadedSuccessfully) {
                    val allNodes = deviceService.fileSystemWidget?.allNodes() ?: emptyList()

                    for (node in allNodes) {
                        if (node is FileNode) {
                            val file =
                                fileToTargetPath.entries.firstOrNull { it.value == node.fullName }?.key ?: continue
                            if (file.getSnapshot().length != node.size) continue

                            fileToTargetPath.values.remove(node.fullName)
                        }

                        if (node is DirNode) {
                            folderToTargetPath.entries.firstOrNull { it.value == node.fullName }?.key ?: continue

                            folderToTargetPath.values.remove(node.fullName)
                        }
                    }

                    if (!fileToTargetPath.isEmpty() || !folderToTargetPath.isEmpty()) {
                        Notifications.Bus.notify(
                            Notification(
                                MpyBundle.message("notification.group.name"),
                                MpyBundle.message("upload.notification.verification.failed"),
                                NotificationType.WARNING
                            ), project
                        )
                    }
                }
            }
        )
        return uploadedSuccessfully
    }

    fun downloadDeviceFiles() {
        deviceService.performReplAction(
            project = project,
            connectionRequired = true,
            requiresRefreshAfter = false,
            canRunInBackground = true,
            description = MpyBundle.message("download.operation.description"),
            cancelledMessage = MpyBundle.message("download.operation.cancelled"),
            timedOutMessage = MpyBundle.message("download.operation.timeout"),
            action = { reporter ->
                reporter.text(MpyBundle.message("download.operation.collecting.files"))
                reporter.fraction(null)

                val deviceService = project.service<MpyDeviceService>()

                val selectedFiles = deviceService.fileSystemWidget?.selectedFiles()
                if (selectedFiles.isNullOrEmpty()) return@performReplAction
                var destination: VirtualFile? = null

                val projectDir = project.guessProjectDir()

                withContext(Dispatchers.EDT) {
                    FileChooserFactory.getInstance().createPathChooser(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null
                    ).choose(projectDir) { folders ->
                        destination = folders.firstOrNull()
                        if (destination?.children?.isNotEmpty() == true) {
                            if (Messages.showOkCancelDialog(
                                    project,
                                    "${MpyBundle.message("download.warning.destination.folder.not.empty.message.line.one")}\n${
                                        MpyBundle.message(
                                            "download.warning.destination.folder.not.empty.message.line.two"
                                        )
                                    }",
                                    MpyBundle.message("download.warning.destination.folder.not.empty.title"),
                                    MpyBundle.message("download.warning.destination.folder.not.empty.ok.text"),
                                    MpyBundle.message("download.warning.destination.folder.not.empty.cancel.text"),
                                    Messages.getWarningIcon()
                                ) != Messages.OK
                            ) {
                                destination = null
                            }
                        }
                    }
                }

                if (destination == null) return@performReplAction
                val parentNameToFile = selectedFiles.map { node -> "" to node }.toMutableList()
                var listIndex = 0

                while (listIndex < parentNameToFile.size) {
                    val (nodeParentName, node) = parentNameToFile[listIndex]
                    node.children().asSequence().forEach { child ->
                        child as FileSystemNode
                        val parentName = when {
                            nodeParentName.isEmpty() && node.isRoot -> ""
                            nodeParentName.isEmpty() -> node.name
                            nodeParentName == "/" -> node.name
                            else -> "$nodeParentName/${node.name}"
                        }

                        parentNameToFile.add(parentName to child)
                    }
                    if (node.isRoot) {
                        parentNameToFile.removeAt(listIndex)
                    } else {
                        listIndex++
                    }
                }

                val singleFileProgress: Double = (1 / parentNameToFile.size.toDouble())
                var downloadedFiles = 1

                parentNameToFile.forEach { (parentName, node) ->
                    val name = if (parentName.isEmpty()) node.name else "$parentName/${node.name}"

                    reporter.text(
                        MpyBundle.message(
                            "download.progress.downloading",
                            downloadedFiles,
                            parentNameToFile.size
                        )
                    )
                    reporter.fraction(downloadedFiles.toDouble() * singleFileProgress)
                    reporter.details(name)

                    val content = if (node is FileNode) {
                        deviceService.download(node.fullName)
                    } else null

                    try {
                        if (node is FileNode) {
                            writeAction {
                                try {
                                    destination!!.findOrCreateFile(name).setBinaryContent(content!!)
                                } catch (e: Throwable) {
                                    throw IOException(
                                        MpyBundle.message(
                                            "download.error.writing.files",
                                            e.localizedMessage
                                        )
                                    )
                                }
                            }
                        } else {
                            writeAction {
                                try {
                                    destination!!.findOrCreateDirectory(name)
                                } catch (e: Throwable) {
                                    throw IOException(
                                        MpyBundle.message(
                                            "download.error.writing.files",
                                            e.localizedMessage
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        throw IOException(MpyBundle.message("download.error.writing.files", e.localizedMessage))
                    }

                    downloadedFiles++
                }
            }
        )
    }
}