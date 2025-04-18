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
import dev.micropythontools.sourceroots.MpySourceRootType
import dev.micropythontools.ui.*
import dev.micropythontools.util.MpyPythonService
import kotlinx.coroutines.*
import java.io.IOException

@Service(Service.Level.PROJECT)
/**
 * @author Lukas Kremla, elmot
 */
class MpyTransferService(private val project: Project) {
    fun listSerialPorts(filterManufacturers: Boolean = project.service<MpySettingsService>().state.filterManufacturers): MutableList<String> {
        val os = System.getProperty("os.name").lowercase()

        val isWindows = os.contains("win")

        val filteredPorts = mutableListOf<String>()
        val ports = SerialPort.getCommPorts()

        for (port in ports) {
            if ((filterManufacturers && port.manufacturer == "Unknown") || port.systemPortPath.startsWith("/dev/tty.")) continue

            if (isWindows) {
                filteredPorts.add(port.systemPortName)
            } else {
                filteredPorts.add(port.systemPortPath)
            }
        }

        return filteredPorts
    }

    suspend fun recursivelySafeDeletePaths(paths: Set<String>) {
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

        val filteredPaths = paths.filter { path ->
            // Keep this path only if no other path is a prefix of it
            paths.none { otherPath ->
                otherPath != path && path.startsWith("$otherPath/")
            }
        }

        filteredPaths.forEach {
            commands.add("___d('${it}')")
        }

        commands.add("del ___d")
        commands.add("gc.collect()")

        fileSystemWidget(project)?.blindExecute(LONG_TIMEOUT, *commands.toTypedArray())?.extractResponse()
    }

