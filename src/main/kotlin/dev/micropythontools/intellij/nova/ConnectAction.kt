/*
 * Copyright 2000-2024 JetBrains s.r.o.
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

package dev.micropythontools.intellij.nova

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.modules
import com.intellij.openapi.ui.Messages
import dev.micropythontools.intellij.settings.MicroPythonProjectConfigurable
import dev.micropythontools.intellij.settings.microPythonFacet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @author elmot
 */
class ConnectAction(text: String = "Connect") : ReplAction(text, false, false) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override val actionDescription: String = "Connect"

    override suspend fun performAction(fileSystemWidget: FileSystemWidget) {
        doConnect(fileSystemWidget)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = when (fileSystemWidget(e)?.state) {
            State.DISCONNECTING, State.DISCONNECTED, null -> true
            State.CONNECTING, State.CONNECTED, State.TTY_DETACHED -> false
        }
    }
}

suspend fun doConnect(fileSystemWidget: FileSystemWidget) {
    if (fileSystemWidget.state == State.CONNECTED) return
    val facet = fileSystemWidget.project.modules.firstNotNullOfOrNull { it.microPythonFacet } ?: return
    var msg: String? = null
    val connectionParameters: ConnectionParameters?
    if (facet.configuration.uart) {
        val portName = facet.configuration.portName
        if (portName.isBlank()) {
            msg = "No port is selected"
            connectionParameters = null
        } else {
            connectionParameters = ConnectionParameters(portName)
        }

    } else {
        val url = facet.configuration.webReplUrl
        val password = fileSystemWidget.project.service<MpySupportService>().retrieveWebReplPassword(url)
        msg = messageForBrokenUrl(url)
        if (password.isBlank()) {
            msg = "Empty password"
            connectionParameters = null
        } else {
            connectionParameters = ConnectionParameters(url, password)
        }

    }
    if (msg != null) {
        withContext(Dispatchers.EDT) {
            val result = Messages.showIdeaMessageDialog(
                fileSystemWidget.project,
                msg,
                "Cannot Connect",
                arrayOf("OK", "Settings..."),
                1,
                AllIcons.General.ErrorDialog,
                null
            )
            if (result == 1) {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(fileSystemWidget.project, MicroPythonProjectConfigurable::class.java)
            }
        }
    } else {
        if (connectionParameters != null) {
            fileSystemWidget.setConnectionParams(connectionParameters)
            fileSystemWidget.connect()
            try {
                if (fileSystemWidget.state == State.CONNECTED) {
                    fileSystemWidget.refresh()
                }
            } finally {
                ActivityTracker.getInstance().inc()
            }
        }
    }
}