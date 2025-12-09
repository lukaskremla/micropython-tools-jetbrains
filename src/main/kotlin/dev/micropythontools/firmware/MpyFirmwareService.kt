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
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.platform.util.progress.RawProgressReporter
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.ui.MpyFileSystemWidget.Companion.formatSize
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
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
    val firmwareNameToLinkParts: Map<String, List<String>>
) {
    // Set or not set for ESP board later in the program's flow
    @Transient
    var offset: Int? = null
}


@Serializable
internal data class MpyBoardsJson(
    val version: String,
    val timestamp: String,
    val supportedPorts: List<String>,
    val portToExtension: Map<String, String>,
    val espMcuToOffset: Map<String, Int>,
    val boards: List<Board>
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonString: String): MpyBoardsJson {
            return json.decodeFromString<MpyBoardsJson>(jsonString)
        }
    }
}

@Serializable
private data class BundledFlashingInfo(
    val compatibleIndexVersion: String,
    val supportedPorts: List<String>,
    val portToExtension: Map<String, String>,
    val espMcuToOffset: Map<String, Int>
) {
    companion object {
        fun fromJson(jsonString: String): BundledFlashingInfo {
            return Json.decodeFromString<BundledFlashingInfo>(jsonString)
        }
    }
}

@Service(Service.Level.PROJECT)
internal class MpyFirmwareService(private val project: Project) {
    private val client: HttpClient = HttpClient.newHttpClient()

    val compatibleIndexVersion: String
    val supportedPorts: List<String>
    val portToExtension: Map<String, String>
    val espMcuToOffset: Map<String, Int>

    init {
        val bundledJsonString =
            javaClass.getResourceAsStream("/bundled/${MpyPaths.BUNDLED_FLASHING_INFO_JSON_FILE_NAME}")!!
                .bufferedReader()
                .readText()

        val bundledFlashingInfo = BundledFlashingInfo.fromJson(bundledJsonString)

        compatibleIndexVersion = bundledFlashingInfo.compatibleIndexVersion
        supportedPorts = bundledFlashingInfo.supportedPorts
        portToExtension = bundledFlashingInfo.portToExtension
        espMcuToOffset = bundledFlashingInfo.espMcuToOffset
    }

    suspend fun getMpyBoardsJson(): MpyBoardsJson {
        val url =
            "https://raw.githubusercontent.com/lukaskremla/micropython-tools-jetbrains/main/data/micropython_boards.json"
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()

        val remoteBoardsJsonContent = try {
            withContext(Dispatchers.IO) {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .await()  // Converts CompletableFuture to cancellable suspend
                    .body()
            }
        } catch (_: Throwable) {
            null
        }

        remoteBoardsJsonContent ?: throw RuntimeException("Failed to fetch latest JSON data")

        // Verify and validate the new JSON
        val mpyBoardsJson = MpyBoardsJson.fromJson(remoteBoardsJsonContent)

        // Ensure the versions match
        if (mpyBoardsJson.version != compatibleIndexVersion) {
            throw IncompatibleBoardsJsonVersionException("Newest board JSON's structure is incompatible with this plugin version. A plugin update is required to restore functionality.")
        }

        return mpyBoardsJson
    }

    /**
     * Gets all unique MCUs for a specific device type/port.
     *
     * @param port The device type/port to filter by
     * @return Sorted list of MCU names for the given port
     */
    fun getMcusForPort(port: String, boards: List<Board>): List<String> {
        val foundMcus = boards.filter { it.port == port.toLowerCasePreservingASCIIRules() }
            .map { it.mcu }
            .distinct()

        return if (foundMcus.isEmpty() && port.startsWith("esp", ignoreCase = true)) {
            espMcuToOffset.keys.toList()
        } else {
            foundMcus
        }
    }

    /**
     * Gets all unique board variants for a specific MCU.
     *
     * @param mcu The MCU to filter by
     * @return Sorted list of board variant names
     */
    fun getBoardsForMcu(mcu: String, boards: List<Board>): List<Board> = boards
        .filter { it.mcu == mcu.toLowerCasePreservingASCIIRules() }

    /**
     * Gets firmware variants for a specific board.
     *
     * @param board The board to get firmware variants for
     * @return List of firmware variant names (e.g., "Standard", "SPIRAM", "OTA")
     */
    fun getFirmwareVariants(board: Board): List<String> = board.firmwareNameToLinkParts.keys.toList()

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

    suspend fun downloadFirmwareToTemp(
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
            val headResponse = withContext(Dispatchers.IO) {
                client.sendAsync(headRequest, HttpResponse.BodyHandlers.discarding())
                    .await()
            }
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
                withContext(Dispatchers.IO) {
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                        .await()
                }.let { response ->
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

                        checkCanceled()
                    }

                    outputStream.toByteArray()
                }
            } else {
                // Unknown size - just download without progress
                reporter.details("Downloading firmware...")
                withContext(Dispatchers.IO) {
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                        .await()
                }.let { response ->
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
        val extension = portToExtension[board.port.toLowerCasePreservingASCIIRules()]
        val tempFile = createTempFile(
            prefix = downloadLinkPart,
            suffix = extension
        )

        // Write the firmware data to the temp file
        tempFile.toFile().writeBytes(response)

        reporter.details(null)

        return tempFile.absolutePathString()
    }

    fun getMpyFlasherFromDeviceType(deviceType: String): MpyFlasherInterface {
        return with(deviceType.toLowerCasePreservingASCIIRules()) {
            when {
                startsWith("esp") -> MpyEspFlasher(project)
                startsWith("samd") -> MpyUf2Flasher(Uf2BoardFamily.SAMD)
                startsWith("rp2") -> MpyUf2Flasher(Uf2BoardFamily.RP2)
                else -> throw RuntimeException("MCU \"${deviceType}\" not supported by flasher")
            }
        }
    }

    suspend fun getFirmwareAndFlash(
        reporter: RawProgressReporter,
        board: Board,
        firmwareVariant: String,
        version: String,
        target: String,
        eraseFlash: Boolean,
        localFirmwarePath: String? = null  // If provided, download is skipped
    ) {
        val flasher = getMpyFlasherFromDeviceType(board.port)

        var pathToFirmware: String? = null

        try {
            pathToFirmware = if (localFirmwarePath.isNullOrBlank()) {
                downloadFirmwareToTemp(reporter, board, firmwareVariant, version)
            } else {
                localFirmwarePath
            }

            flasher.flash(
                reporter,
                target,
                pathToFirmware,
                eraseFlash,
                board
            )

            // Ensure details are cleaned and don't linger for the following operations
            reporter.details(null)
        } finally {
            // Clean up temp file if we downloaded it
            if (localFirmwarePath == null) {
                pathToFirmware?.let {
                    File(it).delete()
                }
            }
        }
    }
}