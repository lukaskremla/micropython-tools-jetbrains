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

package dev.micropythontools.intellij.run

import com.intellij.execution.Executor
import com.intellij.execution.configuration.AbstractRunConfiguration
import com.intellij.execution.configuration.EmptyRunProfileState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationWithSuppressedDefaultDebugAction
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.facet.ui.ValidationResult
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.modules
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.project.stateStore
import com.intellij.util.PathUtil
import com.jetbrains.python.sdk.PythonSdkUtil
import dev.micropythontools.intellij.nova.*
import dev.micropythontools.intellij.settings.MpyProjectConfigurable
import dev.micropythontools.intellij.settings.MpySettingsService
import dev.micropythontools.intellij.settings.mpyFacet
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.jdom.Element
import java.io.ByteArrayInputStream
import java.io.IOException


/**
 * @authors Lukas Kremla, elmot, vlan
 */
private class FTPUploadClient {
    private val ftpClient: FTPClient = FTPClient()

    fun connect(ip: String, ftpUsername: String, ftpPassword: String) {
        ftpClient.connect(ip)
        ftpClient.login(ftpUsername, ftpPassword)
        ftpClient.enterLocalPassiveMode()
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
    }

    fun disconnect() {
        ftpClient.logout()
        ftpClient.disconnect()
    }

    fun uploadFile(bytes: ByteArray, path: String) {
        val unixPath = if (!path.startsWith("/")) {
            "/$path"
        } else {
            path
        }

        val fileName = unixPath.substringAfterLast("/")
        val filePath = unixPath.substringBeforeLast("/")

        if (filePath != "/") {
            val dirs = filePath.split("/").filter { it.isNotEmpty() }
            var currentPath = ""

            for (dir in dirs) {
                currentPath += "/$dir"
                try {
                    ftpClient.makeDirectory(currentPath)
                } catch (e: Exception) {
                    // Directory might already exist, continue
                }
            }
        }

        ftpClient.changeWorkingDirectory(filePath)

        ByteArrayInputStream(bytes).use { inputStream ->
            ftpClient.storeFile(fileName, inputStream)
        }
    }
}

