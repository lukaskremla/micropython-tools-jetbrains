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

package dev.micropythontools.util

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.JDOMUtil
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.NOTIFICATION_GROUP
import java.io.File

/**
 * @author Lukas Kremla
 */
class MpyFacetMigrationActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        ModuleManager.getInstance(project).modules.forEach { module ->
            val moduleFile = module.moduleFile
            if (moduleFile != null) {
                val file = File(moduleFile.path)
                if (file.exists()) {
                    var modified = false

                    @Suppress("DEPRECATION")
                    val document = JDOMUtil.loadDocument(file)
                    val rootElement = document.rootElement

                    rootElement.getChildren("component").forEach { component ->
                        if (component.getAttributeValue("name") == "FacetManager") {
                            val facets = component.getChildren("facet")
                            val mpyFacets = facets.filter {
                                it.getAttributeValue("type") == "MicroPython Tools"
                            }

                            if (mpyFacets.isNotEmpty()) {
                                mpyFacets.forEach { facet ->
                                    component.removeContent(facet)
                                }
                                modified = true
                            }
                        }
                    }

                    if (modified) {
                        JDOMUtil.write(document, file)
                        project.save()

                        // A MicroPython Tools facet was found, new plugin's support should be turned on
                        // to give users a smooth switch to the new plugin settings persistence structure
                        project.service<MpySettingsService>().state.isPluginEnabled = true

                        Notifications.Bus.notify(
                            Notification(
                                NOTIFICATION_GROUP,
                                "MicroPython Tools: We have updated the plugin's internal logic to a use the " +
                                        "latest IntelliJ API. Unfortunately, as a result of this, old run configurations " +
                                        "are no longer supported, they must be re-created manually. " +
                                        "<br><br>" +
                                        "The rest of your settings have been fully migrated. " +
                                        "<br><br>" +
                                        "We apologize for the inconvenience this might have caused you, " +
                                        "but we assure you that this change was vital to improving this plugin's maintainability.",
                                NotificationType.WARNING
                            ), project
                        )
                    }
                }
            }
        }
    }
}