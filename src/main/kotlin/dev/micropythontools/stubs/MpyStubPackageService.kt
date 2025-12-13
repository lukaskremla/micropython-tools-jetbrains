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

package dev.micropythontools.stubs

import com.intellij.facet.ui.FacetConfigurationQuickFix
import com.intellij.facet.ui.ValidationResult
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModifiableModelsProvider
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.util.progress.SequentialProgressReporter
import com.intellij.platform.util.progress.reportSequentialProgress
import com.intellij.util.io.delete
import com.jetbrains.python.library.PythonLibraryType
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.core.MpyPaths.PYTHON_PACKAGE_DIST_INFO_SUFFIX
import dev.micropythontools.core.MpyPaths.STUB_PACKAGE_METADATA_FILE_NAME
import dev.micropythontools.core.MpyPythonInterpreterService
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal data class StubPackage(
    val name: String,
    val mpyVersion: String,
    val port: String,
    val board: String,
    val variant: String,
    val isInstalled: Boolean,
    val isUpToDate: Boolean,
    val exactPackageVersion: String,
    val exactStdlibVersion: String
)

private data class CachedStubPackageUpToDateInfo(
    val name: String,
    val mpyVersion: String,
    val isUpToDate: Boolean,
    val exactPackageVersion: String,
    val exactStdlibVersion: String,
    val timeStamp: Long
)

@Serializable
private data class RemoteStubPackage(
    val name: String,
    val mpyVersion: String,
    val port: String,
    val board: String,
    val variant: String,
    val exactPackageVersion: String,
    val exactStdlibVersion: String
)

@Serializable
private data class StubPackageMetadata(
    val port: String,
    val board: String,
    val variant: String
) {
    companion object {
        fun fromJson(jsonString: String): StubPackageMetadata {
            return Json.decodeFromString<StubPackageMetadata>(jsonString)
        }
    }
}

@Serializable
private data class MpyStubsJson(
    val version: String,
    val packages: List<RemoteStubPackage>
) {
    companion object {
        fun fromJson(jsonString: String): MpyStubsJson {
            return Json.decodeFromString<MpyStubsJson>(jsonString)
        }
    }
}

@Serializable
private data class BundledStubsInfo(
    val compatibleIndexVersion: String
) {
    companion object {
        fun fromJson(jsonString: String): BundledStubsInfo {
            return Json.decodeFromString<BundledStubsInfo>(jsonString)
        }
    }
}

@Service(Service.Level.PROJECT)
internal class MpyStubPackageService(private val project: Project) {
    companion object {
        private const val LIBRARY_NAME = "MicroPythonToolsStubs"
        private val LIBRARIES_TO_REMOVE = listOf(
            LIBRARY_NAME,
            // Legacy library names to clean up
            "MicroPython Tools",
            "MicroPythonTools"
        )
    }

    private val pythonService = project.service<MpyPythonInterpreterService>()
    private val settings = project.service<MpySettingsService>()
    private val client: HttpClient = HttpClient.newHttpClient()

    private val compatibleIndexVersion: String

    init {
        val bundledJsonString =
            javaClass.getResourceAsStream("/bundled/${MpyPaths.BUNDLED_STUB_PACKAGE_INFO_JSON_FILE_NAME}")!!
                .bufferedReader()
                .readText()

        val bundledStubsInfo = BundledStubsInfo.fromJson(bundledJsonString)

        compatibleIndexVersion = bundledStubsInfo.compatibleIndexVersion
    }

    private var cachedStubPackageUpToDateInfo: CachedStubPackageUpToDateInfo? = null

    /**
     * Returns a pair: (sorted list of stub packages, fetchedRemoteOk)
     * Sorted so that installed first, then by mpyVersion desc, then name A→Z, then variant A→Z.
     */
    fun getStubPackages(): Pair<List<StubPackage>, Boolean> {
        val remoteStubPackages = getRemoteStubPackages()

        val stubPackages = mutableListOf<StubPackage>()
        val installedStubPackages = getInstalledStubPackages(remoteStubPackages)
        stubPackages.addAll(installedStubPackages)

        remoteStubPackages.forEach { remotePackage ->
            val remoteFullName = "${remotePackage.name}_${remotePackage.mpyVersion}"
            val isAlreadyInstalled = installedStubPackages.any { installed ->
                "${installed.name}_${installed.mpyVersion}" == remoteFullName
            }
            if (!isAlreadyInstalled) {
                stubPackages.add(
                    StubPackage(
                        remotePackage.name,
                        remotePackage.mpyVersion,
                        remotePackage.port,
                        remotePackage.board,
                        remotePackage.variant,
                        isInstalled = false,
                        isUpToDate = false,
                        remotePackage.exactPackageVersion,
                        remotePackage.exactStdlibVersion
                    )
                )
            }
        }

        val sorted = sortStubPackages(stubPackages)
        return Pair(sorted, remoteStubPackages.isNotEmpty())
    }

