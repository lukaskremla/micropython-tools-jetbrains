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
import com.intellij.openapi.vfs.*
import com.intellij.project.stateStore
import com.jetbrains.python.sdk.PythonSdkUtil
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.*
import dev.micropythontools.util.MpyPythonService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException

@Service(Service.Level.PROJECT)
/**
 * @author Lukas Kremla, elmot
 */
class MpyTransferService(private val project: Project) {
    fun listSerialPorts(): MutableList<String> {
        val filteredPorts = mutableListOf<String>()

        val ports = SerialPort.getCommPorts()
        for (port in ports) {
            if (port.manufacturer == "Unknown" ||
                port.systemPortPath.startsWith("/dev/tty.")
            ) continue

            filteredPorts.add(port.systemPortPath)
        }

        return filteredPorts
    }

    private fun calculateCRC32(file: VirtualFile): String {
        val localFileBytes = file.contentsToByteArray()
        val crc = java.util.zip.CRC32()
        crc.update(localFileBytes)
        return String.format("%08x", crc.value)
    }

    private suspend fun synchronizeAndGetAlreadyUploadedFiles(
        uploadFilesToTargetPath: MutableMap<VirtualFile, String>,
        excludedPaths: List<String>,
        shouldSynchronize: Boolean,
        shouldExcludePaths: Boolean
    ): MutableList<VirtualFile> {
        val alreadyUploadedFiles = mutableListOf<VirtualFile>()
        val fileToUploadListing = mutableListOf<String>()
        val targetPathToFile = uploadFilesToTargetPath.entries.associate { (file, path) -> path to file }

        for (file in uploadFilesToTargetPath.keys) {
            val path = uploadFilesToTargetPath[file]
            val size = file.length
            val hash = calculateCRC32(file)

            fileToUploadListing.add("""("$path", $size, "$hash")""")
        }

        val scriptFileName = "synchronize_and_skip.py"

        val pythonService = project.service<MpyPythonService>()

        val synchronizeAndSkipScript = pythonService.retrieveMpyScriptAsString(scriptFileName)

        val formattedScript = synchronizeAndSkipScript.format(
            if (shouldSynchronize) "True" else "False",
            fileToUploadListing.joinToString(",\n        "),
            if (excludedPaths.isNotEmpty() && shouldExcludePaths) excludedPaths.joinToString(",\n        ") { "\"$it\"" } else ""
        )

        val scriptResponse = fileSystemWidget(project)!!.blindExecute(30000L, formattedScript).extractSingleResponse().trim()

        val matchingTargetPaths = when {
            !scriptResponse.contains("NO MATCHES") && !scriptResponse.contains("ERROR") -> scriptResponse
                .split("&")
                .filter { it.isNotEmpty() }

            else -> emptyList()
        }

        if (scriptResponse.contains("ERROR")) {
            Notifications.Bus.notify(
                Notification(
                    NOTIFICATION_GROUP,
                    "Failed to execute synchronize and check matches script: $scriptResponse",
                    NotificationType.ERROR
                ), project
            )
        }

        for (path in matchingTargetPaths) {
            targetPathToFile[path]?.let {
                alreadyUploadedFiles.add(it)
            }
        }

        return alreadyUploadedFiles
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

    fun uploadProject(
        excludedPaths: List<String> = emptyList(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false,
        useFTP: Boolean = false,
        ssid: String = "",
        wifiPassword: String = ""
    ): Boolean {

        FileDocumentManager.getInstance().saveAllDocuments()
        val filesToUpload = collectProjectUploadables()
        return performUpload(
            filesToUpload,
            true,
            excludedPaths,
            shouldSynchronize,
            shouldExcludePaths,
            useFTP,
            ssid,
            wifiPassword
        )
    }

    fun uploadFileOrFolder(
        toUpload: VirtualFile,
        excludedPaths: List<String> = emptyList(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false,
        useFTP: Boolean = false,
        ssid: String = "",
        wifiPassword: String = ""
    ): Boolean {

        FileDocumentManager.getInstance().saveAllDocuments()
        return performUpload(
            setOf(toUpload),
            false,
            excludedPaths,
            shouldSynchronize,
            shouldExcludePaths,
            useFTP,
            ssid,
            wifiPassword
        )
    }

    fun uploadItems(
        toUpload: Set<VirtualFile>,
        excludedPaths: List<String> = emptyList(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false,
        useFTP: Boolean = false,
        ssid: String = "",
        wifiPassword: String = ""
    ): Boolean {

        FileDocumentManager.getInstance().saveAllDocuments()
        return performUpload(
            toUpload,
            false,
            excludedPaths,
            shouldSynchronize,
            shouldExcludePaths,
            useFTP,
            ssid,
            wifiPassword
        )
    }

    private fun performUpload(
        toUpload: Set<VirtualFile>,
        initialIsProjectUpload: Boolean,
        excludedPaths: List<String> = emptyList(),
        shouldSynchronize: Boolean = false,
        shouldExcludePaths: Boolean = false,
        useFTP: Boolean = false,
        ssid: String = "",
        password: String = ""
    ): Boolean {

        val pythonService = project.service<MpyPythonService>()

        var isProjectUpload = initialIsProjectUpload
        var filesToUpload = toUpload.toMutableList()
        val excludedFolders = collectExcluded()
        val projectDir = project.guessProjectDir()
        val mpySourceFolders = project.service<MpySettingsService>().state.mpySourcePaths
            .mapNotNull { StandardFileSystems.local().findFileByPath(it) }

        mpySourceFolders.filter { potentialChild ->
            !mpySourceFolders.any { potentialAncestor ->
                VfsUtil.isAncestor(potentialAncestor, potentialChild, true)
            }
        }

        var ftpUploadClient: MpyFTPClient? = null
        var uploadedSuccessfully = false

        performReplAction(
            project = project,
            connectionRequired = true,
            description = "Upload",
            requiresRefreshAfter = true,
            action = { fileSystemWidget, reporter ->
                reporter.text("Collecting files to upload...")
                reporter.fraction(null)

                var i = 0
                while (i < filesToUpload.size) {
                    val file = filesToUpload[i]

                    val shouldSkip = !file.isValid ||
                            (file.leadingDot() && file != projectDir) ||
                            FileTypeRegistry.getInstance().isFileIgnored(file) ||
                            excludedFolders.any { VfsUtil.isAncestor(it, file, false) } ||
                            (isProjectUpload && !file.isDirectory &&
                                    !mpySourceFolders.any { VfsUtil.isAncestor(it, file, false) })

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
                        }

                        else -> i++
                    }

                    checkCanceled()
                }
                val uniqueFilesToUpload = filesToUpload.distinct()
                val fileToTargetPath = mutableMapOf<VirtualFile, String>()

                uniqueFilesToUpload.forEach { file ->
                    val path = when {
                        mpySourceFolders.find { VfsUtil.isAncestor(it, file, false) }?.let { sourceRoot ->
                            VfsUtil.getRelativePath(file, sourceRoot) ?: file.name
                        } != null -> VfsUtil.getRelativePath(
                            file,
                            mpySourceFolders.find { VfsUtil.isAncestor(it, file, false) }!!
                        ) ?: file.name

                        else -> projectDir?.let { VfsUtil.getRelativePath(file, it) } ?: file.name
                    }

                    fileToTargetPath[file] = if (path.startsWith("/")) path else "/$path"
                }

                // Group by target path to find duplicates
                val pathGroups = fileToTargetPath.entries.groupBy(
                    { entry -> entry.value },
                    { entry -> projectDir?.let { VfsUtil.getRelativePath(entry.key, it) } ?: entry.key.path }
                )
                val duplicates = pathGroups.filter { it.value.size > 1 }

                if (duplicates.isNotEmpty()) {
                    throw IllegalStateException(
                        "Multiple source files would be uploaded to the same target path!<br>" +
                                duplicates.entries.joinToString("<br>") { (targetPath, sourceFiles) ->
                                    "Target path: \"$targetPath\" has multiple sources:<br>" +
                                            "\"${sourceFiles.joinToString("\"<br>\"")}\""
                                }
                    )
                }

                val scriptProgressText = if (shouldSynchronize) {
                    "Synchronizing and skipping already uploaded files..."
                } else {
                    "Skipping already uploaded files..."
                }

                reporter.text(scriptProgressText)
                reporter.fraction(null)

                val alreadyUploadedFiles = synchronizeAndGetAlreadyUploadedFiles(
                    fileToTargetPath,
                    excludedPaths,
                    shouldSynchronize,
                    shouldExcludePaths
                )

                fileToTargetPath.keys.removeAll(alreadyUploadedFiles.toSet())

                if (useFTP && fileToTargetPath.isNotEmpty()) {
                    if (ssid.isEmpty()) {
                        Notifications.Bus.notify(
                            Notification(
                                NOTIFICATION_GROUP,
                                "Cannot upload over FTP, no SSID was provided in settings! Falling back to normal communication.",
                                NotificationType.ERROR
                            ), project
                        )
                    } else {
                        reporter.text("Establishing an FTP server connection...")
                        reporter.fraction(null)

                        val scriptFileName = "ftp.py"

                        val ftpScript = pythonService.retrieveMpyScriptAsString(scriptFileName)

                        val formattedScript = ftpScript.format(
                            """"$ssid"""",
                            """"$password"""",
                            20 // Wi-Fi connection timeout
                        )

                        val scriptResponse = fileSystemWidget.blindExecute(LONG_TIMEOUT, formattedScript)
                            .extractSingleResponse().trim()

                        if (scriptResponse.contains("ERROR") || !scriptResponse.startsWith("IP: ")) {
                            Notifications.Bus.notify(
                                Notification(
                                    NOTIFICATION_GROUP,
                                    "Ran into an error establishing an FTP connection, falling back to normal communication: $scriptResponse",
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
                                        "Connecting to FTP server failed: $e",
                                        NotificationType.ERROR
                                    ), project
                                )
                            }
                        }
                    }
                }

                val totalBytes = fileToTargetPath.keys.sumOf { it.length }

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
                    reporter.text("Uploading: file $uploadedFiles of ${fileToTargetPath.size} | ${"%.2f".format(uploadedKB)} KB of ${totalBytes / 1000} KB")
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
                    ftpUploadClient?.disconnect()

                    reporter.text("Cleaning up after FTP upload...")
                    reporter.fraction(null)

                    if (useFTP) {
                        val scriptFileName = "ftp_cleanup.py"

                        val ftpCleanupScript = pythonService.retrieveMpyScriptAsString(scriptFileName)

                        fileSystemWidget(project)?.blindExecute(TIMEOUT, ftpCleanupScript)
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