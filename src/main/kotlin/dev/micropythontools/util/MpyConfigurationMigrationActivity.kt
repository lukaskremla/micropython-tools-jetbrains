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

import com.intellij.execution.RunManager
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.micropythontools.run.MpyRunConfType
import dev.micropythontools.run.MpyRunConfUpload
import dev.micropythontools.run.MpyRunConfUploadFactory
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
        if (settings.state.settingsVersion != 2) {
            var hasModifiedSomething = false

            val runManagerImpls = RunManagerImpl.getInstanceImpl(project)

            runManagerImpls.allConfigurationsList.forEach { configuration ->
                // Create a temporary element to which the configuration attributes are dumped and write it
                val element = org.jdom.Element("temporaryConfig")
                configuration.writeExternal(element)

                if (!element.attributes.toString().contains("micropython-tools-flash-configuration")) return@forEach
                
                val path = element.getChild("option")?.getAttributeValue("path") ?: element.getChildren("option").find { it.getAttributeValue("name") == "path" }?.getAttributeValue("value")
                val runReplOnSuccess = element.getChildren("option").find { it.getAttributeValue("name") == "runReplOnSuccess" }?.getAttributeValue("value") == "true"
                val resetOnSuccess = element.getChildren("option").find { it.getAttributeValue("name") == "resetOnSuccess" }?.getAttributeValue("value") == "true"
                val synchronize = element.getChildren("option").find { it.getAttributeValue("name") == "synchronize" }?.getAttributeValue("value") == "true"
                val excludePaths = element.getChildren("option").find { it.getAttributeValue("name") == "excludePaths" }?.getAttributeValue("value") == "true"
                val excludedPaths = mutableListOf<String>()

                element.getChildren("option").find { it.getAttributeValue("name") == "excludedPaths" }?.let { excludedPathsElement ->
                    excludedPathsElement.getChild("list")?.getChildren("option")?.forEach { pathElement ->
                        pathElement.getAttributeValue("value")?.let { path ->
                            excludedPaths.add(path)
                        }
                    }
                }

                // Try to preserve the original name, but re-create it up to new standards if necessary
                val name = "Upload Project"

                val factory = MpyRunConfUploadFactory(MpyRunConfType())

                // Instantiate the new flash configuration with the old one's settings
                val writtenConfiguration = MpyRunConfUpload(
                    project,
                    factory,
                    name
                ).apply {
                    saveOptions(
                        uploadMode = 0,
                        selectedPaths = emptyList<String>().toMutableList(),
                        path = path ?: "",
                        resetOnSuccess = resetOnSuccess,
                        switchToReplOnSuccess = runReplOnSuccess,
                        synchronize = synchronize,
                        excludePaths = excludePaths,
                        excludedPaths = excludedPaths
                    )
                }

                // create the final run configuration that can be added to the project
                val runnerAndConfigSettings = RunManager.getInstance(project).createConfiguration(
                    writtenConfiguration,
                    factory
                )

                // Add the new migrated configuration and remove the old one
                runManagerImpls.addConfiguration(runnerAndConfigSettings)
                runManagerImpls.removeConfiguration(runManagerImpls.findSettings(configuration))

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
                                "The plugin no longer uses basic Sources Roots but now requires MicroPython Sources roots..<br><br>" +
                                "NOTE: If your run configurations were stored in a file, you'll need to reconfigure them.",
                        NotificationType.WARNING
                    ),
                    project
                )

                withContext(Dispatchers.EDT) {
                    MessageDialogBuilder.Message(
                        "MicroPython Tools Update",
                        "MicroPython Tools run configurations have been automatically updated.\n\n" +
                                "The plugin no longer uses basic Sources Roots but now requires MicroPython Sources roots.\n\n" +
                                "NOTE: If your run configurations were stored in a file, you'll need to reconfigure.",
                    ).buttons("Ok").defaultButton("Ok").show(project)
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