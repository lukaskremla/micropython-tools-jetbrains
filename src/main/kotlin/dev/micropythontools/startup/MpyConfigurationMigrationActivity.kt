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
import dev.micropythontools.freemium.MpyProServiceInterface
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.MpySettingsService

internal class MpyConfigurationMigrationActivity : ProjectActivity, DumbAware {
    companion object {
        private const val CURRENT_VERSION = "2025.2.2"
    }

    override suspend fun execute(project: Project) {
        val settings = project.service<MpySettingsService>()

        // Check if plugin is enabled
        if (!settings.state.isPluginEnabled) return

        val lastShown = settings.state.lastShownVersion

        // Show update notification if version changed
        if (lastShown != CURRENT_VERSION) {
            showUpdateNotification(project)
            settings.state.lastShownVersion = CURRENT_VERSION
        }
    }

    private fun showUpdateNotification(project: Project) {
        val notification = Notification(
            MpyBundle.message("notification.group.name"),
            "MicroPython Tools - Version $CURRENT_VERSION",
            """
            <html>
            <b>Important: Freemium Model Introduction</b><br><br>
            
            MicroPython Tools now offers Free and Pro editions. <b>Don't worry</b> - all features you've been 
            using remain completely free! Your workflow won't change. We've simply added new Pro features 
            that require a license, but everything you currently use stays accessible at no cost.<br><br>
            
            <b>New in this version:</b><br><br>
            
            <b>Free features:</b><br>
            • Switched to release year based versioning<br>
            • Improved stub package installation (UV support)<br>
            • Better run configuration console views<br><br>
            
            <b>Pro features (new, require license):</b><br>
            • mpy-cross compiler with auto-detection<br>
            • Upload compression<br>
            • Background uploads/downloads<br>
            • .mpy file analyzer<br><br>
            
            You can start a 30 day free trial to evaluate the pro features before committing to a subscription.
            </html>
            """.trimIndent(),
            NotificationType.INFORMATION
        )

        notification.addAction(NotificationAction.createSimple(MpyBundle.message("migration.notification.action.view.release.notes")) {
            BrowserUtil.browse("https://github.com/lukaskremla/micropython-tools-jetbrains/releases/tag/$CURRENT_VERSION")
        })

        notification.addAction(NotificationAction.createSimple(MpyBundle.message("migration.notification.action.get.pro.license")) {
            project.service<MpyProServiceInterface>().requestLicense()
        })

        Notifications.Bus.notify(notification, project)
    }
}