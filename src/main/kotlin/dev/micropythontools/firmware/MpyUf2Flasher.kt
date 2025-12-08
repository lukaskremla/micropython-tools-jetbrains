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

import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.progress.checkCanceled
import com.intellij.platform.util.progress.RawProgressReporter
import dev.micropythontools.communication.LONG_LONG_TIMEOUT
import dev.micropythontools.communication.SHORT_DELAY
import dev.micropythontools.communication.TIMEOUT
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.settings.EMPTY_VOLUME_TEXT
import dev.micropythontools.ui.MpyFileSystemWidget.Companion.formatSize
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

enum class Uf2BoardFamily(val boardIdPrefix: String) {
    RP2("RP2"),
    SAMD("SAMD");
}

/**
 * Abstract base class for UF2-based firmware flashing (RP2, SAMD).
 * Handles bootloader entry via 1200 baud touch and cross-platform volume detection.
 */
internal class MpyUf2Flasher(private val boardFamily: Uf2BoardFamily) : MpyFlasherInterface {
    companion object {
        private const val INFO_UF2_FILENAME = "INFO_UF2.TXT"
        private const val BOARD_ID_PREFIX = "Board-ID:"
    }

    override suspend fun flash(
        reporter: RawProgressReporter,
        target: String,
        pathToFirmware: String,
        eraseFlash: Boolean,
        board: Board
    ) {
        val firmwareFile = Path.of(pathToFirmware)
        val destinationVolume = Path.of(target)

        if (eraseFlash && boardFamily == Uf2BoardFamily.RP2) {
            val nukeFile = Files.createTempFile("flash_nuke", ".uf2")
            try {
                javaClass.getResourceAsStream("/bundled/${MpyPaths.RP2_UNIVERSAL_FLASH_NUKE_FILE_NAME}")!!
                    .use { input ->
                        Files.copy(input, nukeFile, StandardCopyOption.REPLACE_EXISTING)
                    }

                // Copy flash nuke file
                copyWithProgress(
                    reporter,
                    "Copying flash erase utility...",
                    nukeFile,
                    destinationVolume
                )

                reporter.text("Waiting for the device to restart...")
                try {
                    // First wait for the device to disappear
                    reporter.details("Waiting for the volume to disappear")
                    withTimeout(TIMEOUT) {
                        while (destinationVolume in findCompatibleUf2Volumes()) {
                            delay(SHORT_DELAY)
                        }
                    }

                    // Then wait for the device to reappear
                    reporter.details("Waiting for the volume to reappear (This can take a while)")
                    withTimeout(LONG_LONG_TIMEOUT) {
                        while (destinationVolume !in findCompatibleUf2Volumes()) {
                            delay(SHORT_DELAY)
                        }
                    }

                    reporter.details(null)
                } catch (_: TimeoutCancellationException) {
                    throw RuntimeException("Device didn't restart in time")
                }
            } finally {
                nukeFile.deleteIfExists()
            }
        }

        // Copy firmware file
        copyWithProgress(
            reporter,
            "Copying firmware file...",
            firmwareFile,
            destinationVolume
        )
    }

