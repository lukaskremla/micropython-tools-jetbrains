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

package dev.micropythontools.startup

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.core.MpyPaths.STUB_PACKAGE_METADATA_FILE_NAME
import dev.micropythontools.freemium.MpyProServiceInterface
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.MpySettingsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.writeText

@Serializable
private data class NewStubPackageMetadata(
    val port: String,
    val board: String,
    val variant: String
)

@Serializable
private data class OldStubPackageMetadata(
    val port: String,
    val board: String,
    val variant: String,
    val boardStubVersion: String,
    val stdlibSubVersion: String
) {
    companion object {
        fun fromJson(jsonString: String): OldStubPackageMetadata {
            return Json.decodeFromString<OldStubPackageMetadata>(jsonString)
        }
    }
}

internal class MpyConfigurationMigrationActivity : ProjectActivity, DumbAware {
    companion object {
        private const val CURRENT_NOTIFICATION_VERSION = "2025.3.3"
    }

    override suspend fun execute(project: Project) {
        val stubsDir = MpyPaths.stubBaseDir

        // Check all stub packages
        stubsDir.toFile().listFiles().forEach {
            try {
                if (!it.isDirectory) return@forEach

                // Resolve the old metadata file
                val oldMetadataFile = it.resolve("micropython-stubs.json")

                // Process only if the package actually has a metadata file
                if (oldMetadataFile.exists()) {
                    val oldFileContent = oldMetadataFile.readText()

                    // Retrieve the old metadata file
                    val oldStubPackageMetadata = OldStubPackageMetadata.fromJson(oldFileContent)

                    val json = Json { prettyPrint = true }
                    val payload = NewStubPackageMetadata(
                        port = oldStubPackageMetadata.port,
                        board = oldStubPackageMetadata.board,
                        variant = oldStubPackageMetadata.variant
                    )

                    // Prep the content and file path
                    val newFileContent = json.encodeToString(payload)
                    val targetFile = stubsDir.resolve(STUB_PACKAGE_METADATA_FILE_NAME)

                    // Write the metadata file
                    targetFile.writeText(newFileContent)
                }
            } catch (_: Throwable) {
                // Ignore, if migration fails for whatever reason, the user will have to reinstall
            }
        }

        val settings = project.service<MpySettingsService>()

        // Check if plugin is enabled
        if (!settings.state.isPluginEnabled) return

        val lastShown = settings.state.lastShownVersion

        // Show update notification if version changed
        if (lastShown != CURRENT_NOTIFICATION_VERSION) {
            showUpdateNotification(project)
            settings.state.lastShownVersion = CURRENT_NOTIFICATION_VERSION
        }
    }

    private fun showUpdateNotification(project: Project) {
        val notification = Notification(
            MpyBundle.message("notification.group.name"),
            "MicroPython Tools - Version $CURRENT_NOTIFICATION_VERSION",
            """
        <html>
        <b>NEW: One-Click ESP Firmware Flashing</b><br><br>
        
        Flash MicroPython firmware directly from the IDE! Select your board, download firmware from micropython.org
        automatically, and flash with real-time progress reporting.<br><br>
        
        The dialog is accessible when not connected to any devices through the 
        File System widget empty text or the plugin settings and available <b>for FREE to everyone</b>.<br><br>
        
        Supports ESP, RP2 and SAMD devices.<br><br>
        
        <b>Pro features</b><br><br>
        
        Get the most out of your IDE with Pro features:
        
        <ul>
            <li>Background uploads that don't block your IDE</li>
            <li>Automatic upload compression for significantly faster and more stable uploads</li>
            <li>One-click mpy-cross run configuration, with parameter auto-detection and friendly UI</li>
            <li>An .mpy file analyzer for viewing MPY bytecode information</li>
        </ul>

        <i>Coming to Pro soon:</i> firmware flashing via run configurations.<br><br>
        
        Start a <b>30-day free trial</b> to try Pro features or purchase a license below.
        </html>
        """.trimIndent(),
            NotificationType.INFORMATION
        )

        notification.addAction(NotificationAction.createSimple(MpyBundle.message("migration.notification.action.view.release.notes")) {
            BrowserUtil.browse("https://github.com/lukaskremla/micropython-tools-jetbrains/releases/tag/$CURRENT_NOTIFICATION_VERSION")
        })

        notification.addAction(NotificationAction.createSimple(MpyBundle.message("migration.notification.action.get.pro.license")) {
            project.service<MpyProServiceInterface>().requestLicense()
        })

        Notifications.Bus.notify(notification, project)
    }
}