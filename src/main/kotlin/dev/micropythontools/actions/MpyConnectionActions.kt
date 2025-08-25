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

package dev.micropythontools.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.platform.util.progress.RawProgressReporter
import dev.micropythontools.communication.State
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.icons.MpyIcons

internal class MpyConnectAction : MpyReplAction(
    MpyBundle.message("action.connect.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.DISCONNECTED,
        enabledWhen = EnabledWhen.DISCONNECTED,
        requiresConnection = false,
        requiresRefreshAfter = false,
        cancelledMessage = MpyBundle.message("action.connect.cancelled"),
        timedOutMessage = MpyBundle.message("action.connect.timeout")
    )
) {
    init {
        this.templatePresentation.icon = MpyIcons.connectActive
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        deviceService.doConnect(reporter, isConnectAction = true)
    }
}

internal class MpyDisconnectAction : MpyReplAction(
    MpyBundle.message("action.disconnect.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.CONNECTED,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = false,
        requiresRefreshAfter = false,
        MpyBundle.message("action.disconnect.cancelled"),
        MpyBundle.message("action.disconnect.timeout")
    )
) {
    init {
        this.templatePresentation.icon = MpyIcons.connectActive
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        deviceService.disconnect(reporter)
    }

    override fun customUpdate(e: AnActionEvent) {
        if (deviceService.state != State.CONNECTED) {
            e.presentation.isEnabledAndVisible = false
        } else {
            Toggleable.setSelected(e.presentation, true)
        }
    }
}