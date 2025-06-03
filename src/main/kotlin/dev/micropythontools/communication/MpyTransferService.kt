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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.modules
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findOrCreateDirectory
import com.intellij.openapi.vfs.findOrCreateFile
import com.intellij.project.stateStore
import com.jetbrains.python.sdk.PythonSdkUtil
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.sourceroots.MpySourceRootType
import dev.micropythontools.ui.*
import jssc.SerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException


/**
 * @author Lukas Kremla, elmot
 */
@Service(Service.Level.PROJECT)
internal class MpyTransferService(private val project: Project) {
    private fun VirtualFile.leadingDot() = this.name.startsWith(".")

    val VirtualFile.crc32: String
        get() {
            val localFileBytes = this.contentsToByteArray()
            val crc = java.util.zip.CRC32()
            crc.update(localFileBytes)
            return "%08x".format(crc.value)
        }

    fun collectExcluded(): Set<VirtualFile> {
        val ideaDir = project.stateStore.directoryStorePath?.let { VfsUtil.findFile(it, false) }
        val excludes = if (ideaDir == null) mutableSetOf() else mutableSetOf(ideaDir)
        project.modules.forEach { module ->
            PythonSdkUtil.findPythonSdk(module)?.homeDirectory?.apply { excludes.add(this) }
            module.rootManager.contentEntries.forEach { entry ->
                excludes.addAll(entry.excludeFolderFiles)
            }
        }
        return excludes
    }

    fun collectMpySourceRoots(): Set<VirtualFile> {
        return project.modules.flatMap { module ->
            module.rootManager.contentEntries
                .flatMap { entry -> entry.getSourceFolders(MpySourceRootType.SOURCE).toList() }
                .filter { mpySourceFolder ->
                    mpySourceFolder.file?.let { !it.leadingDot() } == true
                }
                .mapNotNull { it.file }
        }.toSet()
    }

    fun collectTestRoots(): Set<VirtualFile> {
        return project.modules.flatMap { module ->
            module.rootManager.contentEntries
                .flatMap { entry -> entry.sourceFolders.toList() }
                .filter { sourceFolder -> sourceFolder.isTestSource }
                .mapNotNull { it.file }
        }.toSet()
    }

    private fun collectProjectUploadables(): Set<VirtualFile> {
        return project.modules.flatMap { module ->
            module.rootManager.contentEntries
                .mapNotNull { it.file }
                .flatMap { it.children.toList() }
                .filter { !it.leadingDot() }
                .toMutableList()
        }.toSet()
    }

    private fun createVirtualFileToTargetPathMap(
        files: Set<VirtualFile>,
        targetDestination: String? = "/",
        relativeToFolders: Set<VirtualFile>?,
        sourceFolders: Set<VirtualFile>,
        projectDir: VirtualFile
    ): MutableMap<VirtualFile, String> {
        val normalizedTarget = targetDestination?.trim('/') ?: ""

        return files.associateWithTo(mutableMapOf()) { file ->
            val baseFolder = when {
                // relativeToFolders have priority
                !relativeToFolders.isNullOrEmpty() -> relativeToFolders.firstOrNull {
                    VfsUtil.isAncestor(it, file, false)
                }

                else -> sourceFolders.firstOrNull {
                    VfsUtil.isAncestor(it, file, false)
                }
            } ?: projectDir

            val relativePath = VfsUtil.getRelativePath(file, baseFolder) ?: file.name

            // Combine and normalize path
            val combinedPath = "$normalizedTarget/$relativePath".trim('/')

            // Ensure single leading slash
            "/$combinedPath"
        }
    }

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

        FileDocumentManager.getInstance().saveAllDocuments()

        val settings = project.service<MpySettingsService>()
        val deviceService = project.service<MpyDeviceService>()

        val excludedFolders = collectExcluded()
        val sourceFolders = collectMpySourceRoots()
        val testFolders = collectTestRoots()
        val projectDir = project.guessProjectDir() ?: return false

        var isProjectUpload = initialIsProjectUpload

        var filesToUpload =
            if (initialIsProjectUpload) collectProjectUploadables().toMutableList() else initialFilesToUpload.toMutableList()
        val foldersToUpload = mutableSetOf<VirtualFile>()

