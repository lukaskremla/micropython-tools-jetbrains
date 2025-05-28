package dev.micropythontools.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

internal class MpyRunConfMpyCrossFactory(type: MpyRunConfType) : ConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return MpyRunConfMpyCross(project, this, "Mpy Cross")
    }

    override fun getName(): String {
        return "Mpy-Cross"
    }

    override fun getId(): String {
        return "micropython-tools-mpy-cross-configuration"
    }

    override fun getOptionsClass() = MpyRunConfMpyCrossOptions::class.java
}