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
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModifiableModelsProvider
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.readText
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.util.progress.reportRawProgress
import com.jetbrains.python.library.PythonLibraryType
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.core.MpyPythonInterpreterService
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.absolutePathString
import kotlin.io.path.pathString

internal data class StubPackage(
    val name: String,
    val mpyVersion: String,
    val family: String,
    val board: String,
    val variant: String,
    val isInstalled: Boolean,
    val isUpToDate: Boolean,
    val boardStubVersion: String,
    val stdlibStubVersion: String
)

private data class CachedStubPackageUpToDateInfo(
    val name: String,
    val mpyVersion: String,
    val isUpToDate: Boolean,
    val boardStubVersion: String,
    val stdlibStubVersion: String,
    val timeStamp: Long
)

private data class RemoteStubPackage(
    val name: String,
    val mpyVersion: String,
    val family: String,
    val board: String,
    val variant: String
)

@Serializable
private data class StubPackageJson(
    val family: String,
    val board: String,
    val variant: String,
    val boardStubVersion: String,
    val stdlibStubVersion: String
)

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

    private var cachedStubPackageUpToDateInfo: CachedStubPackageUpToDateInfo? = null
    private val settings = project.service<MpySettingsService>()
    private val client: HttpClient = HttpClient.newHttpClient()

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

    fun checkStubPackageValidity(): ValidationResult {
        val activeStubsPackageName = getSelectedStubPackageName()

        var stubValidationText: String? = null

        // Try to find the stub package
        val stubPackage =
            getInstalledStubPackages(false).find { "${it.name}_${it.mpyVersion}" == activeStubsPackageName }

        var cachedStubPackageUpToDateInfo = this.cachedStubPackageUpToDateInfo

        val isCachedInfoValid = stubPackage != null &&
                cachedStubPackageUpToDateInfo != null &&
                cachedStubPackageUpToDateInfo.name == stubPackage.name &&
                cachedStubPackageUpToDateInfo.mpyVersion == stubPackage.mpyVersion &&
                cachedStubPackageUpToDateInfo.boardStubVersion == stubPackage.boardStubVersion &&
                cachedStubPackageUpToDateInfo.stdlibStubVersion == stubPackage.stdlibStubVersion &&
                cachedStubPackageUpToDateInfo.timeStamp + 3600L > System.currentTimeMillis() / 1000

        val isUpToDate =
            if (stubPackage == null) {
                true // Default state is up-to-date if verification fails
            } else if (!isCachedInfoValid) {
                val newIsUpToDate = isUpToDate(
                    stubPackage.name,
                    stubPackage.mpyVersion,
                    stubPackage.boardStubVersion,
                    stubPackage.stdlibStubVersion
                )

                cachedStubPackageUpToDateInfo = CachedStubPackageUpToDateInfo(
                    stubPackage.name,
                    stubPackage.mpyVersion,
                    newIsUpToDate,
                    stubPackage.boardStubVersion,
                    stubPackage.stdlibStubVersion,
                    System.currentTimeMillis() / 1000
                )

                this.cachedStubPackageUpToDateInfo = cachedStubPackageUpToDateInfo
                newIsUpToDate
            } else {
                cachedStubPackageUpToDateInfo.isUpToDate
            }

        var isUpdateFix = false

        if (settings.state.areStubsEnabled) {
            if (activeStubsPackageName.isBlank()) {
                stubValidationText = MpyBundle.message("stub.service.validation.no.package")
            } else if (stubPackage == null) {
                stubValidationText = MpyBundle.message("stub.service.validation.invalid.package")
            } else if (!isUpToDate) {
                stubValidationText = MpyBundle.message("stub.service.validation.pending.update")
                isUpdateFix = true
            }
        }

        return if (stubValidationText != null) {
            val fix = if (isUpdateFix) {
                object :
                    FacetConfigurationQuickFix(MpyBundle.message("stub.service.validation.install.update")) {
                    override fun run(place: JComponent?) {
                        ApplicationManager.getApplication().invokeLater {
                            runWithModalProgressBlocking(
                                project,
                                MpyBundle.message("configurable.progress.installing.stub.packages.title")
                            ) {
                                reportRawProgress { reporter ->
                                    reporter.text(MpyBundle.message("stub.service.progress.text"))
                                    reporter.details("${stubPackage!!.name}_${stubPackage.mpyVersion}")
                                    try {
                                        install(stubPackage)
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
        } else {
            ValidationResult.OK
        }
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

    /**
     * Returns a pair: (sorted list of stub packages, fetchedRemoteOk)
     * Sorted so that installed first, then by mpyVersion desc, then name A→Z, then variant A→Z.
     */
    fun getStubPackages(): Pair<List<StubPackage>, Boolean> {
        val remoteStubPackages = getRemoteStubPackages()

        val stubPackages = mutableListOf<StubPackage>()
        val installedStubPackages = getInstalledStubPackages()
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
                        remotePackage.family,
                        remotePackage.board,
                        remotePackage.variant,
                        isInstalled = false,
                        isUpToDate = false,
                        boardStubVersion = "",
                        stdlibStubVersion = ""
                    )
                )
            }
        }

        val sorted = sortStubPackages(stubPackages)
        return Pair(sorted, remoteStubPackages.isNotEmpty())
    }

    private fun getLocalStubPackage(
        packageName: String,
        mpyVersion: String,
        checkUpToDate: Boolean = false
    ): StubPackage? {
        val stubPackage = MpyPaths.stubBaseDir.resolve("${packageName}_$mpyVersion")

        // Ensure the stub package is valid by searching for machine.pyi in it
        val machinePyi = stubPackage.resolve(MpyPaths.STUB_PACKAGE_MACHINE_NAME)
        LocalFileSystem.getInstance().findFileByPath(machinePyi.pathString) ?: return null

        val jsonPath = stubPackage.resolve(MpyPaths.STUB_PACKAGE_JSON_FILE_NAME)

        val jsonFile = LocalFileSystem.getInstance().findFileByPath(jsonPath.pathString) ?: return null
        val jsonString = jsonFile.readText()

        val jsonElement = try {
            Json.parseToJsonElement(jsonString)
        } catch (_: SerializationException) {
            return null
        }

        val jsonObject = jsonElement.jsonObject

        val boardStubVersion = jsonObject["boardStubVersion"]?.jsonPrimitive?.content ?: return null
        val stdlibStubVersion = jsonObject["stdlibStubVersion"]?.jsonPrimitive?.content ?: return null

        val isUpToDate =
            if (checkUpToDate) isUpToDate(packageName, mpyVersion, boardStubVersion, stdlibStubVersion) else true

        return StubPackage(
            packageName,
            mpyVersion,
            jsonObject["family"]?.jsonPrimitive?.content ?: return null,
            jsonObject["board"]?.jsonPrimitive?.content ?: return null,
            jsonObject["variant"]?.jsonPrimitive?.content ?: return null,
            true,
            isUpToDate,
            boardStubVersion,
            stdlibStubVersion
        )
    }

    private fun getInstalledStubPackages(checkUpToDate: Boolean = false): List<StubPackage> {
        val localStubPackagePaths = MpyPaths.stubBaseDir.toFile().listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it }
            ?.map { it.name }
            ?.map {
                val index = it.lastIndexOf('_')
                if (index != -1) it.substring(0, index) to it.substring(index + 1)
                else it to ""  // fallback if there's no underscore
            }
            ?: emptyList()

        return localStubPackagePaths.mapNotNull {
            getLocalStubPackage(it.first, it.second, checkUpToDate)
        }
    }

    private fun getRemoteStubPackages(): List<RemoteStubPackage> {
        val url = "https://raw.githubusercontent.com/Josverl/micropython-stubs/main/data/stub-packages.json"
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()

        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Throwable) {
            return emptyList()
        }

        val result = mutableListOf<RemoteStubPackage>()
        val root = JSONObject(response.body())
        val packages = root.optJSONArray("packages") ?: return emptyList()

        for (i in 0 until packages.length()) {
            val entry = packages.getJSONArray(i)

            val remoteStubPackage = RemoteStubPackage(
                entry.getString(0),
                entry.getString(1),
                entry.getString(2),
                entry.getString(3),
                entry.getString(4),
            )

            result.add(remoteStubPackage)
        }
        return result
    }

    /**
     * Method for installing or updating stub packages
     */
    fun install(stubPackage: StubPackage) {
        val (resolvedBoardVersion, _) =
            getExactVersionAndUrl(stubPackage.name, stubPackage.mpyVersion) ?: return
        val (resolvedStdlibVersion, _) = if (isAtLeast123(stubPackage.mpyVersion)) {
            getExactVersionAndUrl(MpyPaths.STDLIB_STUB_PACKAGE_NAME, stubPackage.mpyVersion) ?: return
        } else {
            "" to ""
        }

        val app = ApplicationManager.getApplication()

        val targetDir = MpyPaths.stubBaseDir.resolve("${stubPackage.name}_${stubPackage.mpyVersion}")

        // Ensure target dir is clean
        try {
            app.invokeAndWait {
                runWriteAction {
                    LocalFileSystem.getInstance().findFileByPath(targetDir.pathString)?.delete(this)
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Throwable) {
            // pass
        }

        ensureDirs(MpyPaths.stubBaseDir, targetDir)

        val pythonService = project.service<MpyPythonInterpreterService>()

        // board
        val boardCommand = listOf(
            "-m", "pip", "install",
            "${stubPackage.name}~=$resolvedBoardVersion",
            "--target", targetDir.absolutePathString(),
            "--disable-pip-version-check",
            "--quiet",
            "--upgrade"
        ).toMutableList()

        if (isAtLeast123(stubPackage.mpyVersion)) boardCommand.add("--no-deps")

        val validationResult = pythonService.checkInterpreterValid()

        if (validationResult != ValidationResult.OK) {
            throw RuntimeException(validationResult.errorMessage)
        }

        pythonService.runPythonCode(boardCommand)

        if (isAtLeast123(stubPackage.mpyVersion)) {
            // stdlib
            val stdlibCommand = listOf(
                "-m", "pip", "install",
                "${MpyPaths.STDLIB_STUB_PACKAGE_NAME}~=$resolvedStdlibVersion",
                "--target", targetDir.absolutePathString(),
                "--no-deps",
                "--disable-pip-version-check",
                "--quiet",
                "--upgrade"
            )

            pythonService.runPythonCode(stdlibCommand)
        }

        // metadata
        val json = Json { prettyPrint = true }
        val payload = StubPackageJson(
            family = stubPackage.family,
            board = stubPackage.board,
            variant = stubPackage.variant,
            boardStubVersion = resolvedBoardVersion,
            stdlibStubVersion = resolvedStdlibVersion
        )

        val lfs = LocalFileSystem.getInstance()
        val dirVf = lfs.refreshAndFindFileByPath(targetDir.pathString) ?: return

        val text = json.encodeToString(payload)

        app.invokeAndWait {
            runWriteAction {
                val fileVf = dirVf.findChild(MpyPaths.STUB_PACKAGE_JSON_FILE_NAME)
                    ?: dirVf.createChildData(this, MpyPaths.STUB_PACKAGE_JSON_FILE_NAME)

                com.intellij.openapi.vfs.VfsUtil.saveText(fileVf, text)
                fileVf.refresh(false, false)
            }
        }

        LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(targetDir.toFile())
            ?.refresh(true, true)

        return
    }

    fun delete(stubPackage: StubPackage) {
        val targetDir = MpyPaths.stubBaseDir.resolve("${stubPackage.name}_${stubPackage.mpyVersion}")
        runWriteAction {
            StandardFileSystems.local().findFileByPath(targetDir.absolutePathString())
                ?.delete(MpyStubPackageService)
        }
    }

    /**
     * Method for checking if a selected stub package is up to date.
     *
     * @return Returns true if the remote stub package can't be retrieved (for example due to no network connection)
     */
    fun isUpToDate(name: String, mpyVersion: String, boardStubVersion: String, stdlibStubVersion: String): Boolean {
        val (resolvedBoardVersion, _) =
            getExactVersionAndUrl(name, mpyVersion) ?: return true
        val (resolvedStdlibVersion, _) =
            getExactVersionAndUrl(MpyPaths.STDLIB_STUB_PACKAGE_NAME, mpyVersion) ?: return true

        return boardStubVersion == resolvedBoardVersion && stdlibStubVersion == resolvedStdlibVersion
    }

    private fun ensureDirs(vararg paths: Path) {
        paths.forEach { Files.createDirectories(it) }
    }

    private fun getExactVersionAndUrl(packageName: String, baseVersion: String): Pair<String, String>? {
        // Query all releases for the package
        val metaUrl = "https://pypi.org/pypi/$packageName/json"
        val response = client.send(
            HttpRequest.newBuilder().uri(URI.create(metaUrl)).build(),
            HttpResponse.BodyHandlers.ofString()
        )

        val root = JSONObject(response.body())
        val releases = root.getJSONObject("releases")

        // Collect candidates that are exactly baseVersion OR baseVersion.postN
        val candidates = mutableListOf<String>()
        val keys = releases.keys()
        while (keys.hasNext()) {
            val version = keys.next()
            if (version == baseVersion || version.startsWith("$baseVersion.post")) {
                candidates.add(version)
            }
        }
        if (candidates.isEmpty()) return null

        // Pick the highest post if present; otherwise the plain baseVersion
        fun postNum(version: String): Int = if (version.startsWith("$baseVersion.post")) {
            version.substringAfter("$baseVersion.post").toIntOrNull() ?: 0
        } else -1 // plain baseVersion ranks below any .postN

        val chosenVersion = candidates.maxWith(compareBy<String> { postNum(it) }.thenBy { it })

        // Choose a file URL from that version (wheel preferred, else sdist)
        val files = releases.optJSONArray(chosenVersion) ?: return null
        var url: String? = null
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val type = f.getString("packagetype")
            val u = f.getString("url")
            if (type == "bdist_wheel") {
                url = u; break
            }
            if (type == "sdist" && url == null) url = u
        }
        return if (url != null) chosenVersion to url else null
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

    fun compareVersions(a: String, b: String): Int {
        val ka = versionKey(a)
        val kb = versionKey(b)
        for (i in 0 until maxOf(ka.size, kb.size)) {
            val x = ka.getOrNull(i) ?: 0
            val y = kb.getOrNull(i) ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun compareVersionsDesc(a: String, b: String): Int = -compareVersions(a, b)

    fun isAtLeast123(version: String): Boolean = compareVersions(version, "1.23.0") >= 0
}