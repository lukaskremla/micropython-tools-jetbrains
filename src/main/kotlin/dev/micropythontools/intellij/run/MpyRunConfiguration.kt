/*
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2024-2025 Lukas Kremla
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

package dev.micropythontools.intellij.run

import com.intellij.execution.Executor
import com.intellij.execution.configuration.AbstractRunConfiguration
import com.intellij.execution.configuration.EmptyRunProfileState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationWithSuppressedDefaultDebugAction
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.facet.ui.ValidationResult
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.PathUtil
import dev.micropythontools.intellij.communication.MpyTransferService
import dev.micropythontools.intellij.settings.MpySettingsService
import org.jdom.Element
import ui.fileSystemWidget
import util.MpyPythonService

/**
 * @authors Lukas Kremla, elmot, vlan
 */
class MpyRunConfiguration(project: Project, factory: ConfigurationFactory) : AbstractRunConfiguration(project, factory),
    RunConfigurationWithSuppressedDefaultDebugAction {
    var path: String = ""
    var runReplOnSuccess: Boolean = false
    var resetOnSuccess: Boolean = true
    var useFTP: Boolean = false
    var synchronize: Boolean = false
    var excludePaths: Boolean = false
    var excludedPaths: MutableList<String> = mutableListOf()

    override fun getConfigurationEditor() = MpyRunConfigurationEditor(this)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val success: Boolean
        val projectDir = project.guessProjectDir()
        val projectPath = projectDir?.path

        var ssid = ""
        var wifiPassword = ""

        runWithModalProgressBlocking(project, "Retrieving credentials...") {
            val wifiCredentials = project.service<MpySettingsService>().retrieveWifiCredentials()

            ssid = wifiCredentials.userName ?: ""
            wifiPassword = wifiCredentials.getPasswordAsString() ?: ""
        }

        val transferService = project.service<MpyTransferService>()

        if (path.isBlank() || (projectPath != null && path == projectPath)) {
            success = transferService.uploadProject(
                excludedPaths,
                synchronize,
                excludePaths,
                useFTP,
                ssid,
                wifiPassword
            )
        } else {
            val toUpload = StandardFileSystems.local().findFileByPath(path) ?: return null
            success = transferService.uploadFileOrFolder(
                toUpload,
                excludedPaths,
                synchronize,
                excludePaths,
                useFTP,
                ssid,
                wifiPassword
            )
        }
        if (success) {
            val fileSystemWidget = fileSystemWidget(project)
            if (resetOnSuccess) fileSystemWidget?.reset()
            if (runReplOnSuccess) fileSystemWidget?.activateRepl()
            return EmptyRunProfileState.INSTANCE
        } else {
            return null
        }
    }

    override fun checkConfiguration() {
        super.checkConfiguration()

        val pythonService = project.service<MpyPythonService>() /*?: throw RuntimeConfigurationError(
            "MicroPython support was not enabled for this project",
            Runnable { ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java) }
        )*/
        val validationResult = pythonService.checkValid()
        if (validationResult != ValidationResult.OK) {
            if (validationResult.quickFix != null) {
                val runQuickFix = Runnable {
                    validationResult.quickFix.run(null)
                }
                throw RuntimeConfigurationError(validationResult.errorMessage, runQuickFix)
            } else {
                throw RuntimeConfigurationError(validationResult.errorMessage)
            }
        }
        pythonService.findValidPyhonSdk() ?: throw RuntimeConfigurationError("Python interpreter was not found")
    }

    override fun suggestedName() = "Flash ${PathUtil.getFileName(path)}"

    override fun getValidModules(): MutableCollection<Module> {
        TODO("Not yet implemented")
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("path", path)
        element.setAttribute("run-repl-on-success", if (runReplOnSuccess) "yes" else "no")
        element.setAttribute("reset-on-success", if (resetOnSuccess) "yes" else "no")
        element.setAttribute("synchronize", if (synchronize) "yes" else "no")
        element.setAttribute("exclude-paths", if (excludePaths) "yes" else "no")
        element.setAttribute("ftp", if (useFTP) "yes" else "no")

        if (excludedPaths.isNotEmpty()) {
            val excludedPathsElement = Element("excluded-paths")
            excludedPaths.forEach { path ->
                val pathElement = Element("path")
                pathElement.setAttribute("value", path)
                excludedPathsElement.addContent(pathElement)
            }
            element.addContent(excludedPathsElement)
        }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        configurationModule.readExternal(element)
        element.getAttributeValue("path")?.let {
            path = it
        }
        element.getAttributeValue("run-repl-on-success")?.let {
            runReplOnSuccess = it == "yes"
        }
        element.getAttributeValue("reset-on-success")?.let {
            resetOnSuccess = it == "yes"
        }
        element.getAttributeValue("synchronize")?.let {
            synchronize = it == "yes"
        }
        element.getAttributeValue("exclude-paths")?.let {
            excludePaths = it == "yes"
        }
        element.getAttributeValue("ftp")?.let {
            useFTP = it == "yes"
        }

        excludedPaths.clear()

        excludedPaths.clear()
        element.getChild("excluded-paths")?.let { excludedPathsElement ->
            excludedPathsElement.getChildren("path").forEach { pathElement ->
                pathElement.getAttributeValue("value")?.let { path ->
                    excludedPaths.add(path)
                }
            }
        }
    }
}
