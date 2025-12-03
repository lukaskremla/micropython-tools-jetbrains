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

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.util.progress.RawProgressReporter
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.core.MpyPythonInterpreterService
import dev.micropythontools.ui.MpyFileSystemWidget.Companion.formatSize

class MpyEspFlasher(project: Project) : MpyFlasherInterface {
    private val interpreterService = project.service<MpyPythonInterpreterService>()

    override suspend fun flash(
        reporter: RawProgressReporter,
        port: String,
        pathToFirmware: String,
        mcu: String,
        offset: String,
        eraseFlash: Boolean
    ) {
        // Set up environment to find esptool in isolated package directory
        val esptoolPath = interpreterService.getPackagePythonPath(
            MpyPaths.ESPTOOL_PACKAGE_NAME,
            MpyPaths.ESPTOOL_VERSION
        )
        val env = mapOf("PYTHONPATH" to esptoolPath)

        // Build and execute erase command if applicable
        if (eraseFlash) {
            val eraseArgs = mutableListOf(
                "-m", "esptool",
                "--chip", mcu,
                "--port", port,
                "erase-flash"
            )

            interpreterService.runPythonCodeWithCallback(eraseArgs, env) { outputLine ->
                reporter.text("Erasing flash...")

                // esptool sometimes prints a single dot, this is a hack to avoid displaying it
                if (outputLine != ".") {
                    reporter.details(outputLine)
                }
            }
        }

        // Build write command
        val flashArgs = mutableListOf(
            "-m", "esptool",
            "--chip", mcu,
            "--port", port,
            "write-flash",
            offset,
            pathToFirmware
        )

        // Perform flash while collecting output
        interpreterService.runPythonCodeWithCallback(flashArgs, env) { outputLine ->
            when {
                // esptool sometimes prints a single dot, this is a hack to avoid displaying it
                outputLine == "." -> Unit

                outputLine.startsWith("Writing at") -> {
                    val progressString = outputLine
                        .substringAfterLast("]")
                        .trim()

                    val percentage = progressString
                        .substringBeforeLast("%")
                        .trim()
                        .toDouble()

                    val byteStringParts = progressString
                        .substringAfterLast("%")
                        .substringBeforeLast("bytes...")
                        .trim()
                        .split("/")

                    val bytesWritten = byteStringParts[0].toLong()
                    val totalBytesToWrite = byteStringParts[1].toLong()

                    val progressText = if (percentage < 100) "Flashing firmware..." else "Flashing complete..."
                    reporter.text(progressText)
                    reporter.details("Flashed ${formatSize(bytesWritten)} of ${formatSize(totalBytesToWrite)} ($percentage%)")
                    reporter.fraction(percentage / 100.0)
                }

                outputLine.startsWith("Hard resetting") -> {
                    reporter.text("Hard resetting...")
                    reporter.details(outputLine)
                    reporter.fraction(1.0)
                }

                else -> {
                    // Show other output lines in details for additional context
                    if (outputLine.isNotBlank()) {
                        reporter.details(outputLine)
                    }
                }
            }
        }
    }
}