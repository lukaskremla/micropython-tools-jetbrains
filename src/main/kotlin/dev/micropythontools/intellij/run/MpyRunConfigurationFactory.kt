package dev.micropythontools.intellij.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

class MpyRunConfigurationFactory(type: MpyRunConfigurationType) : ConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return MpyRunConfiguration(project, this, "MicroPython Tools")
    }

    override fun getId(): String {
        return "micropython-tools-flash-configuration"
    }

    override fun getOptionsClass() = MpyRunConfigurationOptions::class.java
}