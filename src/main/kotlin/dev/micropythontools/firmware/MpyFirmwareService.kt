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

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.util.progress.RawProgressReporter
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.ui.MpyFileSystemWidget.Companion.formatSize
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile

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
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonString: String): MpyBoardsJson {
            return json.decodeFromString<MpyBoardsJson>(jsonString)
        }
    }
}

@Service(Service.Level.PROJECT)
internal class MpyFirmwareService(private val project: Project) {
    private val client: HttpClient = HttpClient.newHttpClient()

    private var maxSupportedBoardsJsonMajorVersion: Int? = null

    private var cachedBoards: List<Board> = emptyList()
    private var cachedTimestamp: String = ""
    private var cachedPortToExtension: Map<String, String> = emptyMap()

    init {
        // Dynamically loads the max supported boards json version, not requiring hard-coding
        val extractedJsonString = extractBundledBoardsJsonContent()

        val mpyBoardsJson = MpyBoardsJson.fromJson(extractedJsonString)

        // Set the supported major version string, throw exception if it fails
        maxSupportedBoardsJsonMajorVersion = mpyBoardsJson.version.split(".").firstOrNull()?.toIntOrNull()
            ?: throw RuntimeException("Failed to identify max supported micropython_boards.json version on startup")

        // Load the initial cached data state
        loadFromDisk()
    }