        val pathsToExclude = excludedPaths.toMutableSet()

        var uploadedSuccessfully = false

        // Define the initially collected maps here to allow final verification in the clean-up action
        var fileToTargetPath = mutableMapOf<VirtualFile, String>()
        var folderToTargetPath = mutableMapOf<VirtualFile, String>()

        performReplAction(
            project = project,
            connectionRequired = true,
            description = "Upload",
            requiresRefreshAfter = true,
            action = { reporter ->
                reporter.text("collecting files and creating directories...")
                reporter.fraction(null)

                var i = 0
                while (i < filesToUpload.size) {
                    val file = filesToUpload[i]

                    val shouldSkip = !file.isValid ||
                            // Only skip leading dot files unless it's a project dir
                            // or unless it's in the initial list, which means the user explicitly selected it
                            (file.leadingDot() && file != projectDir && !initialFilesToUpload.contains(file)) ||
                            // Skip files explicitly ignored by the IDE
                            FileTypeRegistry.getInstance().isFileIgnored(file) ||
                            // All excluded folders and their children are always meant to be skipped
                            excludedFolders.any { VfsUtil.isAncestor(it, file, false) } ||
                            // Skip test folders and their children if it's a project upload or if they weren't explicitly selected by the user
                            (testFolders.any { VfsUtil.isAncestor(it, file, false) } &&
                                    (isProjectUpload || !initialFilesToUpload.any {
                                        VfsUtil.isAncestor(
                                            it,
                                            file,
                                            false
                                        )
                                    })) ||
                            // For project uploads, if at least one MicroPython Sources Root was selected
                            // then only contents of MicroPython Sources Roots are to be uploaded
                            (isProjectUpload && sourceFolders.isNotEmpty() &&
                                    !sourceFolders.any { VfsUtil.isAncestor(it, file, false) })

                    when {
                        shouldSkip -> {
                            filesToUpload.removeAt(i)
                        }

                        file == projectDir -> {
                            // If a project root is found start over and treat this as a project upload
                            i = 0
                            filesToUpload.clear()
                            filesToUpload = collectProjectUploadables().toMutableList()
                            isProjectUpload = true
                        }

                        file.isDirectory -> {
                            filesToUpload.addAll(file.children)
                            filesToUpload.removeAt(i)

                            foldersToUpload.add(file)
                        }

                        else -> i++
                    }
                }

                val allItemsToUpload = filesToUpload.toSet() + foldersToUpload

                fileToTargetPath = createVirtualFileToTargetPathMap(
                    filesToUpload.toSet(),
                    targetDestination,
                    relativeToFolders,
                    sourceFolders,
                    projectDir
                )

                folderToTargetPath = createVirtualFileToTargetPathMap(
                    foldersToUpload,
                    targetDestination,
                    relativeToFolders,
                    sourceFolders,
                    projectDir
                )

                if (settings.state.usingUart && settings.state.increaseBaudrateForFileTransfers == "true") {
                    reporter.text("Increasing serial connection baudrate...")

                    deviceService.setBaudrate(
                        settings.state.increasedFileTransferBaudrate?.toIntOrNull() ?: SerialPort.BAUDRATE_115200
                    )
                }

                reporter.text("Analyzing device files and preparing upload...")
                if (deviceService.deviceInformation.hasCRC32) {
                    deviceService.fileSystemWidget?.quietHashingRefresh(reporter)
                } else {
                    deviceService.fileSystemWidget?.quietRefresh(reporter)
                }

                // Traverse and collect all file system nodes
                val allNodes = deviceService.fileSystemWidget?.allNodes() ?: emptyList()

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
                    val size = file.length
                    val hash = file.crc32

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

                if (fileToTargetPath.isEmpty() && folderToTargetPath.isEmpty() && targetPathsToRemove.isEmpty()) {
                    Notifications.Bus.notify(
                        Notification(
                            NOTIFICATION_GROUP,
                            "All files are up to date",
                            NotificationType.INFORMATION
                        ), project
                    )

                    uploadedSuccessfully = true
                    deviceService.state = State.CONNECTED
                    deviceService.writeOffTtyBufferToTerminal()
                    return@performReplAction PerformReplActionResult(null, false)
                }

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
                            customPathFolders
                        )

                        return@withContext uploadPreview.showAndGet()
                    }

