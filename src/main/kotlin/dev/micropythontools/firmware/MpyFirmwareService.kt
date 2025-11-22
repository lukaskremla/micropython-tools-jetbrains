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
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.pathString

internal data class Board(
    val id: String,
    val name: String,
    val port: String,
    val mcu: String,
    val offset: String,
    val firmwareNameToLinkParts: Map<String, List<String>>
)

@Service(Service.Level.PROJECT)
internal class MpyFirmwareService(private val project: Project) {
    private val client: HttpClient = HttpClient.newHttpClient()
    private var supportedMajorIndexVersion: Int? = null

    init {
        // Extract the bundled board json content as a string
        val jsonString = extractBundledBoardJsonContent()

        val root = JSONObject(jsonString)

        // Set the supported major version string, throw exception if it fails.
        supportedMajorIndexVersion = root.optString("version")?.split(".")?.first()?.toInt()
            ?: throw RuntimeException("Failed to identify version on startup")
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
        println(result)
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
        val links = board.firmwareNameToLinkParts[variantName] ?: return emptyList()
        return links.map { link ->
            // Extract version from filename
            // Example: /resources/firmware/ESP32_GENERIC-20250911-v1.26.1.bin
            // Extract: 20250911-v1.26.1
            val filename = link.substringAfterLast('/')
            val versionPart = filename.substringAfter('-').substringBefore('.')
            versionPart
        }
    }

    fun getCachedBoards(): List<Board> {
        println("Retrieving cached boards")
        val boardJsonFilePath = "${MpyPaths.globalAppDataBase()}/${MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME}"

        // Try to find the existing cached board list, extract the bundled one if it can't be found
        val boardJsonFile = LocalFileSystem.getInstance().findFileByPath(boardJsonFilePath) ?: extractBundledBoardJson()

        val parsed = parseBoardsFromJson(boardJsonFile.readText())

        return parsed
    }

    fun updateCachedBoards() {
        println("Updating boards json")
        // new url: "https://github.com/lukaskremla/micropython-tools-jetbrains/blob/dev_test/firmware_retrieval/data/micropython_boards.json"
        val url = "https://raw.githubusercontent.com/Josverl/micropython-stubs/main/data/stub-packages.json"
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()

        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Throwable) {
            return
        }

        val text = response.body()

        println("Before version check")

        // Verify
        val root = JSONObject(text)
        val newVersion =
            root.optString("version") ?: throw RuntimeException("Failed to identify version of new board json")

        val majorIndexVersionToCheck = supportedMajorIndexVersion
            ?: throw RuntimeException("Supported version info failed to be initialized")

        if (newVersion.split(".").first().toInt() > majorIndexVersionToCheck) {
            throw RuntimeException("Warning: Newest board json's structure is incompatible with this plugin version. Consider updating to get latest board support")
        }

        println("After version check")

        val expectedKeys = listOf("timestamp", "skimmed_ports", "boards")

        if (expectedKeys.any { it !in root.keySet() }) {
            throw RuntimeException("Incorrect structure of new board json")
        }

        writeCachedBoardJson(text)
    }

    private fun parseBoardsFromJson(jsonString: String): List<Board> {
        val root = JSONObject(jsonString)

        val boardsArray = root.optJSONArray("boards")
            ?: throw RuntimeException("Failed to retrieve boards from json")

        val boards = mutableListOf<Board>()
        for (i in 0 until boardsArray.length()) {
            val boardJson = boardsArray.optJSONObject(i) ?: continue

            // Extract fields from each board object
            val id = boardJson.optString("id", "")
            val name = boardJson.optString("name", "")
            val port = boardJson.optString("port", "")
            val mcu = boardJson.optString("mcu", "")
            val offset = boardJson.optString("offset", "")

            // Handle the firmwareNameToLink map
            val firmwareMap = mutableMapOf<String, List<String>>()
            val firmwareJson = boardJson.optJSONObject("firmwareNameToLinkParts")
            if (firmwareJson != null) {
                for (key in firmwareJson.keys()) {
                    val linksArray = firmwareJson.optJSONArray(key)
                    if (linksArray != null) {
                        val links = mutableListOf<String>()
                        for (j in 0 until linksArray.length()) {
                            links.add(linksArray.optString(j, ""))
                        }
                        firmwareMap[key] = links
                    }
                }
            }

            boards.add(Board(id, name, port, mcu, offset, firmwareMap))
        }

        return boards
    }

    private fun extractBundledBoardJsonContent(): String {
        // Path to the bundled json file
        val resourcePath = "/data/${MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME}"

        // Extract the stream
        val stream = MpyScripts::class.java.getResourceAsStream(resourcePath)
            ?: error("Bundled board json file not found: $resourcePath")

        // Read and return the file's text
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun extractBundledBoardJson(): VirtualFile {
        val text = extractBundledBoardJsonContent()

        return writeCachedBoardJson(text)
    }

    private fun writeCachedBoardJson(text: String): VirtualFile {
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
        if (boardJsonFile == null) throw RuntimeException("Failed to write cached board JSON")

        return boardJsonFile
    }
}