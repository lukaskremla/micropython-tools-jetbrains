package dev.micropythontools.run

import com.intellij.execution.configurations.RunConfigurationOptions

class MpyRunConfMpyCrossOptions : RunConfigurationOptions() {
    var compileMode by property(0)
    var selectedPaths by list<String>()
    var path by string("")
    var outputMode by property(0)
    var outputRelativeToProjectPath by string("build/")
    var outputPath by string("")
}