package dev.micropythontools.intellij.settings

import com.intellij.openapi.components.BaseState

class MpyState : BaseState() {
    var webReplUrl by string(DEFAULT_WEBREPL_URL)
    var uart by property(true)
    var portName by string("")
    var ssid by string("")
    var activeStubsPath by string("")
}