class MpyRunConfiguration(project: Project, factory: ConfigurationFactory) : AbstractRunConfiguration(project, factory),
    RunConfigurationWithSuppressedDefaultDebugAction {
    var path: String = ""
    var runReplOnSuccess: Boolean = false
    var resetOnSuccess: Boolean = true
    var useFTP: Boolean = false
    var synchronize: Boolean = false
    var excludePaths: Boolean = false
    var excludedPaths: MutableList<String> = mutableListOf()

    override fun getValidModules() =
        allModules.filter { it.mpyFacet != null }.toMutableList()

    override fun getConfigurationEditor() = MpyRunConfigurationEditor(this)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val success: Boolean
        val projectDir = project.guessProjectDir()
        val projectPath = projectDir?.path

        var ssid = ""
        var wifiPassword = ""

        runWithModalProgressBlocking(project, "Retrieving credentials...") {
            val wifiCredentials = project.service<MpySettingsService>().retrieveWifiCredentials()

            ssid = wifiCredentials.userName ?: ""
            wifiPassword = wifiCredentials.getPasswordAsString() ?: ""
        }

        if (path.isBlank() || (projectPath != null && path == projectPath)) {
            success = uploadProject(project, excludedPaths, synchronize, excludePaths, useFTP, ssid, wifiPassword)
        } else {
            val toUpload = StandardFileSystems.local().findFileByPath(path) ?: return null
            success = uploadFileOrFolder(
                project,
                toUpload,
                excludedPaths,
                synchronize,
                excludePaths,
                useFTP,
                ssid,
                wifiPassword
            )
        }
        if (success) {
            val fileSystemWidget = fileSystemWidget(project)
            if (resetOnSuccess) fileSystemWidget?.reset()
            if (runReplOnSuccess) fileSystemWidget?.activateRepl()
            return EmptyRunProfileState.INSTANCE
        } else {
            return null
        }
    }

    override fun checkConfiguration() {
        super.checkConfiguration()
        val m = module ?: throw RuntimeConfigurationError("Module for path was not found")

        val facet = m.mpyFacet ?: throw RuntimeConfigurationError(
            "MicroPython support was not enabled for this project",
            Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyProjectConfigurable::class.java) }
        )
        val validationResult = facet.checkValid()
        if (validationResult != ValidationResult.OK) {
            if (validationResult.quickFix != null) {
                val runQuickFix = Runnable {
                    validationResult.quickFix.run(null)
                }
                throw RuntimeConfigurationError(validationResult.errorMessage, runQuickFix)
            } else {
                throw RuntimeConfigurationError(validationResult.errorMessage)
            }
        }
        facet.pythonSdkPath ?: throw RuntimeConfigurationError("Python interpreter was not found")
    }

    override fun suggestedName() = "Flash ${PathUtil.getFileName(path)}"

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("path", path)
        element.setAttribute("run-repl-on-success", if (runReplOnSuccess) "yes" else "no")
        element.setAttribute("reset-on-success", if (resetOnSuccess) "yes" else "no")
        element.setAttribute("synchronize", if (synchronize) "yes" else "no")
        element.setAttribute("exclude-paths", if (excludePaths) "yes" else "no")
        element.setAttribute("ftp", if (useFTP) "yes" else "no")

        if (excludedPaths.isNotEmpty()) {
            val excludedPathsElement = Element("excluded-paths")
            excludedPaths.forEach { path ->
                val pathElement = Element("path")
                pathElement.setAttribute("value", path)
                excludedPathsElement.addContent(pathElement)
            }
            element.addContent(excludedPathsElement)
        }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        configurationModule.readExternal(element)
        element.getAttributeValue("path")?.let {
            path = it
        }
        element.getAttributeValue("run-repl-on-success")?.let {
            runReplOnSuccess = it == "yes"
        }
        element.getAttributeValue("reset-on-success")?.let {
            resetOnSuccess = it == "yes"
        }
        element.getAttributeValue("synchronize")?.let {
            synchronize = it == "yes"
        }
        element.getAttributeValue("exclude-paths")?.let {
            excludePaths = it == "yes"
        }
        element.getAttributeValue("ftp")?.let {
            useFTP = it == "yes"
        }

        excludedPaths.clear()

        excludedPaths.clear()
        element.getChild("excluded-paths")?.let { excludedPathsElement ->
            excludedPathsElement.getChildren("path").forEach { pathElement ->
                pathElement.getAttributeValue("value")?.let { path ->
                    excludedPaths.add(path)
                }
            }
        }
    }

    val module: Module?
        get() {
            if (path.isEmpty()) {
                val projectDir = project.guessProjectDir()
                if (projectDir != null) return ModuleUtil.findModuleForFile(projectDir, project)
            }
            val file = StandardFileSystems.local().findFileByPath(path) ?: return null
            return ModuleUtil.findModuleForFile(file, project)
        }

    companion object {
        private fun VirtualFile.leadingDot() = this.name.startsWith(".")

        private fun collectProjectUploadables(project: Project): Set<VirtualFile> {
            return project.modules.flatMap { module ->
                module.rootManager.contentEntries
                    .mapNotNull { it.file }
                    .flatMap { it.children.toList() }
                    .filter { !it.leadingDot() }
                    .toMutableList()
            }.toSet()
        }

        private fun collectExcluded(project: Project): Set<VirtualFile> {
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

        private fun collectSourceRoots(project: Project): Set<VirtualFile> {
            return project.modules.flatMap { module ->
                module.rootManager.contentEntries
                    .flatMap { entry -> entry.sourceFolders.toList() }
                    .filter { sourceFolder ->
                        !sourceFolder.isTestSource && sourceFolder.file?.let { !it.leadingDot() } ?: false
                    }
                    .mapNotNull { it.file }
            }.toSet()
        }

        private fun collectTestRoots(project: Project): Set<VirtualFile> {
            return project.modules.flatMap { module ->
                module.rootManager.contentEntries
                    .flatMap { entry -> entry.sourceFolders.toList() }
                    .filter { sourceFolder -> sourceFolder.isTestSource }
                    .mapNotNull { it.file }
            }.toSet()
        }

        fun uploadProject(
            project: Project,
            excludedPaths: List<String> = emptyList(),
            shouldSynchronize: Boolean = false,
            shouldExcludePaths: Boolean = false,
            useFTP: Boolean = false,
            ssid: String = "",
            wifiPassword: String = ""
        ): Boolean {

            FileDocumentManager.getInstance().saveAllDocuments()
            val filesToUpload = collectProjectUploadables(project)
            return performUpload(
                project,
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
            project: Project,
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
                project,
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
            project: Project,
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
                project,
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
            project: Project,
            toUpload: Set<VirtualFile>,
            initialIsProjectUpload: Boolean,
            excludedPaths: List<String> = emptyList(),
            shouldSynchronize: Boolean = false,
            shouldExcludePaths: Boolean = false,
            useFTP: Boolean = false,
            ssid: String = "",
            password: String = ""
        ): Boolean {

            var isProjectUpload = initialIsProjectUpload
            var filesToUpload = toUpload.toMutableList()
            val excludedFolders = collectExcluded(project)
            val sourceFolders = collectSourceRoots(project)
            val testFolders = collectTestRoots(project)
            val projectDir = project.guessProjectDir()

            var ftpUploadClient: FTPUploadClient? = null
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
                                excludedFolders.any { VfsUtil.isAncestor(it, file, true) } ||
                                (isProjectUpload && testFolders.any { VfsUtil.isAncestor(it, file, true) }) ||
                                (isProjectUpload && sourceFolders.isNotEmpty() &&
                                        !sourceFolders.any { VfsUtil.isAncestor(it, file, false) })

                        when {
                            shouldSkip -> {
                                filesToUpload.removeAt(i)
                            }

                            file == projectDir -> {
                                i = 0
                                filesToUpload.clear()
                                filesToUpload = collectProjectUploadables(project).toMutableList()
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
                            sourceFolders.find { VfsUtil.isAncestor(it, file, false) }?.let { sourceRoot ->
                                VfsUtil.getRelativePath(file, sourceRoot) ?: file.name
                            } != null -> VfsUtil.getRelativePath(
                                file,
                                sourceFolders.find { VfsUtil.isAncestor(it, file, false) }!!
                            ) ?: file.name

                            else -> projectDir?.let { VfsUtil.getRelativePath(file, it) } ?: file.name
                        }

                        fileToTargetPath[file] = if (path.startsWith("/")) path else "/$path"
                    }

                    val scriptProgressText = if (shouldSynchronize) {
                        "Syncing and skipping already uploaded files..."
                    } else {
                        "Detecting already uploaded files..."
                    }

                    reporter.text(scriptProgressText)
                    reporter.fraction(null)

                    val alreadyUploadedFiles = fileSystemWidget.synchronizeAndGetAlreadyUploadedFiles(
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

                            val module = project.let { ModuleManager.getInstance(it).modules.firstOrNull() }

                            val scriptFileName = "ftp.py"

                            val ftpScript = module?.mpyFacet?.retrieveMpyScriptAsString(scriptFileName)
                                ?: throw Exception("Failed to find: $scriptFileName")

                            val formattedScript = ftpScript.format(
                                """"$ssid"""",
                                """"$password"""",
                                10 // Wi-Fi connection timeout
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

                                    ftpUploadClient = FTPUploadClient()
                                    ftpUploadClient?.connect(ip, "", "") // No credentials are used
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
                    var uploadedKB = 0
                    var uploadedFiles = 1

                    fileToTargetPath.forEach { (file, path) ->
                        uploadProgress += (file.length.toDouble() / totalBytes.toDouble())

                        reporter.text("Uploading files: $uploadedFiles of ${fileToTargetPath.size} files | $uploadedKB KB of ${totalBytes / 1000} KB")
                        reporter.fraction(uploadProgress)
                        reporter.details(path)

                        if (ftpUploadClient != null) {
                            try {
                                withTimeout(10_000) {
                                    ftpUploadClient?.uploadFile(file.contentsToByteArray(), path)
                                }
                            } catch (e: TimeoutCancellationException) {
                                throw IOException("Timed out while uploading a file with FTP")
                            }
                        } else {
                            fileSystemWidget.upload(path, file.contentsToByteArray())
                        }

                        uploadedKB += (file.length / 1000).toInt()
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
                            val module = project.let { ModuleManager.getInstance(it).modules.firstOrNull() }

                            val scriptFileName = "ftp_cleanup.py"

                            val ftpCleanupScript = module?.mpyFacet?.retrieveMpyScriptAsString(scriptFileName)
                                ?: throw Exception("Failed to find: $scriptFileName")

                            fileSystemWidget(project)?.blindExecute(TIMEOUT, ftpCleanupScript)
                        }
                    }
                }
            )
            return uploadedSuccessfully
        }
    }
}