                    if (!shouldContinue) {
                        return@performReplAction PerformReplActionResult(null, false)
                    }
                }

                // Perform synchronization
                if (shouldSynchronize && targetPathsToRemove.isNotEmpty()) {
                    reporter.text("Performing synchronization...")

                    // Delete remaining existing target paths that aren't a part of the upload
                    deviceService.recursivelySafeDeletePaths(targetPathsToRemove)
                }

                var uploadProgress = 0.0
                var uploadedKB = 0.0
                var uploadedFiles = 1

                // Calculate the total binary size of the upload
                val totalBytes = fileToTargetPath.keys.sumOf { it.length }.toDouble()

                fun progressCallbackHandler(uploadedBytes: Double) {
                    // Floating point arithmetic can be inaccurate,
                    // ensures the uploaded size won't go over the actual file size
                    uploadedKB += (uploadedBytes / 1000).coerceIn((uploadedBytes / 1000), totalBytes / 1000)
                    // Convert to double for maximal accuracy
                    uploadProgress += (uploadedBytes / totalBytes)
                    // Ensure that uploadProgress never goes over 1.0
                    // as floating point arithmetic can have minor inaccuracies
                    uploadProgress = uploadProgress.coerceIn(0.0, 1.0)

                    reporter.text(
                        "Uploading: file $uploadedFiles of ${fileToTargetPath.size} | ${
                            "%.2f".format(
                                uploadedKB
                            )
                        } KB of ${"%.2f".format(totalBytes / 1000)} KB"
                    )
                    reporter.fraction(uploadProgress)
                }

                fileToTargetPath.forEach { (file, path) ->
                    reporter.details(path)

                    deviceService.upload(
                        path,
                        file.contentsToByteArray(),
                        ::progressCallbackHandler
                    )

                    uploadedFiles++
                    checkCanceled()
                }

                reporter.details(null)

                // The upload methods handle creating parent files internally,
                // however, this is necessary to ensure that empty folders get created too
                if (folderToTargetPath.isNotEmpty()) {
                    reporter.text("Creating directories...")
                    deviceService.safeCreateDirectories(folderToTargetPath.values.toSet())
                }

                uploadedSuccessfully = true
            },
            cleanUpAction = { reporter ->
                withContext(NonCancellable) {
                    if (settings.state.usingUart && settings.state.increaseBaudrateForFileTransfers == "true") {
                        reporter.text("Restoring the original serial connection baudrate...")

                        deviceService.setBaudrate(SerialPort.BAUDRATE_115200)
                    }
                }
            },
            finalCheckAction = {
                if (uploadedSuccessfully) {
                    val allNodes = deviceService.fileSystemWidget?.allNodes() ?: emptyList()

                    for (node in allNodes) {
                        if (node is FileNode) {
                            val file =
                                fileToTargetPath.entries.firstOrNull { it.value == node.fullName }?.key ?: continue
                            if (file.length != node.size) continue

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
                                NOTIFICATION_GROUP,
                                "Uploaded files don't match with the expected sizes. Please try to re-run the upload.",
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
        performReplAction(
            project = project,
            connectionRequired = true,
            description = "Download",
            requiresRefreshAfter = false,
            action = { reporter ->
                reporter.text("Collecting files to download...")
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
                                    "The destination folder is not empty.\nIts contents may be damaged.",
                                    "Warning",
                                    "Continue",
                                    "Cancel",
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

                    reporter.text("Downloading: file $downloadedFiles of ${parentNameToFile.size}")
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
                                    throw IOException("Error writing files - ${e.localizedMessage}")
                                }
                            }
                        } else {
                            writeAction {
                                try {
                                    destination!!.findOrCreateDirectory(name)
                                } catch (e: Throwable) {
                                    throw IOException("Error writing files - ${e.localizedMessage}")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        throw IOException("Error writing files - ${e.localizedMessage}")
                    }

                    downloadedFiles++
                }
            }
        )
    }
}