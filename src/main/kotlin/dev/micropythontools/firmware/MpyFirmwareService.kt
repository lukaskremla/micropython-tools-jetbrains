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

package dev.micropythontools.firmware

import com.amazon.ion.NullValueException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.core.MpyScripts
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.pathString

internal const val PREVIEW_FIRMWARE_STRING = "preview"

internal class IncompatibleBoardsJsonVersionException(message: String) : Exception(message)

@Serializable
internal data class Board(
    val id: String,
    val name: String,
    val vendor: String,
    val port: String,
    val mcu: String,
    val offset: String,
    val firmwareNameToLinkParts: Map<String, List<String>>
)

@Serializable
internal data class MpyBoardsJson(
    val version: String,
    val timestamp: String,
    val skimmedPorts: List<String>,
    val portToExtension: Map<String, String>,
    val boards: List<Board>
)

@Service(Service.Level.PROJECT)
internal class MpyFirmwareService(private val project: Project) {
    private val client: HttpClient = HttpClient.newHttpClient()
    private var maxSupportedBoardsJsonMajorVersion: Int? = null
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // Extract the bundled board json content as a string
        val extractedJsonString = extractBundledBoardsJsonContent()

        val mpyBoardsJson = parseMpyBoardJson(extractedJsonString)

        // Set the supported major version string, throw exception if it fails
        maxSupportedBoardsJsonMajorVersion = mpyBoardsJson.version.split(".").firstOrNull()?.toIntOrNull()
            ?: throw RuntimeException("Failed to identify max supported micropython_boards.json version on startup")
    }

    /**
     * Gets the firmware file extension for a specific device type/port.
     *
     * @param port The device type/port to get the extension for
     * @return The file extension (e.g., ".bin", ".uf2") or null if not found
     */
    fun getExtensionForPort(port: String): String {
        val mpyBoardsJson = getCachedBoardsJson()

        val extension = mpyBoardsJson.portToExtension[port.toLowerCasePreservingASCIIRules()]
            ?: throw RuntimeException("Port \"${port}\" has no mapped extension")

        return extension
    }

    /**
     * Gets all unique device types (ports) from cached boards.
     *
     * @return Sorted list of device type names (e.g., "esp32", "esp8266", "rp2")
     */
    fun getDeviceTypes(): List<String> = getCachedBoards()
        .map { it.port }
        .distinct()

    /**
     * Gets all unique MCUs for a specific device type/port.
     *
     * @param port The device type/port to filter by
     * @return Sorted list of MCU names for the given port
     */
    fun getMcusForPort(port: String): List<String> = getCachedBoards()
        .filter { it.port == port.toLowerCasePreservingASCIIRules() }
        .map { it.mcu }
        .distinct()

    /**
     * Gets all unique board variants for a specific MCU.
     *
     * @param mcu The MCU to filter by
     * @return Sorted list of board variant names
     */
    fun getBoardsForMcu(mcu: String): List<Board> = getCachedBoards()
        .filter { it.mcu == mcu.toLowerCasePreservingASCIIRules() }

    /**
     * Gets firmware variants for a specific board.
     *
     * @param board The board to get firmware variants for
     * @return List of firmware variant names (e.g., "Standard", "SPIRAM", "OTA")
     */
    fun getFirmwareVariants(board: Board): List<String> {
        val result = board.firmwareNameToLinkParts.keys.toList()
        return result
    }

    /**
     * Gets firmware versions for a specific board and firmware variant.
     * Extracts version numbers from firmware file names.
     *
     * @param board The board
     * @param variantName The firmware variant name
     * @return List of version strings extracted from firmware file names
     */
    fun getFirmwareVersions(board: Board, variantName: String): List<String> {
        val linkParts = board.firmwareNameToLinkParts[variantName] ?: return emptyList()
        return linkParts.map { linkPart ->
            // Remove leading "-"
            var trimmedLinkPart = linkPart.removePrefix("-")

            // Remove the file extension
            trimmedLinkPart = trimmedLinkPart.substringBeforeLast(".")

            // Preview boards are shown more verbosely
            val displayText = if (trimmedLinkPart.contains(PREVIEW_FIRMWARE_STRING)) {
                trimmedLinkPart
            } else {
                trimmedLinkPart.substringAfterLast("-")
            }

            // EXAMPLE: https://micropython.org/resources/firmware/ESP32_GENERIC-20250911-v1.26.1.bin
            //val downloadLink = "https://micropython.org/resources/firmware/${board.id}/$linkPart"

            displayText
        }
    }

    private fun getCachedBoardsJson(): MpyBoardsJson {
        val cachedBoardsJsonFilePath = "${MpyPaths.globalAppDataBase()}/${MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME}"

        // Try to find the existing cached board list
        var cachedBoardsJsonFile = LocalFileSystem.getInstance().findFileByPath(cachedBoardsJsonFilePath)

        try {
            // Use the bundled json if none is cached
            cachedBoardsJsonFile = cachedBoardsJsonFile ?: throw NullValueException("No boards cached, falling back")

            // Extract the file's content
            val cachedBoardsJsonContent = cachedBoardsJsonFile.readText()

            return parseMpyBoardJson(cachedBoardsJsonContent)
        } catch (_: Throwable) {
            val writtenBundledBoardsJson = writeBundledBoardsJson()

            val writtenBundledBoardsJsonContent = writtenBundledBoardsJson.readText()

            return parseMpyBoardJson(writtenBundledBoardsJsonContent)
        }
    }

    fun getCachedBoardsTimestamp(): String {
        val cachedBoardsJson = getCachedBoardsJson()

        // Return just the timestamp
        return cachedBoardsJson.timestamp
    }

    fun getCachedBoards(): List<Board> {
        val cachedBoardsJson = getCachedBoardsJson()

        // Return just the boards
        return cachedBoardsJson.boards
    }

    fun updateCachedBoards() {
        val url =
            "https://raw.githubusercontent.com/lukaskremla/micropython-tools-jetbrains/dev_test/firmware_retrieval/data/micropython_boards.json"
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()

        var remoteBoardsJsonContent: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            remoteBoardsJsonContent = try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                response.body()
            } catch (_: Throwable) {
                null
            }
        }

        remoteBoardsJsonContent ?: throw RuntimeException("Failed to fetch latest JSON data")

        // Verify and validate the new JSON
        val mpyBoardsJson = parseMpyBoardJson(remoteBoardsJsonContent!!)

        // Save the highest supported major version to a local variable and ensure it's initialized
        val maxSupportedMajorVersion = maxSupportedBoardsJsonMajorVersion
            ?: throw RuntimeException("Max supported boards json major version wasn't initialized")

        // Retrieve the major version of the remote json
        val newMajorVersion = mpyBoardsJson.version.split(".").firstOrNull()?.toIntOrNull()
            ?: throw RuntimeException("Failed to identify version of new boards JSON")

        // Ensure the remote json's version is supported
        if (newMajorVersion > maxSupportedMajorVersion) {
            throw IncompatibleBoardsJsonVersionException("Newest board JSON's structure is incompatible with this plugin version. Consider updating to get latest board support")
        }

        writeCachedBoardsJson(remoteBoardsJsonContent!!)
    }

    private fun parseMpyBoardJson(jsonString: String): MpyBoardsJson {
        return try {
            json.decodeFromString<MpyBoardsJson>(jsonString)
        } catch (e: SerializationException) {
            throw RuntimeException("Failed to parse boards from JSON", e)
        }
    }

    private fun extractBundledBoardsJsonContent(): String {
        // Path to the bundled json file
        val resourcePath = "/data/${MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME}"

        // Extract the stream
        val stream = MpyScripts::class.java.getResourceAsStream(resourcePath)
            ?: throw RuntimeException("Bundled boards JSON file not found: $resourcePath")

        // Read and return the file's text
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun writeBundledBoardsJson(): VirtualFile {
        val bundledBoardsJsonContent = extractBundledBoardsJsonContent()

        return writeCachedBoardsJson(bundledBoardsJsonContent)
    }

    private fun writeCachedBoardsJson(text: String): VirtualFile {
        var boardJsonFile: VirtualFile? = null

        // Retrieve the app data dir
        val appDataDir = LocalFileSystem
            .getInstance()
            .refreshAndFindFileByPath(MpyPaths.globalAppDataBase().pathString)
            ?: throw RuntimeException("Failed to find the plugin's data directory")

        // Ensure synchronization on EDT
        ApplicationManager.getApplication().invokeAndWait {
            runWriteAction {
                // Find the existing file or create it
                boardJsonFile = appDataDir.findChild(MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME)
                    ?: appDataDir.createChildData(this, MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME)

                // Write the new content, flushing the old one
                VfsUtil.saveText(boardJsonFile, text)

                // Refresh the file in VFS
                boardJsonFile.refresh(false, false)
            }
        }

        // Ensure the file was created
        if (boardJsonFile == null) throw RuntimeException("Failed to write cached boards JSON")

        return boardJsonFile
    }
}