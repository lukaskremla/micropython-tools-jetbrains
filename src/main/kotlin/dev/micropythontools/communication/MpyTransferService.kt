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
import dev.micropythontools.ui.DirNode
import dev.micropythontools.ui.FileNode
import dev.micropythontools.ui.FileSystemNode
import dev.micropythontools.ui.NOTIFICATION_GROUP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException


@Service(Service.Level.PROJECT)
/**
 * @author Lukas Kremla, elmot
 */
class MpyTransferService(private val project: Project) {
    private fun VirtualFile.leadingDot() = this.name.startsWith(".")

    val VirtualFile.crc32: String
        get() {
            val localFileBytes = this.contentsToByteArray()
            val crc = java.util.zip.CRC32()
            crc.update(localFileBytes)
            return "%08x".format(crc.value)
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

    private fun collectTestRoots(): Set<VirtualFile> {
        return project.modules.flatMap { module ->
            module.rootManager.contentEntries
                .flatMap { entry -> entry.sourceFolders.toList() }
                .filter { sourceFolder -> sourceFolder.isTestSource }
                .mapNotNull { it.file }
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
    ): Boolean {

        FileDocumentManager.getInstance().saveAllDocuments()

        val settings = project.service<MpySettingsService>()
        val deviceService = project.service<MpyDeviceService>()

        val excludedFolders = collectExcluded()
        val sourceFolders = collectMpySourceRoots()
        val testFolders = collectTestRoots()
        val projectDir = project.guessProjectDir() ?: return false

        var isProjectUpload = initialIsProjectUpload

        var filesToUpload = if (initialIsProjectUpload) collectProjectUploadables().toMutableList() else initialFilesToUpload.toMutableList()
        var foldersToCreate = mutableSetOf<VirtualFile>()

        var ftpUploadClient: MpyFTPClient? = null
        var shouldCleanUpFTP = false
        var hasUftpdCached: Boolean? = null

        var uploadedSuccessfully = false

        // Define the initially collected maps here to allow final verification in the clean-up action
        var fileToTargetPath = mutableMapOf<VirtualFile, String>()
        var folderToTargetPath = mutableMapOf<VirtualFile, String>()

        performReplAction(
            project = project,
            connectionRequired = true,
            description = "Upload",
            requiresRefreshAfter = false,
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
                            FileTypeRegistry.getInstance().isFileIgnored(file) ||
                            excludedFolders.any { VfsUtil.isAncestor(it, file, true) } ||
                            // Test folders are only uploaded if explicitly selected or if a parent folder was explicitly selected
                            ((isProjectUpload || !initialFilesToUpload.any { VfsUtil.isAncestor(it, file, true) }) &&
                                    testFolders.any { VfsUtil.isAncestor(it, file, true) }) ||
                            (isProjectUpload && sourceFolders.isNotEmpty() &&
                                    !sourceFolders.any { VfsUtil.isAncestor(it, file, false) })

                    when {
                        shouldSkip -> {
                            filesToUpload.removeAt(i)
                        }

                        file == projectDir -> {
                            i = 0
                            filesToUpload.clear()
                            filesToUpload = collectProjectUploadables().toMutableList()
                            isProjectUpload = true
                        }

                        file.isDirectory -> {
                            filesToUpload.addAll(file.children)
                            filesToUpload.removeAt(i)

                            foldersToCreate.add(file)
                        }

                        else -> i++
                    }
                }

                fileToTargetPath = createVirtualFileToTargetPathMap(
                    filesToUpload.toSet(),
                    targetDestination,
                    relativeToFolders,
                    sourceFolders,
                    projectDir
                )

                folderToTargetPath = createVirtualFileToTargetPathMap(
                    foldersToCreate,
                    targetDestination,
                    relativeToFolders,
                    sourceFolders,
                    projectDir
                )

                if (deviceService.deviceInformation.hasCRC32) {
                    reporter.text(if (shouldSynchronize) "Syncing and skipping already uploaded files..." else "Detecting already uploaded files...")
                    deviceService.fileSystemWidget?.quietHashingRefresh(reporter)
                } else if (shouldSynchronize) {
                    reporter.text("Synchronizing...")
                    deviceService.fileSystemWidget?.quietRefresh(reporter)
                }

                if (shouldSynchronize || deviceService.deviceInformation.hasCRC32) {
                    // Traverse and collect all file system nodes
                    val allNodes = deviceService.fileSystemWidget?.allNodes() ?: emptyList()

                    // Map target paths to file system nodes
                    val targetPathToNode = mutableMapOf<String, FileSystemNode>()
                    allNodes.forEach { node ->
                        targetPathToNode[node.fullName] = node
                    }

                    // Map all existing target paths
                    val targetPathsToRemove = allNodes
                        .map { it.fullName }
                        .toMutableSet()

                    // Iterate over files that are being uploaded
                    // exempt them from synchronization
                    // and remove those that are already uploaded
                    val alreadyUploadedFiles = mutableSetOf<VirtualFile>()
                    fileToTargetPath.keys.forEach { file ->
                        val path = fileToTargetPath[file]
                        val size = file.length.toInt()
                        val hash = file.crc32

                        val matchingNode = targetPathToNode[path]

                        if (matchingNode != null) {
                            if (matchingNode is FileNode) {
                                if (size == matchingNode.size && hash == matchingNode.hash) {
                                    // If binascii is missing the hash is "0"
                                    if (matchingNode.hash != "0") {
                                        // Remove already uploaded files
                                        alreadyUploadedFiles.add(file)
                                    }
                                }
                            }
                            // This target path is being uploaded, it shouldn't be deleted by synchronization
                            targetPathsToRemove.remove(matchingNode.fullName)
                        }
                    }

                    // Iterate over files that are being uploaded
                    // exempt them from synchronization
                    val alreadyExistingFolders = mutableSetOf<VirtualFile>()
                    folderToTargetPath.keys.forEach { folder ->
                        val path = folderToTargetPath[folder]

                        val matchingNode = targetPathToNode[path]

                        if (matchingNode != null) {
                            alreadyExistingFolders.add(folder)
                            targetPathsToRemove.remove(matchingNode.fullName)
                        }
                    }

                    val cachedFtpScriptPath = "${settings.state.cachedFTPScriptPath ?: ""}/uftpd.py"

                    // Check if uftpd is cached
                    if (fileToTargetPath.values.any { it == cachedFtpScriptPath }) {
                        hasUftpdCached = true
                    }

                    // Remove already existing file system entries
                    fileToTargetPath.keys.removeAll(alreadyUploadedFiles)
                    folderToTargetPath.keys.removeAll(alreadyExistingFolders)

                    if (shouldSynchronize) {
                        // Remove explicitly excluded paths
                        if (shouldExcludePaths && excludedPaths.isNotEmpty()) {
                            targetPathsToRemove.removeAll(excludedPaths)
                        }

                        // Special handling for cached FTP scripts
                        if (settings.state.useFTP && settings.state.cacheFTPScript) {
                            targetPathsToRemove.remove(cachedFtpScriptPath)
                        }

                        // Delete remaining existing target paths that aren't a part of the upload
                        deviceService.recursivelySafeDeletePaths(targetPathsToRemove)
                    }
                }

                // Create all directories
                if (folderToTargetPath.isNotEmpty()) {
                    reporter.text("Creating directories...")
                    deviceService.safeCreateDirectories(folderToTargetPath.values.toSet())
                }

                val totalBytes = fileToTargetPath.keys.sumOf { it.length }.toDouble()

                if (shouldUseFTP(project, totalBytes) && fileToTargetPath.isNotEmpty()) {
                    shouldCleanUpFTP = true

                    try {
                        ftpUploadClient = MpyFTPClient(project)
                        val ip = ftpUploadClient.setupFtpServer(reporter, hasUftpdCached)
                        ftpUploadClient.connect(ip, "", "")
                    } catch (e: Throwable) {
                        Notifications.Bus.notify(
                            Notification(
                                NOTIFICATION_GROUP,
                                e.localizedMessage,
                                NotificationType.ERROR
                            ), project
                        )
                    }
                }

                var uploadProgress = 0.0
                var uploadedKB = 0.0
                var uploadedFiles = 1

                fun progressCallbackHandler(uploadedBytes: Int) {
                    uploadedKB += (uploadedBytes.toDouble() / 1000)
                    // Convert to double for maximal accuracy
                    uploadProgress += (uploadedBytes.toDouble() / totalBytes.toDouble())
                    // Ensure that uploadProgress never goes over 1.0
                    // as floating point arithmetic can have minor inaccuracies
                    uploadProgress = uploadProgress.coerceIn(0.0, 1.0)
                    reporter.text("Uploading: file $uploadedFiles of ${fileToTargetPath.size} | ${"%.2f".format(uploadedKB)} KB of ${"%.2f".format(totalBytes / 1000)} KB")
                    reporter.fraction(uploadProgress)
                }

                fileToTargetPath.forEach { (file, path) ->
                    reporter.details(path)

                    if (ftpUploadClient != null && ftpUploadClient.isConnected) {
                        try {
                            withTimeout(10_000) {
                                ftpUploadClient.uploadFile(path, file.contentsToByteArray(), ::progressCallbackHandler)
                            }
                        } catch (_: TimeoutCancellationException) {
                            throw IOException("Timed out while uploading a file with FTP")
                        }
                    } else {
                        deviceService.upload(path, file.contentsToByteArray(), ::progressCallbackHandler)
                    }

                    uploadedFiles++
                    checkCanceled()
                }

                uploadedSuccessfully = true
            },

            cleanUpAction = { reporter ->
                // Reporter text and fraction parameters are always set when the reporter is used elsewhere,
                // but details aren't thus they should be cleaned up
                reporter.details(null)

                if (shouldCleanUpFTP) {
                    reporter.text("Cleaning up after FTP upload...")
                    reporter.fraction(null)
                    ftpUploadClient?.teardownFtpServer()
                }

                deviceService.fileSystemWidget?.refresh(reporter)

                if (uploadedSuccessfully) {
                    val allNodes = deviceService.fileSystemWidget?.allNodes() ?: emptyList()

                    for (node in allNodes) {
                        if (node is FileNode) {
                            val file = fileToTargetPath.entries.firstOrNull { it.value == node.fullName }?.key ?: continue
                            if (file.length != node.size.toLong()) continue

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

                withContext(Dispatchers.EDT) {
                    FileChooserFactory.getInstance().createPathChooser(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null
                    ).choose(null) { folders ->
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

                    val content = if (node is FileNode) deviceService.download(node.fullName) else null

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