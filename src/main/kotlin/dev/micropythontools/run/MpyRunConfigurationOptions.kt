package dev.micropythontools.run

import com.intellij.execution.configurations.RunConfigurationOptions

class MpyRunConfigurationOptions : RunConfigurationOptions() {
    var path by string("")
    var runReplOnSuccess by property(false)
    var resetOnSuccess by property(true)
    var useFTP by property(false)
    var synchronize by property(false)
    var excludePaths by property(false)
    var excludedPaths by list<String>()
}