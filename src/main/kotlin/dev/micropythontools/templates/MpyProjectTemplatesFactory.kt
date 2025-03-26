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

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.NewProjectWizardActivityKey
import com.intellij.openapi.util.IconLoader
import com.intellij.platform.ProjectTemplate
import com.intellij.platform.ProjectTemplatesFactory
import javax.swing.Icon

class MpyProjectTemplatesFactory : ProjectTemplatesFactory() {
    companion object {
        private const val MICROPYTHON_TOOLS_GROUP = "MicroPython Tools"
    }

    override fun getGroups(): Array<String> {

        NewProjectWizardActivityKey
        println("get groups")
        return arrayOf(MICROPYTHON_TOOLS_GROUP)
    }

    override fun createTemplates(group: String?, context: WizardContext): Array<ProjectTemplate> {
        println("In create template")
        if (group != MICROPYTHON_TOOLS_GROUP) {
            println("wrong group")
            return emptyArray()
        }

        println("returning factories")

        return arrayOf(
            MpyProjectTemplate()
        )
    }

    override fun getGroupIcon(group: String): Icon? {
        println("icon")
        return IconLoader.getIcon("/icons/pluginIcon.svg", this::class.java)
    }

    override fun getGroupWeight(group: String): Int {
        println("width")
        return 30
    }
}
