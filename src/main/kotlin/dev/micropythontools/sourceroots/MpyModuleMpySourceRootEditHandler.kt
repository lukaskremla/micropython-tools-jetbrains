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

package dev.micropythontools.sourceroots

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.roots.ui.configuration.ModuleSourceRootEditHandler
import com.intellij.ui.JBColor
import dev.micropythontools.settings.mpySourceIcon
import org.jetbrains.annotations.Nls
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import java.awt.Color
import javax.swing.Icon

internal class MpyModuleMpySourceRootEditHandler :
    ModuleSourceRootEditHandler<JavaSourceRootProperties>(MpySourceRootType.SOURCE) {
    override fun getRootTypeName(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "MicroPython Sources"
    }

    override fun getRootIcon(): Icon {
        return mpySourceIcon
    }

    override fun getFolderUnderRootIcon(): Icon? {
        return rootIcon
    }

    override fun getMarkRootShortcutSet(): CustomShortcutSet? {
        return null
    }

    override fun getRootsGroupTitle(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "MicroPython Sources"
    }

    override fun getRootsGroupColor(): Color {
        return JBColor(
            Color(75, 188, 236),  // Light theme
            Color(50, 185, 236)   // Dark theme
        )
    }

    override fun getUnmarkRootButtonText(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "Unmark as MicroPython Sources"
    }
}