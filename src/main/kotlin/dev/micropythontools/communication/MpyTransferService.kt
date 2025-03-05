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

import com.fazecast.jSerialComm.SerialPort
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
import com.intellij.util.containers.TreeTraversal
import com.intellij.util.ui.tree.TreeUtil
import com.jetbrains.python.sdk.PythonSdkUtil
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.*
import dev.micropythontools.util.MpyPythonService
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
    fun listSerialPorts(filterManufacturers: Boolean = project.service<MpySettingsService>().state.filterManufacturers): MutableList<String> {
        val filteredPorts = mutableListOf<String>()

        val ports = SerialPort.getCommPorts()
        for (port in ports) {
            if ((filterManufacturers && port.manufacturer == "Unknown") ||
                port.systemPortPath.startsWith("/dev/tty.")
            ) continue

            filteredPorts.add(port.systemPortPath)
        }

        return filteredPorts
    }

    suspend fun recursivelyDeletePaths(paths: List<String>) {
        val commands = mutableListOf(
            "import os, gc",
            "def ___d(p):",
            "   try:",
            "       os.stat(p)",
            "   except:",
            "       return",
            "   try:",
            "       os.remove(p)",
            "       return",
            "   except:",
            "       pass",
            "   for r in os.ilistdir(p):",
            "       f = f'{p}/{r[0]}' if p != '/' else f'/{r[0]}'",
            "       if r[1] & 0x4000:",
            "           ___d(f)",
            "       else:",
            "           os.remove(f)",
            "   try:",
            "       os.rmdir(p)",
            "   except:",
            "       pass"
        )

        paths.forEach {
            commands.add("___d('${it}')")
        }

        commands.add("del ___d")
        commands.add("gc.collect()")

        fileSystemWidget(project)?.blindExecute(LONG_TIMEOUT, *commands.toTypedArray())?.extractResponse()
    }

    private fun calculateCRC32(file: VirtualFile): String {
        val localFileBytes = file.contentsToByteArray()
        val crc = java.util.zip.CRC32()
        crc.update(localFileBytes)
        return String.format("%08x", crc.value)
    }

    private fun VirtualFile.leadingDot() = this.name.startsWith(".")

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

    private fun collectSourceRoots(): Set<VirtualFile> {
        return project.modules.flatMap { module ->
            module.rootManager.contentEntries
                .flatMap { entry -> entry.sourceFolders.toList() }
                .filter { sourceFolder ->
                    !sourceFolder.isTestSource && sourceFolder.file?.let { !it.leadingDot() } == true
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
        excludedPaths: List<String> = emptyList(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false
    ): Boolean {

        FileDocumentManager.getInstance().saveAllDocuments()
        val filesToUpload = collectProjectUploadables()

        return performUpload(
            filesToUpload,
            null,
            null,
            excludedPaths,
            shouldSynchronize,
            shouldExcludePaths,
            true
        )
    }

    fun uploadFileOrFolder(
        toUpload: VirtualFile,
        excludedPaths: List<String> = emptyList(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false
    ): Boolean {

        return performUpload(
            setOf(toUpload),
            null,
            null,
            excludedPaths,
            shouldSynchronize,
            shouldExcludePaths,
            false
        )
    }

    fun uploadItems(
        toUpload: Set<VirtualFile>,
        excludedPaths: List<String> = emptyList(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false
    ): Boolean {

        return performUpload(
            toUpload,
            null,
            null,
            excludedPaths,
            shouldSynchronize,
            shouldExcludePaths,
            false
        )
    }

    fun performUpload(
        initialFilesToUpload: Set<VirtualFile>,
        targetDestination: String? = "/",
        relativeToFolders: Set<VirtualFile>? = null,
        excludedPaths: List<String> = emptyList(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false,
        initialIsProjectUpload: Boolean = false
    ): Boolean {

        FileDocumentManager.getInstance().saveAllDocuments()

        val settings = project.service<MpySettingsService>()
        val pythonService = project.service<MpyPythonService>()

        var filesToUpload = initialFilesToUpload.toMutableList()
        var foldersToCreate = mutableSetOf<VirtualFile>()

        val excludedFolders = collectExcluded()
        val sourceFolders = collectSourceRoots()
        val testFolders = collectTestRoots()
        val projectDir = project.guessProjectDir() ?: return false

        var isProjectUpload = initialIsProjectUpload

        var ftpUploadClient: MpyFTPClient? = null
        var uploadedSuccessfully = false

        performReplAction(
            project = project,
            connectionRequired = true,
            description = "Upload",
            requiresRefreshAfter = true,
            action = { fileSystemWidget, reporter ->
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
                            println()
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

                // Create a file to target path map
                val fileToTargetPath = createVirtualFileToTargetPathMap(
                    filesToUpload.toSet(),
                    targetDestination,
                    relativeToFolders,
                    sourceFolders,
                    projectDir
                )

                val folderToTargetPath = createVirtualFileToTargetPathMap(
                    foldersToCreate,
                    targetDestination,
                    relativeToFolders,
                    sourceFolders,
                    projectDir
                )

                // Collect the target paths of directories that must be created
                val sortedFolderTargetPaths = folderToTargetPath.values
                    .sortedBy { it.split("/").filter { it.isNotEmpty() }.size }
                    .toMutableList()

                if (fileSystemWidget.deviceInformation.hasBinascii) {
                    reporter.text(if (shouldSynchronize) "Syncing and skipping already uploaded files..." else "Detecting already uploaded files...")
                    fileSystemWidget.quietHashingRefresh(reporter)
                } else if (shouldSynchronize) {
                    reporter.text("Synchronizing...")
                    fileSystemWidget.refresh(reporter)
                }

                if (shouldSynchronize) {
                    val allNodes = mutableListOf<FileSystemNode>()
                    val root = fileSystemWidget.tree.model.root as DirNode
                    TreeUtil.treeNodeTraverser(root)
                        .traverse(TreeTraversal.POST_ORDER_DFS)
                        .mapNotNull {
                            when (val node = it) {
                                is DirNode -> node
                                is FileNode -> node
                                else -> null
                            }
                        }
                        .toCollection(allNodes)

                    val deviceFileMap = mutableMapOf<String, FileSystemNode>()
                    allNodes.forEach { node ->
                        deviceFileMap[node.fullName] = node
                    }

                    val commands = mutableListOf("import os")

                    val filePathsToRemove = allNodes
                        .filter { it is FileNode }
                        .map { it.fullName }
                        .toMutableSet()

                    val directoryPathsToRemove = allNodes
                        .filter { it is DirNode }
                        .map { it.fullName }
                        .toMutableSet()

                    val alreadyUploadedFiles = mutableSetOf<VirtualFile>()
                    fileToTargetPath.keys.forEach { file ->
                        val path = fileToTargetPath[file]
                        val size = file.length.toInt()
                        val hash = calculateCRC32(file)

                        val matchingNode = deviceFileMap[path]

                        if (matchingNode != null) {
                            if (matchingNode is FileNode) {
                                if (size == matchingNode.size && hash == matchingNode.hash) {
                                    // If binascii is missing the hash is "0"
                                    if (matchingNode.hash != "0") {
                                        alreadyUploadedFiles.add(file)
                                    }
                                }
                                filePathsToRemove.remove(matchingNode.fullName)
                            } else {
                                directoryPathsToRemove.remove(matchingNode.fullName)
                            }
                        }
                    }

                    filePathsToRemove.forEach {
                        if (!shouldExcludePaths || !excludedPaths.contains(it)) {
                            commands.add("os.remove('$it')")
                        }
                    }

                    directoryPathsToRemove.removeAll(sortedFolderTargetPaths)
                    directoryPathsToRemove.forEach {
                        if (!shouldExcludePaths || !excludedPaths.contains(it)) {
                            commands.add("os.rmdir('$it')")
                        }
                    }

                    fileSystemWidget.blindExecute(LONG_TIMEOUT, *commands.toTypedArray())
                    fileToTargetPath.keys.removeAll(alreadyUploadedFiles)
                }

                // Prepare the dir creation command
                val mkdirCommands = mutableListOf(
                    "import os, gc",
                    "def ___m(p):",
                    "   try:",
                    "       os.mkdir(p)",
                    "   except:",
                    "       pass"
                )
                sortedFolderTargetPaths.forEach {
                    mkdirCommands.add("___m('$it')")
                }
                mkdirCommands.add("del ___m")
                mkdirCommands.add("gc.collect()")

                // Create the directories
                fileSystemWidget.blindExecute(SHORT_TIMEOUT, *mkdirCommands.toTypedArray()).extractResponse()

                val totalBytes = fileToTargetPath.keys.sumOf { it.length }.toDouble()

                if (shouldUseFTP(project, totalBytes) && fileToTargetPath.isNotEmpty()) {
                    reporter.text("Retrieving FTP credentials...")

                    val wifiCredentials = settings.retrieveWifiCredentials()
                    val ssid = wifiCredentials.userName
                    val password = wifiCredentials.getPasswordAsString()

                    if (ssid.isNullOrBlank()) {
                        Notifications.Bus.notify(
                            Notification(
                                NOTIFICATION_GROUP,
                                "Cannot upload over FTP, no SSID was provided in settings! Falling back to REPL uploads.",
                                NotificationType.ERROR
                            ), project
                        )
                    } else {
                        val cachedFtpScriptImportPath = settings.state.cachedFTPScriptPath
                            ?.replace("/", ".")
                            ?.removePrefix("/")
                            ?.removeSuffix(".py")

                        if (settings.state.cacheFTPScript) {
                            reporter.text("Validating the cached FTP script...")

                            val commands = mutableListOf<String>(
                                "try:",
                                "   import $cachedFtpScriptImportPath",
                                "   print('valid')",
                                "except ImportError:",
                                "   pass"
                            )

                            val cacheValid = fileSystemWidget.blindExecute(SHORT_TIMEOUT, *commands.toTypedArray())
                                .extractSingleResponse() == "valid"

                            val uftpdPath = settings.state.cachedFTPScriptPath

                            if (!cacheValid && uftpdPath != null) {
                                val ftpFile = pythonService.retrieveMpyScriptAsVirtualFile("uftpd.py")
                                val ftpTotalBytes = ftpFile.length
                                var uploadedFTPKB = 0.0
                                var uploadFtpProgress = 0.0

                                fun ftpUploadProgressCallbackHandler(uploadedBytes: Int) {
                                    uploadedFTPKB += (uploadedBytes.toDouble() / 1000)
                                    // Convert to double for maximal accuracy
                                    uploadFtpProgress += (uploadedBytes.toDouble() / ftpTotalBytes.toDouble())
                                    // Ensure that uploadProgress never goes over 1.0
                                    // as floating point arithmetic can have minor inaccuracies
                                    uploadFtpProgress = uploadFtpProgress.coerceIn(0.0, 1.0)
                                    reporter.text("Uploading ftp script... | ${"%.2f".format(uploadedFTPKB)} KB of ${"%.2f".format(ftpTotalBytes / 1000)} KB")
                                    reporter.fraction(uploadFtpProgress)
                                }

                                fileSystemWidget.upload(uftpdPath, ftpFile.contentsToByteArray(), ::ftpUploadProgressCallbackHandler)
                            }
                        }

                        reporter.text("Establishing an FTP server connection...")

                        val cleanupScriptName = "ftp_cleanup.py"
                        val cleanupScript = pythonService.retrieveMpyScriptAsString(cleanupScriptName)
                        val commands = mutableListOf<String>(cleanupScript)

                        val wifiConnectScriptName = "connect_to_wifi.py"
                        val wifiConnectScript = pythonService.retrieveMpyScriptAsString(wifiConnectScriptName)
                        val formattedWifiConnectScript = wifiConnectScript.format(
                            """"$ssid"""",
                            """"$password"""",
                            20, // Wi-Fi connection timeout
                        )
                        commands.add(formattedWifiConnectScript)

                        if (settings.state.cacheFTPScript) {
                            commands.add("import cachedFtpScriptImportPath")
                            commands.add("start()")
                        } else {
                            val miniUftpdScript = pythonService.retrieveMpyScriptAsString("mini_uftpd.py")

                            commands.add(miniUftpdScript)
                            commands.add(
                                "___ftp().start()"
                            )
                        }

                        val scriptResponse = fileSystemWidget.blindExecute(LONG_TIMEOUT, *commands.toTypedArray())
                            .extractSingleResponse().trim()

                        if (scriptResponse.contains("ERROR") || !scriptResponse.startsWith("IP: ")) {
                            Notifications.Bus.notify(
                                Notification(
                                    NOTIFICATION_GROUP,
                                    "Ran into an error establishing an FTP connection, falling back to REPL uploads: $scriptResponse",
                                    NotificationType.ERROR
                                ), project
                            )
                        } else {
                            try {
                                val ip = scriptResponse.removePrefix("IP: ")

                                ftpUploadClient = MpyFTPClient()
                                ftpUploadClient.connect(ip, "", "") // No credentials are used
                            } catch (e: Exception) {
                                Notifications.Bus.notify(
                                    Notification(
                                        NOTIFICATION_GROUP,
                                        "Connecting to FTP server failed, falling back to REPL uploads: $e",
                                        NotificationType.ERROR
                                    ), project
                                )
                            }
                        }
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
                        fileSystemWidget.upload(path, file.contentsToByteArray(), ::progressCallbackHandler)
                    }

                    uploadedFiles++
                    checkCanceled()
                }

                // reporter text and fraction parameters are always set when the reporter is used elsewhere,
                // but details aren't thus they should be cleaned up
                reporter.details(null)

                uploadedSuccessfully = true
            },

            cleanUpAction = { fileSystemWidget, reporter ->
                if (fileSystemWidget.state == State.CONNECTED) {
                    if (shouldUseFTP(project)) {
                        reporter.text("Cleaning up after FTP upload...")
                        reporter.fraction(null)

                        ftpUploadClient?.disconnect()

                        val scriptFileName = "ftp_cleanup.py"

                        val ftpCleanupScript = pythonService.retrieveMpyScriptAsString(scriptFileName)

                        fileSystemWidget(project)?.blindExecute(SHORT_TIMEOUT, ftpCleanupScript)
                    } else {
                        // Run garbage collection after REPL based uploads
                        val commands = mutableListOf<String>(
                            "import gc",
                            "gc.collect()"
                        )
                        fileSystemWidget.blindExecute(SHORT_TIMEOUT, *commands.toTypedArray())
                    }
                }
            }
        )
        return uploadedSuccessfully
    }

    @Suppress("UnstableApiUsage")
    private suspend fun writeDown(
        node: FileSystemNode,
        fileSystemWidget: FileSystemWidget,
        name: String,
        destination: VirtualFile?
    ) {
        if (node is FileNode) {
            val content = fileSystemWidget.download(node.fullName)
            writeAction {
                destination!!.findOrCreateFile(name).setBinaryContent(content)
            }
        } else {
            writeAction {
                destination!!.findOrCreateDirectory(name)
            }
        }
    }

    fun downloadDeviceFiles() {
        performReplAction(
            project = project,
            connectionRequired = true,
            description = "Download",
            requiresRefreshAfter = false,
            action = { fileSystemWidget, reporter ->
                reporter.text("Collecting files to download...")
                reporter.fraction(null)

                val selectedFiles = fileSystemWidget.selectedFiles()
                if (selectedFiles.isEmpty()) return@performReplAction
                var destination: VirtualFile? = null

                withContext(Dispatchers.EDT) {
                    FileChooserFactory.getInstance().createPathChooser(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor(), fileSystemWidget.project, null
                    ).choose(null) { folders ->
                        destination = folders.firstOrNull()
                        if (destination?.children?.isNotEmpty() == true) {
                            if (Messages.showOkCancelDialog(
                                    fileSystemWidget.project,
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
                    writeDown(node, fileSystemWidget, name, destination)
                    reporter.text("Downloading: file $downloadedFiles of ${parentNameToFile.size}")
                    reporter.fraction(downloadedFiles.toDouble() * singleFileProgress)
                    reporter.details(name)

                    downloadedFiles++
                }
            }
        )
    }
}