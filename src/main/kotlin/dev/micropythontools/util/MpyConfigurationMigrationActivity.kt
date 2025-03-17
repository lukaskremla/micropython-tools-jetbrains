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

import com.intellij.execution.impl.RunManagerImpl
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.NOTIFICATION_GROUP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * @author Lukas Kremla
 */
class MpyConfigurationMigrationActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        val settings = project.service<MpySettingsService>()

        delay(2000)

        // Avoid running migration code if the settings version is up to date
        if (true) {//if (settings.state.settingsVersion != 2) {
            val runManagerImpls = RunManagerImpl.getInstanceImpl(project)

            var hasModifiedSomething = false

            runManagerImpls.allConfigurationsList.forEach { configuration ->
                // Create a temporary element to which the configuration attributes are dumped and write it
                val element = org.jdom.Element("temporaryConfig")
                configuration.writeExternal(element)

                // Now the attributes can be checked
                // They're of no interest and can be skipped if they aren't of the old "micropython-tools-flash-configuration"
                if (!element.attributes.toString().contains("micropython-tools-flash-configuration")) return@forEach

                element.attributes.forEach { attribute ->
                    if (attribute.value == "micropython-tools-flash-configuration") {
                        // Update the configuration ID
                        attribute.value = "micropython-tools-upload-configuration"
                    }

                    if (attribute.value.contains("Flash")) {
                        attribute.value = attribute.value.replace("Flash", "Upload")
                    }
                }

                // Handle switchToReplOnSuccess option
                element.children.forEach { child ->
                    if (child.name == "option") {
                        val attribute = child.getAttribute("name")

                        if (attribute.value == "runReplOnSuccess") {
                            attribute.value = "switchToReplOnSuccess"
                        }
                    }
                }

                configuration.readExternal(element)

                hasModifiedSomething = true
            }

            // The migration process has completed fully, the settings version can be saved
            settings.state.settingsVersion = 2

            if (hasModifiedSomething) {
                Notifications.Bus.notify(
                    Notification(
                        NOTIFICATION_GROUP,
                        "MicroPython tools update",
                        "MicroPython Tools run configurations have been automatically updated.<br><br>" +
                                "For the changes to take effect, the IDE needs to be restarted.<br><br>" +
                                "NOTE: If your run configurations were stored in a file, you'll need to reconfigure them after restart.",
                        NotificationType.WARNING
                    ).addAction(object : NotificationAction("Restart IDE") {
                        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                            ProjectManager.getInstance().reloadProject(project)
                            notification.expire()
                        }
                    }),
                    project
                )

                withContext(Dispatchers.EDT) {
                    val clickResult = MessageDialogBuilder.Message(
                        "MicroPython Tools Update",
                        "MicroPython Tools run configurations have been automatically updated.\n\n" +
                                "For the changes to take effect, the IDE needs to be restarted.\n\n" +
                                "NOTE: If your run configurations were stored in a file, you'll need to reconfigure them after restart.",
                    ).buttons("Restart", "Cancel").defaultButton("Restart").show(project)

                    if (clickResult == "Restart") {
                        ProjectManager.getInstance().reloadProject(project)
                    }
                }
            }
        }

        // Logic for cleaning-up old MicroPython Tools libraries
        /*delay(20000)

        DumbService.getInstance(project).smartInvokeLater {
            ApplicationManager.getApplication().runWriteAction {
                // Clean up library table
                val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                val projectLibraryModel = projectLibraryTable.modifiableModel

                val librariesToRemove = projectLibraryModel.libraries.filter {
                    it.name in listOf("MicroPythonTools", "MicroPython Tools")
                }

                librariesToRemove.forEach { library ->
                    projectLibraryModel.removeLibrary(library)
                }

                projectLibraryModel.commit()

                // Clean up order entries
                val moduleManager = ModuleManager.getInstance(project)
                val modules = moduleManager.modules

                modules.forEach { module ->
                    val moduleRootManager = ModuleRootManager.getInstance(module)
                    val moduleRootModel = moduleRootManager.modifiableModel

                    // Find all order entries related to MicroPython libraries
                    val entriesToRemove = moduleRootModel.orderEntries.filter { entry ->
                        entry is LibraryOrderEntry && (entry.libraryName in listOf("MicroPythonTools", "MicroPython Tools"))
                    }

                    if (entriesToRemove.isNotEmpty()) {
                        entriesToRemove.forEach { entry ->
                            moduleRootModel.removeOrderEntry(entry)
                        }

                        moduleRootModel.commit()
                    }
                }

                println("Finished cleaning up all order entries")
            }
        }*/
    }
}