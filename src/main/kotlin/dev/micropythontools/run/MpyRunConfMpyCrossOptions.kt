package dev.micropythontools.run

import com.intellij.execution.configurations.RunConfigurationOptions

internal class MpyRunConfMpyCrossOptions : RunConfigurationOptions() {
    var uploadMode by property(0)
    var selectedPaths by list<String>()
    var path by string("")
    var outputPath by string(null)
    var pathRelativeToOutput by string("")
}