    private fun getRemoteStubPackages(): List<RemoteStubPackage> {
        val url =
            "https://raw.githubusercontent.com/lukaskremla/micropython-tools-jetbrains/refs/heads/main/data/micropython_stubs.json"
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()

        val remoteStubsJsonContent = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
                .body()
        } catch (_: Throwable) {
            return emptyList()
        }

        // Convert to a JSON object
        val mpyStubsJson = MpyStubsJson.fromJson(remoteStubsJsonContent)

        // Ensure the versions match
        if (mpyStubsJson.version != compatibleIndexVersion) {
            Notifications.Bus.notify(
                Notification(
                    MpyBundle.message("notification.group.name"),
                    MpyBundle.message("stub.service.error.remote.packages.incompatible"),
                    NotificationType.ERROR
                ), project
            )

            return emptyList()
        }

        return mpyStubsJson.packages
    }

    /**
     * Gets installed stub packages and possibly checks if they're up to date.
     *
     *
     * @param remoteStubPackages Optional parameter, a list of remote stub packages,
     * if passed it also checks if the stub packages are up to date
     *
     * @return A list of installed stub packages
     */
    private fun getInstalledStubPackages(remoteStubPackages: List<RemoteStubPackage>?): List<StubPackage> {
        val localStubPackagePaths = MpyPaths.stubBaseDir.toFile().listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it }
            ?.map { it.name }
            ?.map {
                val index = it.lastIndexOf('_') // The last underscore separates package name and mpy version
                if (index != -1) it.substring(0, index) to it.substring(index + 1)
                else it to ""  // fallback if there's no underscore
            }
            ?: emptyList()

        return localStubPackagePaths.mapNotNull { (name, mpyVersion) ->
            val (stubPackageMetadata, exactPackageVersion, exactStdlibVersion) = try {
                // Get stub package path
                val stubPackagePath = MpyPaths.stubBaseDir.resolve("${name}_$mpyVersion")

                // Get metadata json content
                val metadataContent = stubPackagePath.resolve(STUB_PACKAGE_METADATA_FILE_NAME).readText()

                // Format the stub package metadata json
                val stubPackageMetadata = StubPackageMetadata.fromJson(metadataContent)

                // Get exact package version
                val exactPackageVersion = extractVersionFromDistInfo(stubPackagePath, name)

                // Get exact stdlib version
                val exactStdlibVersion = extractVersionFromDistInfo(stubPackagePath, "micropython_stdlib_stubs")

                Triple(stubPackageMetadata, exactPackageVersion, exactStdlibVersion)
            } catch (_: Throwable) {
                return@mapNotNull null
            }

            val isUpToDate = if (remoteStubPackages != null) {
                isUpToDate(
                    name,
                    mpyVersion,
                    exactPackageVersion,
                    exactStdlibVersion,
                    remoteStubPackages
                )
            } else {
                // Fallback to true
                true
            }

            StubPackage(
                name,
                mpyVersion,
                stubPackageMetadata.port,
                stubPackageMetadata.board,
                stubPackageMetadata.variant,
                isInstalled = true,
                isUpToDate = isUpToDate,
                exactPackageVersion = exactPackageVersion,
                exactStdlibVersion = exactStdlibVersion
            )
        }
    }

    /**
     * Extracts the package version from a Python package's .dist-info directory name.
     *
     * Searches for a directory matching the pattern `{packageName}-{version}.dist-info`
     * within the stub package path and extracts the version string.
     *
     * @param stubPackagePath The path to the stub package directory containing the .dist-info folder
     * @param packageName The name of the Python package to look for (e.g., "micropython_rp2_stubs")
     * @return The extracted version string (e.g., "1.23.0.post1")
     * @throws NullPointerException if no matching .dist-info directory is found
     */
    private fun extractVersionFromDistInfo(stubPackagePath: Path, packageName: String): String {
        val stubPackageDirChildren = stubPackagePath.toFile().listFiles()

        // Dist info uses "_" characters instead of "-"
        val packageNameToCheck = packageName.replace("-", "_")

        // Find the dist info dir
        val packageDistInfoDir = stubPackageDirChildren
            .find { it.name.startsWith(packageNameToCheck) && it.name.endsWith(PYTHON_PACKAGE_DIST_INFO_SUFFIX) }

        // Get the actual version from the directory's name
        return packageDistInfoDir!!.name
            .substringAfter("-")
            .removeSuffix(PYTHON_PACKAGE_DIST_INFO_SUFFIX)
    }

    suspend fun installStubPackages(selected: List<StubPackage>) {
        val remoteStubPackages = getRemoteStubPackages()

        // Every package has board + stdlib variants
        val progressSize = selected.size * 2

        reportSequentialProgress(progressSize) { reporter ->
            selected.forEach {
                installStubPackage(reporter, it, remoteStubPackages)
            }
        }
    }

    private suspend fun installStubPackage(
        reporter: SequentialProgressReporter,
        stubPackage: StubPackage,
        remoteStubPackages: List<RemoteStubPackage>
    ) {
        // Validate the python interpreter
        val interpreterValidationResult = pythonService.checkInterpreterValid()

        if (interpreterValidationResult != ValidationResult.OK) {
            throw RuntimeException(interpreterValidationResult.errorMessage)
        }

        // Find the matching remote stub package
        val remoteStubPackage = remoteStubPackages
            .find { it.name == stubPackage.name && it.mpyVersion == stubPackage.mpyVersion }
            ?: throw RuntimeException(
                MpyBundle.message(
                    "stub.service.error.no.matching.remote",
                    stubPackage.name,
                    stubPackage.mpyVersion
                )
            )

        // Generate the target path of the stub package's folder
        val targetPath = MpyPaths.stubBaseDir.resolve("${stubPackage.name}_${stubPackage.mpyVersion}")

        // Install the package stubs, clean the directory, dependencies (stdlib) are handled separately
        pythonService.installPackage(
            reporter,
            "${stubPackage.name}==${remoteStubPackage.exactPackageVersion}",
            targetPath.absolutePathString(),
            false,
            cleanTargetDir = true
        )

        // Install the stdlib stubs, the directory was already cleand before, avoid cleaning it
        // If any deps exist, there's no harm in downloading them
        pythonService.installPackage(
            reporter,
            "${MpyPaths.STDLIB_STUB_PACKAGE_NAME}==${remoteStubPackage.exactStdlibVersion}",
            targetPath.absolutePathString(),
            true,
            cleanTargetDir = false
        )

        // Create metadata payload
        val json = Json { prettyPrint = true }
        val payload = StubPackageMetadata(
            port = stubPackage.port,
            board = stubPackage.board,
            variant = stubPackage.variant
        )

        // Prep the content and file path
        val fileContent = json.encodeToString(payload)
        val targetFile = targetPath.resolve(STUB_PACKAGE_METADATA_FILE_NAME)

        // Write the metadata file
        targetFile.writeText(fileContent)
    }

    fun delete(stubPackage: StubPackage) =
        MpyPaths.stubBaseDir.resolve("${stubPackage.name}_${stubPackage.mpyVersion}").delete(true)

    /**
     * Method for checking if a selected stub package is up to date.
     *
     * @return Returns true if the remote stub package can't be retrieved (for example due to no network connection)
     */
    private fun isUpToDate(
        name: String,
        mpyVersion: String,
        exactPackageVersion: String,
        exactStdlibVersion: String,
        remoteStubPackages: List<RemoteStubPackage>
    ): Boolean {
        val remoteStubPackage = remoteStubPackages
            .find { it.name == name && it.mpyVersion == mpyVersion } ?: return true // Fallback to true

        val isUpToDate = exactPackageVersion == remoteStubPackage.exactPackageVersion &&
                exactStdlibVersion == remoteStubPackage.exactStdlibVersion

        return isUpToDate
    }

    private fun sortStubPackages(pkgs: List<StubPackage>): List<StubPackage> {
        return pkgs.sortedWith(
            compareBy<StubPackage> { !it.isInstalled } // installed first
                .thenComparator { a, b -> compareVersionsDesc(a.mpyVersion, b.mpyVersion) } // newest version first
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name } // name A→Z
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.variant } // variant A→Z
        )
    }

    private fun versionKey(v: String): IntArray {
        val base = v.substringBefore(".post")
        val post = v.substringAfter(".post", "").toIntOrNull()
        val nums = base.split('.').map { it.toIntOrNull() ?: 0 }
        val major = nums.getOrNull(0) ?: 0
        val minor = nums.getOrNull(1) ?: 0
        val patch = nums.getOrNull(2) ?: 0
        return intArrayOf(major, minor, patch, if (post != null) 1 else 0, post ?: 0)
    }

    private fun compareVersions(a: String, b: String): Int {
        val ka = versionKey(a)
        val kb = versionKey(b)
        for (i in 0 until maxOf(ka.size, kb.size)) {
            val x = ka.getOrNull(i) ?: 0
            val y = kb.getOrNull(i) ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun compareVersionsDesc(a: String, b: String): Int = -compareVersions(a, b)

    fun checkStubPackageValid(): ValidationResult {
        // Only perform the check if stubs are enabled
        if (!settings.state.areStubsEnabled) return ValidationResult.OK

        // Get the active stub package's name and version string
        val activeStubsPackageName = getSelectedStubPackageName()

        // Try to find the stub package
        val stubPackage = getInstalledStubPackages(null)
            .find { "${it.name}_${it.mpyVersion}" == activeStubsPackageName }

        // Retrieve the cached to a local variable
        var cachedStubPackageUpToDateInfo = this.cachedStubPackageUpToDateInfo

        // Ensure the cache info is valid
        val isCachedInfoValid = stubPackage != null &&
                cachedStubPackageUpToDateInfo != null &&
                cachedStubPackageUpToDateInfo.name == stubPackage.name &&
                cachedStubPackageUpToDateInfo.mpyVersion == stubPackage.mpyVersion &&
                cachedStubPackageUpToDateInfo.exactPackageVersion == stubPackage.exactPackageVersion &&
                cachedStubPackageUpToDateInfo.exactStdlibVersion == stubPackage.exactStdlibVersion &&
                cachedStubPackageUpToDateInfo.timeStamp + 3600L > System.currentTimeMillis() / 1000

        var remoteStubPackages = emptyList<RemoteStubPackage>()

        val isUpToDate = when {
            // Default state is up-to-date if verification fails
            stubPackage == null -> true

            // Re-do cache
            !isCachedInfoValid -> {
                // Get the latest index of remote stub packages
                remoteStubPackages = getRemoteStubPackages()

                // Check if the stub package is valid
                val newIsUpToDate = isUpToDate(
                    stubPackage.name,
                    stubPackage.mpyVersion,
                    stubPackage.exactPackageVersion,
                    stubPackage.exactStdlibVersion,
                    remoteStubPackages
                )

                // Create a new cache
                cachedStubPackageUpToDateInfo = CachedStubPackageUpToDateInfo(
                    stubPackage.name,
                    stubPackage.mpyVersion,
                    newIsUpToDate,
                    stubPackage.exactPackageVersion,
                    stubPackage.exactStdlibVersion,
                    System.currentTimeMillis() / 1000
                )

                // Save cached info
                this.cachedStubPackageUpToDateInfo = cachedStubPackageUpToDateInfo

                // Return actual isUpToDate state
                newIsUpToDate
            }

            // If package was found and cache is valid, use it
            else -> cachedStubPackageUpToDateInfo.isUpToDate
        }

        val stubValidationText = when {
            activeStubsPackageName.isBlank() -> MpyBundle.message("stub.service.validation.no.package")

            stubPackage == null -> MpyBundle.message("stub.service.validation.invalid.package")

            !isUpToDate -> MpyBundle.message("stub.service.validation.pending.update")

            // Nothing is invalid, no problem to report to the user
            else -> return ValidationResult.OK
        }

        val fix = if (!isUpToDate && stubPackage != null) {
            object :
                FacetConfigurationQuickFix(MpyBundle.message("stub.service.validation.install.update")) {
                override fun run(place: JComponent?) {
                    ApplicationManager.getApplication().invokeLater {
                        runWithModalProgressBlocking(
                            project,
                            MpyBundle.message("configurable.progress.installing.stub.packages.title")
                        ) {
                            reportSequentialProgress(1) { reporter ->
                                try {
                                    installStubPackage(reporter, stubPackage, remoteStubPackages)
                                } catch (e: Throwable) {
                                    Notifications.Bus.notify(
                                        Notification(
                                            MpyBundle.message("notification.group.name"),
                                            MpyBundle.message(
                                                "stub.service.error.update.failed.notification",
                                                e.localizedMessage
                                            ),
                                            NotificationType.ERROR
                                        ), project
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            object :
                FacetConfigurationQuickFix(MpyBundle.message("stub.service.validation.change.button.settings")) {
                override fun run(place: JComponent?) {
                    ApplicationManager.getApplication().invokeLater {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java)
                    }
                }
            }
        }

        return ValidationResult(stubValidationText, fix)
    }

    fun getSelectedStubPackageName(): String {
        val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        return projectLibraryTable.modifiableModel.libraries.find { it.name == LIBRARY_NAME }?.modifiableModel?.getFiles(
            OrderRootType.CLASSES
        )?.firstOrNull()?.name
            ?: ""
    }

    fun updateLibrary(newStubPackage: String? = null) {
        removeAllMpyLibraries()

        if (!newStubPackage.isNullOrBlank()) addMpyLibrary(newStubPackage)
    }

    private fun addMpyLibrary(newStubPackageName: String) {
        DumbService.getInstance(project).smartInvokeLater {
            ApplicationManager.getApplication().runWriteAction {
                // Try to find the stub package
                val stubPackage = getStubPackages().first.find { "${it.name}_${it.mpyVersion}" == newStubPackageName }

                if (!settings.state.areStubsEnabled || stubPackage == null || !stubPackage.isInstalled) {
                    return@runWriteAction
                }

                val modelsProvider = ModifiableModelsProvider.getInstance()
                val projectLibraryModel = modelsProvider.getLibraryTableModifiableModel(project)

                // Create library
                val newLibrary = projectLibraryModel.createLibrary(LIBRARY_NAME, PythonLibraryType.getInstance().kind)
                val libraryModel = newLibrary.modifiableModel

                // Add roots
                val rootUrl = "${MpyPaths.stubBaseDir}/$newStubPackageName"

                val stdlibUrl = "$rootUrl/stdlib"

                val rootFile = LocalFileSystem.getInstance().findFileByPath(rootUrl)
                val stdlibFile = LocalFileSystem.getInstance().findFileByPath(stdlibUrl)

                if (rootFile != null) {
                    libraryModel.addRoot(rootFile, OrderRootType.CLASSES)
                }
                if (stdlibFile != null) {
                    libraryModel.addRoot(stdlibFile, OrderRootType.CLASSES)
                }

                // Commit library changes first
                libraryModel.commit()
                projectLibraryModel.commit()

                // Then add to modules
                for (module in ModuleManager.getInstance(project).modules) {
                    val moduleModel = modelsProvider.getModuleModifiableModel(module)
                    moduleModel.addLibraryEntry(newLibrary)
                    modelsProvider.commitModuleModifiableModel(moduleModel)
                }
            }
        }
    }

    private fun removeAllMpyLibraries() {
        DumbService.getInstance(project).smartInvokeLater {
            ApplicationManager.getApplication().runWriteAction {
                // Clean up library table
                val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                val projectLibraryModel = projectLibraryTable.modifiableModel

                val librariesToRemove = projectLibraryModel.libraries.filter {
                    it.name in LIBRARIES_TO_REMOVE
                }

                librariesToRemove.forEach { library ->
                    projectLibraryModel.removeLibrary(library)
                }

                projectLibraryModel.commit()

                // Clean up order entries
                val moduleManager = ModuleManager.getInstance(project)
                val modules = moduleManager.modules

                modules.forEach { module ->
                    val moduleRootManager = ModuleRootManager.getInstance(module)
                    val moduleRootModel = moduleRootManager.modifiableModel

                    // Find all order entries related to MicroPython libraries
                    val entriesToRemove = moduleRootModel.orderEntries.filter { entry ->
                        entry is LibraryOrderEntry && (entry.libraryName in LIBRARIES_TO_REMOVE)
                    }

                    if (entriesToRemove.isNotEmpty()) {
                        entriesToRemove.forEach { entry ->
                            moduleRootModel.removeOrderEntry(entry)
                        }

                        moduleRootModel.commit()
                    }
                }
            }
        }
    }
}