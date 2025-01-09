/*
 * Copyright 2000-2024 JetBrains s.r.o.
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

package dev.micropythontools.intellij.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.jetbrains.python.run.PythonConfigurationFactoryBase
import javax.swing.Icon

/**
 * @author Mikhail Golubev
 */
class MpyConfigurationType : ConfigurationType {
    private val factory = object : PythonConfigurationFactoryBase(this) {
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            MpyRunConfiguration(project, this)

        override fun getId(): String = "MicroPython Tools"
    }

    override fun getIcon(): Icon = IconLoader.getIcon("/icons/micropython.svg", MpyConfigurationType::class.java)

    override fun getConfigurationTypeDescription(): String = "MicroPython Tools run configuration"

    override fun getId(): String = "MpyConfigurationType"

    override fun getDisplayName(): String = "MicroPython Tools"

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)
}

fun getInstance(): MpyConfigurationType =
    ConfigurationTypeUtil.findConfigurationType(MpyConfigurationType::class.java)