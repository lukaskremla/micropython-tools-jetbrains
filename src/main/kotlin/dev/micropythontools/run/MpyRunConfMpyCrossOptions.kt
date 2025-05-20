package dev.micropythontools.run

import com.intellij.execution.configurations.RunConfigurationOptions

internal class MpyRunConfMpyCrossOptions : RunConfigurationOptions() {
    var uploadMode by property(0)
    var selectedPaths by list<String>()
    var path by string("")
    var uploadToPath by string("/")
}