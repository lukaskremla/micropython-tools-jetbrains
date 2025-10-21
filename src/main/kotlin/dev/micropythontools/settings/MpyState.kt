package dev.micropythontools.settings

import com.intellij.openapi.components.BaseState

internal class MpyState : BaseState() {
    var isPluginEnabled by property(false)
    var autoClearRepl by property(true)
    var usingUart by property(true)
    var enableManualEditing by property(false)
    var filterManufacturers by property(true)
    var portName by string("")
    var webReplIp by string(DEFAULT_WEBREPL_IP)
    var webReplPort by property(DEFAULT_WEBREPL_PORT)
    var backgroundUploadsDownloads by property(false)
    var compressUploads by property(true)
    var legacyVolumeSupportEnabled by property(false)
    var showUploadPreviewDialog by property(true)
    var areStubsEnabled by property(true)
}