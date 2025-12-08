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
import dev.micropythontools.communication.SHORT_DELAY
import dev.micropythontools.communication.TIMEOUT
import dev.micropythontools.ui.MpyFileSystemWidget.Companion.formatSize
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

enum class Uf2BoardFamily(val boardIdPrefix: String) {
    RP2("RP2"),
    SAMD("SAMD");

    companion object {
        fun fromIdPrefix(boardIdPrefix: String): Uf2BoardFamily? =
            entries.find { it.boardIdPrefix.equals(boardIdPrefix, ignoreCase = true) }
    }
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
        try {
            // Re-validate right before flashing just in case
            validate(target)

            val firmwareFile = Path.of(target)
            val destinationVolume = firmwareFile.resolve(firmwareFile.fileName.toString())

            copyWithProgress(reporter, firmwareFile, destinationVolume)

            reporter.text("Waiting for the device to restart...")

            withTimeout(TIMEOUT) {
                while (destinationVolume.exists()) {
                    delay(SHORT_DELAY)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw RuntimeException("Device didn't reboot in time")
        }
    }

    private suspend fun copyWithProgress(reporter: RawProgressReporter, source: Path, dest: Path) {
        val totalBytes = Files.size(source)
        var copiedBytes = 0L

        Files.newInputStream(source).use { input ->
            Files.newOutputStream(dest).use { output ->
                val buffer = ByteArray(65536) // 64KB chunks
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    copiedBytes += bytesRead

                    reporter.text("Copying firmware file...")
                    reporter.fraction(copiedBytes.toDouble() / totalBytes)

                    val percentage = (copiedBytes * 100 / totalBytes).toInt()

                    reporter.details("Copied ${formatSize(copiedBytes)} of ${formatSize(totalBytes)} ($percentage%)")

                    checkCanceled()
                }
            }
        }

        // Clear the details
        reporter.details(null)
    }

    override suspend fun validate(target: String): ValidationResult {
        val compatibleVolumes = findCompatibleUf2Volumes()

        return if (compatibleVolumes.distinct().size < compatibleVolumes.size) {
            ValidationResult(
                "There are several devices with identical UF2 volumes connected. " +
                        "Please make sure only the one device is connected and try again."
            )
        } else {
            ValidationResult.OK
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


