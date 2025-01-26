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

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.execution.RunManager
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.isFile
import dev.micropythontools.run.MpyFlashConfiguration
import dev.micropythontools.run.MpyFlashConfigurationFactory
import dev.micropythontools.run.MpyRunConfigurationType
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.NOTIFICATION_GROUP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @author Lukas Kremla
 */
class MpyFacetMigrationActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        val settings = project.service<MpySettingsService>()

        // Avoid running migration code if the settings version is up to date
        if (settings.state.settingsVersion == 1) {
            return
        }

        var hasMigratedFacet = false
        var hasMigratedRunConfiguration = false
        var hasMigratedPasswordSafe: Boolean

        // Instantiate the plugin's run configuration factory here so that all configurations that might be migrated
        // share one factory
        val factory = MpyFlashConfigurationFactory(MpyRunConfigurationType())

        // Assume that the main .iml is inside the project's .idea folder
        val ideaDirPath = "${project.guessProjectDir()}/.idea".removePrefix("file:")

        val ideaDir = LocalFileSystem.getInstance().findFileByPath(ideaDirPath) ?: return

        // iterate over all .idea contents and look through .iml files
        for (child in ideaDir.children) {
            if (child.isFile) {
                if (child.extension == "iml") {
                    var modified = false

                    val file = File(child.path)
                    if (!file.exists()) {
                        continue
                    }

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
                        hasMigratedFacet = true
                        JDOMUtil.write(document, file)
                        project.save()
                    }
                }
            }
        }

        val runManagerImpls = RunManagerImpl.getInstanceImpl(project)

        // TODO: Revisit the existing run configuration migrator and make sure it works
        // TODO: Extend the implementation to also migrate newer configurations (as the MicroPython source feature changed things again)
        runManagerImpls.allConfigurationsList.forEach { configuration ->
            // Create a temporary element to which the configuration attributes are dumped and write it
            val element = org.jdom.Element("temporaryConfig")
            configuration.writeExternal(element)

            // Now the attributes can be checked
            // They're of no interest and can be skipped if they aren't of the old "MpyConfigurationType"
            if (!element.attributes.toString().contains("MpyConfigurationType")) return@forEach

            // The configuration must be of MpyConfigurationType if we got here, its settings can be extracted
            val savedName = element.getAttributeValue("name")
            val path = element.getAttributeValue("path")
            val useFTP = element.getAttributeValue("ftp") == "yes"
            val runReplOnSuccess = element.getAttributeValue("run-repl-on-success") == "yes"
            val resetOnSuccess = element.getAttributeValue("reset-on-success") == "yes"
            val synchronize = element.getAttributeValue("synchronize") == "yes"
            val excludePaths = element.getAttributeValue("exclude-paths") == "yes"
            val excludedPaths = mutableListOf<String>()

            element.getChild("excluded-paths")?.let { excludedPathsElement ->
                excludedPathsElement.getChildren("path").forEach { pathElement ->
                    pathElement.getAttributeValue("value")?.let { path ->
                        excludedPaths.add(path)
                    }
                }
            }

            // Try to preserve the original name, but re-create it up to new standards if necessary
            val name = when {
                !savedName.isNullOrBlank() -> savedName

                !path.isNullOrBlank() -> "Flash $path"

                else -> "Flash Project"
            }

            // Instantiate the new flash configuration with the old one's settings
            val writtenConfiguration = MpyFlashConfiguration(
                project,
                factory,
                name
            ).apply {
                saveOptions(
                    flashingProject = path.isNullOrBlank(),
                    selectedPaths = mutableListOf(path ?: ""),
                    resetOnSuccess = resetOnSuccess,
                    switchToReplOnSuccess = runReplOnSuccess,
                    alwaysUseFTP = useFTP,
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

            hasMigratedRunConfiguration = true
        }

        // Retrieve possible old credential attributes
        val oldWifiAttributes = createOldCredentialAttributes(project, "WiFi")
        val oldWebREPLAttributes = createOldCredentialAttributes(project, "WebREPL")

        withContext(Dispatchers.IO) {
            // Retrieve old credentials
            val oldWifiCredentials = PasswordSafe.instance.get(oldWifiAttributes)
            val oldWifiSSID = oldWifiCredentials?.userName ?: ""
            val oldWifiPass = oldWifiCredentials?.getPasswordAsString() ?: ""

            val oldWebReplPass = PasswordSafe.instance.getPassword(oldWebREPLAttributes) ?: ""

            // Save old credentials in the new format
            settings.saveWifiCredentials(oldWifiSSID, oldWifiPass)
            settings.saveWebReplPassword(oldWebReplPass)

            // Delete old credentials
            PasswordSafe.instance.set(oldWifiAttributes, null)
            PasswordSafe.instance.set(oldWebREPLAttributes, null)

            hasMigratedPasswordSafe = when {
                oldWifiSSID != "" || oldWifiPass != "" || oldWebReplPass != "" -> true

                else -> false
            }
        }

        if (hasMigratedFacet || hasMigratedRunConfiguration || hasMigratedPasswordSafe) {
            // A MicroPython Tools facet was found, new plugin should be enabled as well
            // to give users a smooth switch to the new plugin settings persistence structure
            // (the facet won't be there if the support was disabled)
            if (hasMigratedFacet) {
                settings.state.isPluginEnabled = true
            }

            Notifications.Bus.notify(
                Notification(
                    NOTIFICATION_GROUP,
                    "MicroPython Tools: We have updated the plugin's internal logic to a use the " +
                            "latest IntelliJ API. But don't worry! All of your settings have automatically been migrated." +
                            "<br><br>" +
                            "You can ignore any MicroPython Tools facet errors if they popped up, we've already taken " +
                            "care of them for you!" +
                            "<br><br>" +
                            "Happy coding!",
                    NotificationType.INFORMATION
                ), project
            )
        }

        // The migration process has completed fully, the settings version can be saved
        settings.state.settingsVersion = 1
    }

    private fun createOldCredentialAttributes(project: Project, key: String): CredentialAttributes {
        val projectIdentifyingElement = project.guessProjectDir()?.path ?: project.name
        // In previous versions the package looked like this,
        val fullKey = "dev.micropythontools.intellij.settings.MpySettingsService/${projectIdentifyingElement}/$key"

        return CredentialAttributes(
            generateServiceName("MySystem", fullKey)
        )
    }
}