    fun downloadFirmwareToTemp(
        reporter: RawProgressReporter,
        board: Board,
        variantName: String,
        version: String
    ): String {
        reporter.text("Downloading MicroPython firmware...")

        // Get the link part for this variant and version
        val linkParts = board.firmwareNameToLinkParts[variantName]
            ?: throw IllegalArgumentException("Firmware variant '$variantName' not found for board '${board.name}'")

        val linkPart = linkParts.find { it.contains(version) }
            ?: throw IllegalArgumentException("Version '$version' not found for variant '$variantName'")

        val downloadLinkPart = board.id + linkPart

        // Construct the full download URL
        val downloadUrl = "https://micropython.org/resources/firmware/$downloadLinkPart"

        reporter.details("Connecting to micropython.org...")

        // First, get the content length with a HEAD request
        val headRequest = HttpRequest.newBuilder()
            .uri(URI.create(downloadUrl))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()

        val contentLength = try {
            val headResponse = client.send(headRequest, HttpResponse.BodyHandlers.discarding())
            headResponse.headers().firstValueAsLong("Content-Length")
                .orElse(-1L)
                .takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        }

        // Download with streaming to track progress
        val request = HttpRequest.newBuilder()
            .uri(URI.create(downloadUrl))
            .build()

        val response = try {
            if (contentLength != null) {
                // Known size - track progress
                client.send(request, HttpResponse.BodyHandlers.ofInputStream()).let { response ->
                    if (response.statusCode() != 200) {
                        throw RuntimeException("Failed to download firmware: HTTP ${response.statusCode()}")
                    }

                    val inputStream = response.body()
                    val outputStream = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    reporter.details("Starting download... (${formatSize(contentLength)})")

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        // Update progress
                        val percentage = (totalBytesRead * 100 / contentLength).toInt()
                        reporter.details(
                            "Downloaded ${formatSize(totalBytesRead)} of ${formatSize(contentLength)} ($percentage%)"
                        )
                        reporter.fraction(totalBytesRead.toDouble() / contentLength)
                    }

                    outputStream.toByteArray()
                }
            } else {
                // Unknown size - just download without progress
                reporter.details("Downloading firmware...")
                client.send(request, HttpResponse.BodyHandlers.ofByteArray()).let { response ->
                    if (response.statusCode() != 200) {
                        throw RuntimeException("Failed to download firmware: HTTP ${response.statusCode()}")
                    }
                    response.body()
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to download firmware from $downloadUrl", e)
        }

        reporter.details("Download complete. Saving to temporary file...")

        // Create a temp file with the correct extension
        val extension = getExtensionForPort(board.port)
        val tempFile = createTempFile(
            prefix = downloadLinkPart,
            suffix = extension
        )

        // Write the firmware data to the temp file
        tempFile.toFile().writeBytes(response)

        reporter.details("Saved to: ${tempFile.fileName}")

        return tempFile.absolutePathString()
    }

    suspend fun flashFirmware(
        reporter: RawProgressReporter,
        port: String,
        pathToFirmware: String,
        mcu: String,
        offset: String,
        eraseFlash: Boolean
    ) {
        val mcuLower = mcu.lowercase()

        when {
            mcuLower.startsWith("esp") -> MpyEspFlasher(project)
            mcuLower.startsWith("stm") -> MpyStmFlasher()
            mcuLower.startsWith("samd") -> MpySamdFlasher(project)
            mcuLower.startsWith("rp2") -> MpyRp2Flasher(project)
            else -> throw RuntimeException("MCU \"$mcu\" not supported by flasher")
        }.flash(reporter, port, pathToFirmware, mcu, offset, eraseFlash)

        // Ensure details are cleaned and don't linger for the following operations
        reporter.details(null)
    }

    fun getCachedBoards(): List<Board> = cachedBoards
    fun getCachedBoardsTimestamp(): String = cachedTimestamp

    /**
     * Gets the firmware file extension for a specific device type/port.
     *
     * @param port The device type/port to get the extension for
     * @return The file extension (e.g., ".bin", ".uf2") or null if not found
     */
    fun getExtensionForPort(port: String): String = cachedPortToExtension[port.toLowerCasePreservingASCIIRules()]
        ?: throw RuntimeException("Port \"${port}\" has no mapped extension")

    /**
     * Gets all unique device types (ports) from cached boards.
     *
     * @return Sorted list of device type names (e.g., "esp32", "esp8266", "rp2")
     */
    fun getDeviceTypes(): List<String> = getCachedBoards()
        .map { it.port }
        .filter {
            it.toLowerCasePreservingASCIIRules().startsWith("esp")
        } //TODO: Remove once more devices are implemented
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

    fun updateCachedBoards() {
        val url =
            "https://raw.githubusercontent.com/lukaskremla/micropython-tools-jetbrains/main/data/micropython_boards.json"
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()

        val remoteBoardsJsonContent = try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.body()
        } catch (_: Throwable) {
            null
        }

        remoteBoardsJsonContent ?: throw RuntimeException("Failed to fetch latest JSON data")

        // Verify and validate the new JSON
        val mpyBoardsJson = MpyBoardsJson.fromJson(remoteBoardsJsonContent)

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

        // Cache the newly retrieved values
        cachedBoards = mpyBoardsJson.boards
        cachedTimestamp = mpyBoardsJson.timestamp
        cachedPortToExtension = mpyBoardsJson.portToExtension

        // Get the cache file to write to
        val cacheFile = MpyPaths.globalAppDataBase()
            .resolve(MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME)
            .toFile()

        // Ensure parent directories exists
        cacheFile.parentFile.mkdirs()

        // Cache the data
        cacheFile.writeText(remoteBoardsJsonContent)
    }

    private fun loadFromDisk() {
        val jsonContent = try {
            // Try to access a newer cached board json
            val cacheFile = MpyPaths.globalAppDataBase().resolve(MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME).toFile()
            if (cacheFile.exists()) cacheFile.readText() else throw Exception("No cache")
        } catch (_: Throwable) {
            // Fall back to bundled resource
            extractBundledBoardsJsonContent()
        }

        val parsedMpyBoardJson = MpyBoardsJson.fromJson(jsonContent)
        cachedBoards = parsedMpyBoardJson.boards
        cachedTimestamp = parsedMpyBoardJson.timestamp
        cachedPortToExtension = parsedMpyBoardJson.portToExtension
    }

    private fun extractBundledBoardsJsonContent(): String =
        javaClass.getResourceAsStream("/data/${MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME}")!!
            .bufferedReader()
            .readText()
}