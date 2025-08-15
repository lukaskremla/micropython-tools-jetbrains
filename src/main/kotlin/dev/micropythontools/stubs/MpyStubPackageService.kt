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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
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
import com.jetbrains.python.library.PythonLibraryType
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.settings.PLUGIN_ID
import dev.micropythontools.settings.stubsPath
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import javax.swing.JComponent

internal data class StubPackage(
    val name: String,
    val version: String,
    val isDownloaded: Boolean,
    val boardVersion: String,
    val stdlibVersion: String,
    val path: Path
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

    private val stubBaseDir: Path = PathManager.getSystemDir().resolve(PLUGIN_ID)

    private val settings = project.service<MpySettingsService>()
    private val client: HttpClient = HttpClient.newHttpClient()

    fun getAvailableLocalStubs(): List<Pair<String, String>> {
        return stubBaseDir.toFile().listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it }
            ?.map { it.name }
            ?.map {
                val index = it.lastIndexOf('_')
                if (index != -1) it.substring(0, index) to it.substring(index + 1)
                else it to ""  // fallback if there's no underscore
            }
            ?: emptyList()
    }

    fun getAvailableStubs(): List<String> {
        return File(MpyPaths.stubsPath).listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it }
            ?.map { it.name }
            ?: emptyList()
    }

    fun getExistingStubPackage(): String {
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
        val activeStubsPackage = getExistingStubPackage()

        var stubValidationText: String? = null

        if (settings.state.areStubsEnabled) {
            if (activeStubsPackage.isBlank()) {
                stubValidationText = "No stub package selected"
            } else if (activeStubsPackage.isNotBlank() && !getAvailableStubs().contains(activeStubsPackage)) {
                stubValidationText = "Invalid stub package selected"
            }
        }

        return if (stubValidationText != null) {
            return ValidationResult(
                stubValidationText,
                object : FacetConfigurationQuickFix("Change Settings") {
                    override fun run(place: JComponent?) {
                        ApplicationManager.getApplication().invokeLater {
                            ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java)
                        }
                    }
                }
            )
        } else {
            ValidationResult.OK
        }
    }

    private fun addMpyLibrary(newStubPackage: String) {
        DumbService.getInstance(project).smartInvokeLater {
            ApplicationManager.getApplication().runWriteAction {
                if (!settings.state.areStubsEnabled || !getAvailableStubs().contains(newStubPackage)) {
                    return@runWriteAction
                }

                val modelsProvider = ModifiableModelsProvider.getInstance()
                val projectLibraryModel = modelsProvider.getLibraryTableModifiableModel(project)

                // Create library
                val newLibrary = projectLibraryModel.createLibrary(LIBRARY_NAME, PythonLibraryType.getInstance().kind)
                val libraryModel = newLibrary.modifiableModel

                // Add roots
                val rootUrl = "${MpyPaths.stubsPath}/$newStubPackage"
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

    fun getStubPackages(): List<StubPackage> {
        val availablePackages = try {
            fetchStubPackageList()
        } catch (_: Throwable) {
            getAvailableLocalStubs()
        }

        println(availablePackages)

        val stubPackages = availablePackages.map { pkg ->
            StubPackage(
                pkg.first,
                pkg.second,
                isInstalled(pkg.first, pkg.second)
            )
        }

        for (pkg in availablePackages) {

        }

        return emptyList()
    }

    fun fetchStubPackageList(): List<Pair<String, String>> {
        val url = "https://raw.githubusercontent.com/Josverl/micropython-stubs/main/data/stub-packages.json"
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        val result = mutableListOf<Pair<String, String>>()
        val root = JSONObject(response.body())
        val packages = root.optJSONArray("packages") ?: return emptyList()

        for (i in 0 until packages.length()) {
            val entry = packages.getJSONArray(i)
            result.add(entry.getString(0) to entry.getString(1))
        }
        return result
    }

    fun isInstalled(packageName: String, version: String): Boolean {
        return Files.exists(stubBaseDir.resolve("${packageName}_$version"))
    }

    fun install(packageName: String, version: String): Boolean {
        val downloadUrl = getPackageDownloadUrl(packageName, version) ?: return false
        val archiveBytes = client.send(
            HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).body()

        val archivePath = Files.createTempFile("$packageName-$version", ".zip")
        Files.write(archivePath, archiveBytes)

        val targetDir = stubBaseDir.resolve("${packageName}_$version")
        unzip(archivePath, targetDir)

        // Refresh VFS
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetDir.toFile())?.refresh(true, true)

        return true
    }

    private fun getPackageDownloadUrl(packageName: String, version: String): String? {
        val url = "https://pypi.org/pypi/$packageName/$version/json"
        val response = client.send(
            HttpRequest.newBuilder().uri(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        val releases = JSONObject(response.body()).getJSONObject("releases")
        val files = releases.optJSONArray(version) ?: return null

        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val type = file.getString("packagetype")
            val urlValue = file.getString("url")
            if (type == "bdist_wheel" || type == "sdist") {
                return urlValue
            }
        }

        return null
    }

    private fun unzip(zipFile: Path, targetDir: Path) {
        Files.createDirectories(targetDir)
        ZipInputStream(Files.newInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outPath = targetDir.resolve(entry.name).normalize()
                if (!outPath.startsWith(targetDir)) throw IOException("Zip traversal attack detected!")

                if (entry.isDirectory) {
                    Files.createDirectories(outPath)
                } else {
                    Files.createDirectories(outPath.parent)
                    Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING)
                }
                entry = zis.nextEntry
            }
        }
    }
}