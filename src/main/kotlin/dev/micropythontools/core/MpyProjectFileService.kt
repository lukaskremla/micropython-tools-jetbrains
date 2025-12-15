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

package dev.micropythontools.core

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.modules
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.project.stateStore
import com.jetbrains.python.sdk.PythonSdkUtil
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.sourceroots.MpySourceRootType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CachedSnapshot(
    val content: ByteArray,
    val length: Long,
    val crc32: String
)

private val SNAPSHOT_KEY = Key.create<CachedSnapshot>("mpy.upload.snapshot")

internal fun VirtualFile.putSnapshot(snapshot: CachedSnapshot) {
    this.putUserData(SNAPSHOT_KEY, snapshot)
}

internal fun VirtualFile.getSnapshot(): CachedSnapshot =
    this.getUserData(SNAPSHOT_KEY)
        ?: throw RuntimeException(MpyBundle.message("upload.error.cached.snapshot.not.found"))

internal val VirtualFile.crc32: String
    get() {
        val localFileBytes = this.contentsToByteArray()
        val crc = java.util.zip.CRC32()
        crc.update(localFileBytes)
        return "%08x".format(crc.value)
    }

@Service(Service.Level.PROJECT)
internal class MpyProjectFileService(private val project: Project) {
    private fun VirtualFile.leadingDot() = this.name.startsWith(".")

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

    private fun collectProjectCollectibles(): Set<VirtualFile> {
        return project.modules.flatMap { module ->
            module.rootManager.contentEntries
                .mapNotNull { it.file }
                .flatMap { it.children.toList() }
                .filter { !it.leadingDot() }
                .toMutableList()
        }.toSet()
    }

    fun createVirtualFileToTargetPathMap(
        files: Set<VirtualFile>,
        targetDestination: String? = "/",
        relativeToFolders: Set<VirtualFile>? = null
    ): MutableMap<VirtualFile, String> {
        val sourceFolders = collectMpySourceRoots()
        val projectDir = project.guessProjectDir()
            ?: throw RuntimeException(MpyBundle.message("upload.error.could.not.locate.project.root"))

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

    suspend fun collectFilesAndFolders(
        initialFilesToCollect: Set<VirtualFile> = emptySet(),
        initialIsProjectCollection: Boolean = false,
    ): Pair<Set<VirtualFile>, Set<VirtualFile>> {
        val projectDir = project.guessProjectDir()
            ?: throw RuntimeException(MpyBundle.message("upload.error.could.not.locate.project.root"))

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        projectDir.refresh(false, true)

        val excludedFolders = collectExcluded()
        val sourceFolders = collectMpySourceRoots()
        val testFolders = collectTestRoots()

        var isProjectCollection = initialIsProjectCollection

        var filesToCollect =
            if (initialIsProjectCollection) collectProjectCollectibles().toMutableList() else initialFilesToCollect.toMutableList()
        val foldersToUpload = mutableSetOf<VirtualFile>()

        var i = 0
        while (i < filesToCollect.size) {
            val file = filesToCollect[i]

            val shouldSkip = !file.isValid ||
                    // Only skip leading dot files unless it's a project dir
                    // or unless it's in the initial list, which means the user explicitly selected it
                    (file.leadingDot() && file != projectDir && !initialFilesToCollect.contains(file)) ||
                    // Skip files explicitly ignored by the IDE
                    FileTypeRegistry.getInstance().isFileIgnored(file) ||
                    // All excluded folders and their children are always meant to be skipped
                    excludedFolders.any { VfsUtil.isAncestor(it, file, false) } ||
                    // Skip test folders and their children if it's a project upload or if they weren't explicitly selected by the user
                    (testFolders.any { VfsUtil.isAncestor(it, file, false) } &&
                            (isProjectCollection || !initialFilesToCollect.any {
                                VfsUtil.isAncestor(
                                    it,
                                    file,
                                    false
                                )
                            })) ||
                    // For project uploads, if at least one MicroPython Sources Root was selected
                    // then only contents of MicroPython Sources Roots are to be uploaded
                    (isProjectCollection && sourceFolders.isNotEmpty() &&
                            !sourceFolders.any { VfsUtil.isAncestor(it, file, false) })

            when {
                shouldSkip -> {
                    filesToCollect.removeAt(i)
                }

                file == projectDir -> {
                    // If a project root is found start over and treat this as a project upload
                    i = 0
                    filesToCollect.clear()
                    filesToCollect = collectProjectCollectibles().toMutableList()
                    isProjectCollection = true
                }

                file.isDirectory -> {
                    filesToCollect.addAll(file.children)
                    filesToCollect.removeAt(i)

                    foldersToUpload.add(file)
                }

                else -> i++
            }
        }

        return Pair(filesToCollect.toSet(), foldersToUpload.toSet())
    }
}
