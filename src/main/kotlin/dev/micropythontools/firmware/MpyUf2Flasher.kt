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

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.ui.dsl.builder.panel
import dev.micropythontools.i18n.MpyBundle
import jssc.SerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JComponent

/**
 * Abstract base class for UF2-based firmware flashing (RP2, SAMD).
 * Handles bootloader entry via 1200 baud touch and cross-platform volume detection.
 */
internal abstract class MpyUf2Flasher(private val project: Project) : MpyFlasherInterface {
    // TODO: The intention is right but needs adjustments
    // In an unflashed state, the device might not present a serial port, a different approach than solely relying on
    // A serial port always being present must be taken, the STM32 supports awaits similar trouble
    // In future iterations, the dialog will have to be reworked to not be port centric.

    /**
     * Board ID prefix to match in INFO_UF2.TXT (e.g., "RP2", "SAMD").
     * The Board-ID line might contain values like "RPI-RP2", "SAMD21", "SAMD51", etc.
     */
    abstract val boardIdPrefix: String

    companion object {
        private const val INFO_UF2_FILENAME = "INFO_UF2.TXT"
        private const val BOARD_ID_PREFIX = "Board-ID:"
        private const val BOOTLOADER_WAIT_SECONDS = 10
        private const val MANUAL_BOOTLOADER_POLL_SECONDS = 30
        private const val REBOOT_WAIT_SECONDS = 5
    }

    override suspend fun flash(
        reporter: RawProgressReporter,
        port: String,
        pathToFirmware: String,
        mcu: String,
        offset: String,
        eraseFlash: Boolean
    ) {
        // Step 1: Check if UF2 volume is already mounted - error if so
        reporter.text(MpyBundle.message("flash.uf2.checking.existing.volume"))
        val existingVolume = findUf2Volume()
        if (existingVolume != null) {
            throw RuntimeException(
                MpyBundle.message(
                    "flash.uf2.error.volume.already.mounted",
                    existingVolume.toString()
                )
            )
        }

        // Step 2: Enter bootloader via 1200 baud touch
        reporter.text(MpyBundle.message("flash.uf2.entering.bootloader"))
        enter1200BaudBootloader(port)

        // Step 3: Wait for UF2 volume (10 seconds)
        reporter.text(MpyBundle.message("flash.uf2.waiting.for.volume"))
        var uf2Volume = waitForUf2Volume(BOOTLOADER_WAIT_SECONDS, reporter)

        // Step 4: If not found, show manual bootloader dialog and continue polling
        if (uf2Volume == null) {
            uf2Volume = showManualBootloaderDialogAndWait(reporter)
        }

        if (uf2Volume == null) {
            throw RuntimeException(MpyBundle.message("flash.uf2.error.volume.not.found"))
        }

        // Step 5: Verify Board-ID matches expected prefix
        val boardId = readBoardId(uf2Volume)
        if (boardId == null || !boardId.uppercase().contains(boardIdPrefix.uppercase())) {
            throw RuntimeException(
                MpyBundle.message(
                    "flash.uf2.error.wrong.board",
                    boardIdPrefix,
                    boardId ?: "Unknown"
                )
            )
        }

        reporter.details(MpyBundle.message("flash.uf2.board.id.found", boardId))

        // Step 6: Copy firmware file to volume
        reporter.text(MpyBundle.message("flash.uf2.copying.firmware"))
        val firmwareFile = Path.of(pathToFirmware)
        val destinationFile = uf2Volume.resolve(firmwareFile.fileName.toString())

        withContext(Dispatchers.IO) {
            Files.copy(firmwareFile, destinationFile, StandardCopyOption.REPLACE_EXISTING)
        }

        // Step 7: Wait for device to reboot (volume disappears)
        reporter.text(MpyBundle.message("flash.uf2.waiting.for.reboot"))
        waitForVolumeToDisappear(uf2Volume, REBOOT_WAIT_SECONDS)

        reporter.text(MpyBundle.message("flash.uf2.complete"))
        reporter.fraction(1.0)
    }