    suspend fun safeCreateDirectories(paths: Set<String>) {
        val mkdirCommands = mutableListOf(
            "import os, gc",
            "def ___m(p):",
            "   try:",
            "       os.mkdir(p)",
            "   except:",
            "       pass"
        )

        println(paths)

        val allPaths = buildSet {
            paths.forEach { path ->
                // Generate and add all parent directories
                val parts = path.split("/")
                var currentPath = ""
                for (part in parts) {
                    if (part.isEmpty()) continue
                    currentPath += "/$part"
                    add(currentPath)
                }
            }
        }

        val sortedPaths = allPaths
            // Sort shortest paths first, ensures parents are created before children
            .sortedBy { it.split("/").filter { it.isNotEmpty() }.size }
            .toList()

        sortedPaths.forEach {
            mkdirCommands.add("___m('$it')")
        }
        mkdirCommands.add("del ___m")
        mkdirCommands.add("gc.collect()")

        fileSystemWidget(project)?.blindExecute(LONG_TIMEOUT, *mkdirCommands.toTypedArray())?.extractSingleResponse()
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

    fun uploadFileOrFolder(
        fileToUpload: VirtualFile,
        shouldGoToRoot: Boolean = false,
        excludedPaths: Set<String> = emptySet(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false
    ): Boolean {

        val relativeToFolders = when {
            shouldGoToRoot -> setOf(fileToUpload.parent)

            else -> emptySet<VirtualFile>()
        }

        return performUpload(
            initialFilesToUpload = setOf(fileToUpload),
            relativeToFolders = relativeToFolders,
            excludedPaths = excludedPaths,
            shouldSynchronize = shouldSynchronize,
            shouldExcludePaths = shouldExcludePaths
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
        val pythonService = project.service<MpyPythonService>()

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

                println(fileToTargetPath)
                println(folderToTargetPath)

                if (fileSystemWidget.deviceInformation.hasCRC32) {
                    reporter.text(if (shouldSynchronize) "Syncing and skipping already uploaded files..." else "Detecting already uploaded files...")
                    fileSystemWidget.quietHashingRefresh(reporter)
                } else if (shouldSynchronize) {
                    reporter.text("Synchronizing...")
                    fileSystemWidget.quietRefresh(reporter)
                }

                if (shouldSynchronize || fileSystemWidget.deviceInformation.hasCRC32) {
                    // Traverse and collect all file system nodes
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
                        val hash = calculateCRC32(file)

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
                        delay(500)
                        recursivelySafeDeletePaths(targetPathsToRemove)
                    }
                }

                // Create all directories
                if (folderToTargetPath.isNotEmpty()) {
                    reporter.text("Creating directories...")
                    delay(500)
                    safeCreateDirectories(folderToTargetPath.values.toSet())
                }

                val totalBytes = fileToTargetPath.keys.sumOf { it.length }.toDouble()

                if (shouldUseFTP(project, totalBytes) && fileToTargetPath.isNotEmpty()) {
                    shouldCleanUpFTP = true

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
                        val cachedFtpScriptPath = "${settings.state.cachedFTPScriptPath ?: ""}/uftpd.py"
                        val cachedFtpScriptImportPath = cachedFtpScriptPath
                            .replace("/", ".")
                            .removeSuffix(".py")
                            .trim('.')

                        if (settings.state.cacheFTPScript) {
                            reporter.text("Validating the cached FTP script...")

                            val cachedFtpScriptPath = "${settings.state.cachedFTPScriptPath ?: ""}/uftpd.py"
                            val cachedFtpScriptImportPath = cachedFtpScriptPath
                                .replace("/", ".")
                                .removeSuffix(".py")
                                .trim('.')

                            val isUftpdAvailable: Boolean = when {
                                hasUftpdCached != null -> hasUftpdCached
                                else -> {
                                    val commands = mutableListOf<String>(
                                        "try:",
                                        "   import $cachedFtpScriptImportPath",
                                        "   print('valid')",
                                        "except ImportError:",
                                        "   pass"
                                    )

                                    delay(500)
                                    fileSystemWidget.blindExecute(SHORT_TIMEOUT, *commands.toTypedArray())
                                        .extractSingleResponse() == "valid"
                                }
                            }

                            if (!isUftpdAvailable) {
                                val ftpFile = pythonService.retrieveMpyScriptAsVirtualFile("uftpd.py")
                                val ftpTotalBytes = ftpFile.length.toDouble()
                                var uploadedFTPKB = 0.0
                                var uploadFtpProgress = 0.0

                                fun ftpUploadProgressCallbackHandler(uploadedBytes: Int) {
                                    uploadedFTPKB += (uploadedBytes.toDouble() / 1000)
                                    // Convert to double for maximal accuracy
                                    uploadFtpProgress += (uploadedBytes.toDouble() / ftpTotalBytes.toDouble())
                                    // Ensure that uploadProgress never goes over 1.0
                                    // as floating point arithmetic can have minor inaccuracies
                                    uploadFtpProgress = uploadFtpProgress.coerceIn(0.0, 1.0)
                                    reporter.text("Uploading ftp script... ${"%.2f".format(uploadedFTPKB)} KB of ${"%.2f".format(ftpTotalBytes / 1000)} KB")
                                    reporter.fraction(uploadFtpProgress)
                                }

                                val scriptPath = settings.state.cachedFTPScriptPath

                                val parentDirectories = when {
                                    scriptPath == null -> setOf("")
                                    else -> {
                                        val parts = scriptPath.split("/")
                                        buildSet {
                                            var currentPath = ""
                                            for (part in parts) {
                                                if (part.isEmpty()) continue
                                                currentPath += "/$part"
                                                add(currentPath)
                                            }
                                        }
                                    }
                                }

                                // Create the parent directories
                                safeCreateDirectories(parentDirectories)

                                try {
                                    fileSystemWidget.upload(cachedFtpScriptPath, ftpFile.contentsToByteArray(), ::ftpUploadProgressCallbackHandler)
                                } catch (e: Throwable) {
                                    print(e)
                                }
                            }
                        }

                        reporter.text("Establishing an FTP server connection...")

                        val commands = mutableListOf<String>()

                        commands.add("import $cachedFtpScriptImportPath as uftpd")
                        val cleanUpScriptName = "ftp_cleanup.py"
                        val cleanupScriptName = pythonService.retrieveMpyScriptAsString(cleanUpScriptName)
                        val formattedCleanUpScript = cleanupScriptName.format(
                            if (settings.state.usingUart) "True" else "False"
                        )
                        commands.add(formattedCleanUpScript)

                        if (settings.state.usingUart) {
                            val wifiConnectScriptName = "connect_to_wifi.py"
                            val wifiConnectScript = pythonService.retrieveMpyScriptAsString(wifiConnectScriptName)
                            val formattedWifiConnectScript = wifiConnectScript.format(
                                """"$ssid"""",
                                """"$password"""",
                                20, // Wi-Fi connection timeout
                            )
                            commands.add(formattedWifiConnectScript)
                        }

                        if (settings.state.cacheFTPScript) {
                            commands.add("import $cachedFtpScriptImportPath as uftpd")
                            commands.add("uftpd.start()")
                        } else {
                            val miniUftpdScript = pythonService.retrieveMpyScriptAsString("mini_uftpd.py")

                            commands.add(miniUftpdScript)
                            commands.add(
                                "___ftp().start()"
                            )
                        }

                        // Catch all exceptions to avoid showing the wi-fi credentials as a notification
                        val scriptResponse = try {
                            delay(500)
                            fileSystemWidget.blindExecute(LONG_TIMEOUT, *commands.toTypedArray())
                                .extractSingleResponse().trim()
                        } catch (e: Throwable) {
                            if (e.localizedMessage.contains("timed")) {
                                "ERROR: FTP Connection attempt timed out"
                            } else {
                                "ERROR: There was a problem attempting to establish the FTP connection $e"
                            }
                        }

                        if (scriptResponse.contains("ERROR")) {
                            Notifications.Bus.notify(
                                Notification(
                                    NOTIFICATION_GROUP,
                                    "Ran into an error establishing an FTP connection, falling back to REPL uploads: $scriptResponse",
                                    NotificationType.ERROR
                                ), project
                            )
                        } else {
                            try {
                                val ip = when {
                                    settings.state.usingUart -> scriptResponse
                                        .removePrefix("FTP server started on ")
                                        .removeSuffix(":21")

                                    else -> settings.state.webReplUrl
                                        ?.removePrefix("ws://")
                                        ?.removePrefix("wss://")
                                        ?.split(":")[0]
                                        ?: ""
                                }

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
                if (shouldCleanUpFTP) {
                    if (shouldUseFTP(project)) {
                        reporter.text("Cleaning up after FTP upload...")
                        reporter.fraction(null)

                        ftpUploadClient?.disconnect()

                        val cachedFtpScriptPath = "${settings.state.cachedFTPScriptPath ?: ""}/uftpd.py"
                        val cachedFtpScriptImportPath = cachedFtpScriptPath
                            .replace("/", ".")
                            .removeSuffix(".py")
                            .trim('.')

                        val commands = mutableListOf<String>()

                        commands.add("import $cachedFtpScriptImportPath as uftpd")
                        val cleanUpScriptName = "ftp_cleanup.py"
                        val cleanupScriptName = pythonService.retrieveMpyScriptAsString(cleanUpScriptName)
                        val formattedCleanUpScript = cleanupScriptName.format(
                            if (settings.state.usingUart) "True" else "False"
                        )
                        commands.add(formattedCleanUpScript)

                        commands.add(formattedCleanUpScript)

                        fileSystemWidget(project)?.blindExecute(LONG_TIMEOUT, *commands.toTypedArray())
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