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

package dev.micropythontools.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.platform.util.progress.RawProgressReporter
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.communication.MpyTransferService
import dev.micropythontools.communication.State
import dev.micropythontools.communication.performReplAction
import dev.micropythontools.settings.MpySettingsService

// ===== ENUMS AND BASE CLASSES =====

internal enum class VisibleWhen {
    ALWAYS, PLUGIN_ENABLED, CONNECTED, DISCONNECTED
}

internal enum class EnabledWhen {
    ALWAYS, PLUGIN_ENABLED, CONNECTED, DISCONNECTED
}

/**
 * Data class containing options of MpyActions. [visibleWhen] and [enabledWhen] only affect the default states.
 * They can be modified as needed by overriding the customUpdate() function
 */
internal data class MpyActionOptions(
    val visibleWhen: VisibleWhen,
    val enabledWhen: EnabledWhen,
    val requiresConnection: Boolean,
    val requiresRefreshAfter: Boolean,
    val cancelledMessage: String,
    val timedOutMessage: String
)

internal data class DialogResult(
    val shouldExecute: Boolean,
    val resultToPass: Any?
)

internal abstract class MpyActionBase(
    private val text: String,
    private val options: MpyActionOptions
) : DumbAwareAction(text) {

    protected lateinit var project: Project
    protected lateinit var settings: MpySettingsService
    protected lateinit var deviceService: MpyDeviceService
    protected lateinit var transferService: MpyTransferService

    protected fun initialize(event: AnActionEvent): Boolean {
        val project = event.project ?: return false
        this.project = project
        this.settings = project.service<MpySettingsService>()
        this.deviceService = project.service<MpyDeviceService>()
        this.transferService = project.service<MpyTransferService>()
        return true
    }

    final override fun update(e: AnActionEvent) {
        super.update(e)

        // Retrieve the services manually to properly handle null states
        val project = e.project
        val settings = project?.service<MpySettingsService>()
        val deviceService = project?.service<MpyDeviceService>()

        val isPluginEnabled = settings?.state?.isPluginEnabled == true
        val isConnected =
            deviceService != null && (deviceService.state == State.CONNECTING || deviceService.state == State.CONNECTED || deviceService.state == State.TTY_DETACHED)

        e.presentation.isVisible = when (options.visibleWhen) {
            VisibleWhen.ALWAYS -> true
            VisibleWhen.PLUGIN_ENABLED -> isPluginEnabled
            VisibleWhen.CONNECTED -> isConnected
            VisibleWhen.DISCONNECTED -> !isConnected
        }

        e.presentation.isEnabled = when (options.enabledWhen) {
            EnabledWhen.ALWAYS -> true
            EnabledWhen.PLUGIN_ENABLED -> isPluginEnabled
            EnabledWhen.CONNECTED -> isConnected && isPluginEnabled
            EnabledWhen.DISCONNECTED -> !isConnected && isPluginEnabled
        }

        // Attempt initialization before the customUpdate method, which relies on the properties being initialized
        val wasInitialized = initialize(e)
        if (!wasInitialized) return

        customUpdate(e)
    }

    open val actionDescription: @NlsContexts.DialogMessage String
        get() = text

    open fun customUpdate(e: AnActionEvent) = Unit
}

internal abstract class MpyAction(
    text: String,
    protected val options: MpyActionOptions
) : MpyActionBase(
    text,
    options
) {
    final override fun actionPerformed(e: AnActionEvent) {
        val wasInitialized = initialize(e)
        if (!wasInitialized) return

        performAction(e)
    }

    abstract fun performAction(e: AnActionEvent)
}

internal abstract class MpyReplAction(
    text: String,
    protected val options: MpyActionOptions
) : MpyActionBase(
    text,
    options
) {
    final override fun actionPerformed(e: AnActionEvent) {
        val wasInitialized = initialize(e)
        if (!wasInitialized) return

        val dialogResult = dialogToShowFirst(e)

        if (!dialogResult.shouldExecute) return

        performReplAction(
            project = project,
            connectionRequired = options.requiresConnection,
            requiresRefreshAfter = options.requiresRefreshAfter,
            description = actionDescription,
            cancelledMessage = options.cancelledMessage,
            timedOutMessage = options.timedOutMessage,
            { reporter ->
                performAction(e, reporter, dialogResult.resultToPass)
            }
        )
    }

    open suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) = Unit

    open suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter, dialogResult: Any?) =
        performAction(e, reporter)

    open fun dialogToShowFirst(e: AnActionEvent): DialogResult = DialogResult(true, null)
}