    /**
     * Enters bootloader mode using the 1200 baud touch method.
     * Opens serial port at 1200 baud, sets RTS/DTR to false, waits, then closes.
     */
    private fun enter1200BaudBootloader(port: String) {
        val serialPort = SerialPort(port)
        try {
            serialPort.openPort()
            serialPort.setParams(
                1200,
                SerialPort.DATABITS_8,
                SerialPort.STOPBITS_1,
                SerialPort.PARITY_NONE
            )
            serialPort.setRTS(false)
            serialPort.setDTR(false)
            Thread.sleep(200)
        } finally {
            try {
                serialPort.closePort()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    /**
     * Finds a mounted UF2 volume by looking for INFO_UF2.TXT file.
     * Cross-platform implementation for macOS, Windows, and Linux.
     */
    private fun findUf2Volume(): Path? {
        val os = System.getProperty("os.name").lowercase()

        return when {
            os.contains("mac") -> findUf2VolumeMacOS()
            os.contains("win") -> findUf2VolumeWindows()
            os.contains("linux") -> findUf2VolumeLinux()
            else -> null
        }
    }

    /**
     * Finds UF2 volume on macOS by checking /Volumes/
     */
    private fun findUf2VolumeMacOS(): Path? {
        val volumesDir = File("/Volumes")
        if (!volumesDir.exists() || !volumesDir.isDirectory) return null

        return volumesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.toPath() }
            ?.firstOrNull { isUf2Volume(it) }
    }

    /**
     * Finds UF2 volume on Windows by checking all drive roots
     */
    private fun findUf2VolumeWindows(): Path? {
        return File.listRoots()
            .map { it.toPath() }
            .firstOrNull { isUf2Volume(it) }
    }

    /**
     * Finds UF2 volume on Linux by checking common mount points
     */
    private fun findUf2VolumeLinux(): Path? {
        val username = System.getProperty("user.name")
        val mountPoints = listOf(
            "/media/$username",
            "/run/media/$username",
            "/media"
        )

        for (mountPoint in mountPoints) {
            val dir = File(mountPoint)
            if (!dir.exists() || !dir.isDirectory) continue

            val found = dir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.toPath() }
                ?.firstOrNull { isUf2Volume(it) }

            if (found != null) return found
        }

        return null
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

    /**
     * Waits for a UF2 volume to appear, polling every second
     */
    private suspend fun waitForUf2Volume(seconds: Int, reporter: RawProgressReporter): Path? {
        repeat(seconds) { i ->
            reporter.fraction(i.toDouble() / seconds)
            val volume = findUf2Volume()
            if (volume != null) return volume
            delay(1000)
        }
        return null
    }

    /**
     * Waits for the UF2 volume to disappear (indicating device has rebooted)
     */
    private suspend fun waitForVolumeToDisappear(volumePath: Path, seconds: Int) {
        repeat(seconds) {
            if (!Files.exists(volumePath)) return
            delay(1000)
        }
    }

    /**
     * Shows a modeless dialog asking user to enter bootloader manually,
     * while continuing to poll for the UF2 volume in the background.
     * Dialog auto-closes when volume is detected.
     */
    private suspend fun showManualBootloaderDialogAndWait(reporter: RawProgressReporter): Path? {
        var dialog: ManualBootloaderDialog? = null
        var foundVolume: Path? = null

        // Show dialog on EDT
        withContext(Dispatchers.EDT) {
            dialog = ManualBootloaderDialog(project)
            dialog.show()
        }

        // Poll for volume while dialog is open
        reporter.text(MpyBundle.message("flash.uf2.waiting.manual.bootloader"))

        repeat(MANUAL_BOOTLOADER_POLL_SECONDS) { i ->
            reporter.fraction(i.toDouble() / MANUAL_BOOTLOADER_POLL_SECONDS)

            val volume = findUf2Volume()
            if (volume != null) {
                foundVolume = volume
                // Close dialog on EDT
                withContext(Dispatchers.EDT) {
                    dialog?.close(DialogWrapper.OK_EXIT_CODE)
                }
                return@repeat
            }

            // Check if dialog was closed by user
            val dialogClosed = withContext(Dispatchers.EDT) {
                dialog?.isDisposed == true || dialog?.isVisible == false
            }

            if (dialogClosed && foundVolume == null) {
                // User closed dialog without volume being found - give up
                return null
            }

            delay(1000)
        }

        // Final cleanup - close dialog if still open
        withContext(Dispatchers.EDT) {
            if (dialog?.isShowing == true) {
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
        }

        return foundVolume
    }

    /**
     * Modeless dialog asking user to enter bootloader mode manually
     */
    private class ManualBootloaderDialog(project: Project) : DialogWrapper(project, false) {
        init {
            title = MpyBundle.message("flash.uf2.manual.bootloader.dialog.title")
            setOKButtonText(MpyBundle.message("flash.uf2.manual.bootloader.dialog.ok"))
            setCancelButtonText(MpyBundle.message("flash.uf2.manual.bootloader.dialog.cancel"))
            init()
        }

        override fun createCenterPanel(): JComponent {
            return panel {
                row {
                    text(MpyBundle.message("flash.uf2.manual.bootloader.dialog.message"))
                }
            }
        }
    }
}


