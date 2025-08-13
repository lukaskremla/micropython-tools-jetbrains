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
import com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.readText
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.util.ui.UIUtil
import com.jediterm.terminal.ui.JediTermWidget
import com.jetbrains.python.PythonFileType
import dev.micropythontools.ui.MpyComponentRegistryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class MpyInterruptAction : MpyReplAction(
    "Interrupt",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.Suspend
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        reporter.text("Interrupting...")
        deviceService.interrupt()
    }
}

internal class MpySoftResetAction : MpyReplAction(
    "Reset",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.CONNECTED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "Reset cancelled"
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.StopAndRestart
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        reporter.text("Resetting...")
        deviceService.reset()
    }
}

internal class MpyClearReplAction : MpyAction(
    "Clear REPL",
    MpyActionOptions(
        visibleWhen = VisibleWhen.ALWAYS,
        enabledWhen = EnabledWhen.ALWAYS,
        requiresConnection = false,
        requiresRefreshAfter = false
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.GC
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun performAction(e: AnActionEvent) {
        val terminal = project.service<MpyComponentRegistryService>().getTerminal()
        val widget = UIUtil.findComponentOfType(terminal?.component, JediTermWidget::class.java)
        widget?.terminalPanel?.clearBuffer()
    }
}

internal class MpyExecuteFileInReplAction : MpyReplAction(
    "Execute File in REPL",
    MpyActionOptions(
        visibleWhen = VisibleWhen.PLUGIN_ENABLED,
        enabledWhen = EnabledWhen.PLUGIN_ENABLED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "REPL execution cancelled"
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.Rerun
    }

    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        ApplicationManager.getApplication().invokeLater {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        val code = e.getData(CommonDataKeys.VIRTUAL_FILE)?.readText() ?: return
        reporter.text("Executing file in REPL...")
        deviceService.instantRun(code)
    }

    override fun customUpdate(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        val file = files.firstOrNull() ?: return

        val excludedItems = transferService.collectExcluded()

        if (files.any { it.isDirectory } || excludedItems.any { excludedItem ->
                files.any { candidate ->
                    VfsUtil.isAncestor(excludedItem, candidate, false)
                }
            }
        ) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isEnabled = files.size == 1 &&
                (file.fileType == PythonFileType.INSTANCE || file.extension == "mpy")
    }
}

internal class MpyExecuteFragmentInReplAction : MpyReplAction(
    "Execute Fragment in REPL",
    MpyActionOptions(
        visibleWhen = VisibleWhen.PLUGIN_ENABLED,
        enabledWhen = EnabledWhen.PLUGIN_ENABLED,
        requiresConnection = true,
        requiresRefreshAfter = false,
        cancelledMessage = "REPL execution cancelled"
    )
) {
    init {
        this.templatePresentation.icon = AllIcons.Actions.Run_anything
    }

    private fun editor(project: Project?): Editor? =
        project?.let { FileEditorManager.getInstance(it).selectedTextEditor }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override suspend fun performAction(e: AnActionEvent, reporter: RawProgressReporter) {
        val code = withContext(Dispatchers.EDT) {
            val editor = editor(project) ?: return@withContext null
            val emptySelection = editor.selectionModel.getSelectedText(true).isNullOrBlank()
            reporter.text(if (emptySelection) "Executing Line in REPL..." else "Executing Selection in REPL...")

            var text = editor.selectionModel.getSelectedText(true)
            if (text.isNullOrBlank()) {
                try {
                    val range = EditorUtil.calcCaretLineTextRange(editor)
                    if (!range.isEmpty) {
                        text = editor.document.getText(range).trim()
                    }
                } catch (_: Throwable) {
                }
            }
            text
        }
        if (!code.isNullOrBlank()) {
            deviceService.instantRun(code)
        }
    }

    override fun customUpdate(e: AnActionEvent) {
        val editor = editor(e.project)
        if (editor == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val emptySelection = editor.selectionModel.getSelectedText(true).isNullOrBlank()
        e.presentation.text =
            if (emptySelection) "Execute Line in REPL" else "Execute Selection in REPL"
    }
}
