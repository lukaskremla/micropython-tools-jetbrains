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

package dev.micropythontools.templates

import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.NlsContexts
import com.intellij.platform.ProjectTemplate
import javax.swing.Icon


class MpyProjectTemplate : ProjectTemplate {
    override fun getName(): @NlsContexts.Label String = "MicroPython Project"

    override fun getDescription(): @NlsContexts.DetailedDescription String = "Creates a basic MicroPython project structure with main.py and boot.py"

    override fun getIcon(): Icon? = IconLoader.getIcon("/icons/pluginIcon.svg", this::class.java)

    override fun createModuleBuilder(): ModuleBuilder {
        return MpyModuleBuilder()
    }

    @Deprecated("Deprecated in Java")
    override fun validateSettings(): ValidationInfo? {
        return null
    }
}