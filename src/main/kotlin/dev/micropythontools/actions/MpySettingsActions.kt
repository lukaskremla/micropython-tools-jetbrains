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

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.MpyConfigurable

internal class MpyOpenSettingsAction : MpyAction(
    MpyBundle.message("action.settings.text"),
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.ALWAYS,
        requiresConnection = false,
        requiresRefreshAfter = false,
        canRunInBackground = false,
        "",
        ""
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.General.GearPlain
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun performAction(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, MpyConfigurable::class.java)
    }
}