    private suspend fun copyWithProgress(
        reporter: RawProgressReporter,
        progressText: String,
        source: Path,
        volume: Path
    ) {
        val totalBytes = Files.size(source)
        var copiedBytes = 0L

        reporter.text(progressText)

        val dest = volume.resolve(source.fileName.toString())

        // Timeout on opening the channels, sometimes the volume might present itself but not be writable
        val (input, output) = try {
            withTimeout(TIMEOUT) {
                runInterruptible(Dispatchers.IO) {
                    Pair(
                        FileChannel.open(source, StandardOpenOption.READ),
                        FileChannel.open(dest, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
                    )
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw RuntimeException("The volume isn't writable, please reconnect it and start over.")
        } catch (e: NoSuchFileException) {
            // Only handle the output directory missing error explicitly, the dest missing error might be encountered commonly
            // and doesn't signify a bug in the plugin.
            // Missing source file suggests a bug in the plugin and doesn't need a separate message
            if (e.message == dest.absolutePathString()) {
                throw RuntimeException("Failed to find selected volume, please re-enter bootloader mode and try again.")
            } else {
                throw e
            }
        }

        input.use { inputChannel ->
            output.use { outputChannel ->
                val buffer = ByteBuffer.allocate(65536)

                while (true) {
                    buffer.clear()

                    val bytesRead = try {
                        withTimeout(TIMEOUT) {
                            runInterruptible(Dispatchers.IO) {
                                inputChannel.read(buffer)
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        throw RuntimeException("Timed out while reading a UF2 firmware chunk")
                    }

                    if (bytesRead == -1) break

                    // Prepare for writing
                    buffer.flip()

                    try {
                        withTimeout(TIMEOUT) {
                            runInterruptible(Dispatchers.IO) {
                                outputChannel.write(buffer)
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        throw RuntimeException("Timed out while writing a UF2 firmware chunk")
                    }

                    copiedBytes += bytesRead

                    val percentage = (copiedBytes * 100 / totalBytes).toInt()
                    reporter.fraction(copiedBytes.toDouble() / totalBytes)
                    reporter.details("Copied ${formatSize(copiedBytes)} of ${formatSize(totalBytes)} ($percentage%)")

                    checkCanceled()
                }
            }
        }

        reporter.details(null)
    }

    override suspend fun validate(target: String): ValidationResult {
        val compatibleVolumes = findCompatibleUf2Volumes()

        return when {
            target == EMPTY_VOLUME_TEXT -> ValidationResult("No volume selected")

            compatibleVolumes.distinct().size < compatibleVolumes.size -> ValidationResult(
                "There are several devices with identical UF2 volumes connected. " +
                        "Please make sure only the one device is connected and try again."
            )

            else -> ValidationResult.OK
        }
    }

    fun findCompatibleUf2Volumes(): List<Path> {
        val uf2Volumes = findUf2Volumes()

        return uf2Volumes.filter {
            val boardId = readBoardId(it)
            boardId != null && boardId.uppercase().contains(boardFamily.boardIdPrefix)
        }
    }

    /**
     * Finds a mounted UF2 volume by looking for INFO_UF2.TXT file.
     * Cross-platform implementation for macOS, Windows, and Linux.
     */
    private fun findUf2Volumes(): List<Path> {
        val os = System.getProperty("os.name").lowercase()

        return when {
            os.contains("mac") -> findUf2VolumesMacOS()
            os.contains("win") -> findUf2VolumesWindows()
            os.contains("linux") -> findUf2VolumesLinux()
            else -> emptyList()
        }
    }

    /**
     * Finds UF2 volume on macOS by checking /Volumes/
     */
    private fun findUf2VolumesMacOS(): List<Path> {
        val volumesDir = File("/Volumes")
        if (!volumesDir.exists() || !volumesDir.isDirectory) return emptyList()

        val uf2Volumes = volumesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.toPath() }
            ?.filter { isUf2Volume(it) }

        return uf2Volumes ?: emptyList()
    }

    /**
     * Finds UF2 volume on Windows by checking all drive roots
     */
    private fun findUf2VolumesWindows(): List<Path> {
        val uf2Volumes = File.listRoots()
            ?.map { it.toPath() }
            ?.filter { isUf2Volume(it) }

        return uf2Volumes ?: emptyList()
    }

    /**
     * Finds UF2 volume on Linux by checking common mount points
     */
    private fun findUf2VolumesLinux(): List<Path> {
        val username = System.getProperty("user.name")
        val mountPoints = listOf(
            "/media/$username",
            "/run/media/$username",
            "/media"
        )

        val uf2Volumes = mutableListOf<Path>()

        mountPoints.forEach { mountPoint ->
            val mountPointFile = File(mountPoint)

            if (!mountPointFile.exists() || !mountPointFile.isDirectory) return@forEach

            val newUf2Volumes = mountPointFile.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.toPath() }
                ?.filter { isUf2Volume(it) }
                ?: emptyList()

            uf2Volumes.addAll(newUf2Volumes)
        }

        return uf2Volumes
    }

    /**
     * Checks if a path is a UF2 bootloader volume by looking for INFO_UF2.TXT
     */
    private fun isUf2Volume(path: Path): Boolean {
        return try {
            val infoFile = path.resolve(INFO_UF2_FILENAME)
            Files.exists(infoFile) && Files.isReadable(infoFile)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads the Board-ID from INFO_UF2.TXT file
     */
    private fun readBoardId(volumePath: Path): String? {
        return try {
            val infoFile = volumePath.resolve(INFO_UF2_FILENAME)
            Files.readAllLines(infoFile)
                .firstOrNull { it.startsWith(BOARD_ID_PREFIX) }
                ?.substringAfter(BOARD_ID_PREFIX)
                ?.trim()
        } catch (_: Exception) {
            null
        }
    }
}


