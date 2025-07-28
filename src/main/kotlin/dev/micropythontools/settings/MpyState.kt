/*
 * Copyright 2024-2025 Lukas Kremla
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

package dev.micropythontools.settings

import com.intellij.openapi.components.BaseState

/**
 * @author Lukas Kremla
 */
internal class MpyState : BaseState() {
    var isPluginEnabled by property(false)
    var usingUart by property(true)
    var enableManualEditing by property(false)
    var filterManufacturers by property(true)
    var portName by string("")
    var increaseBaudrateForFileTransfers by string("")
    var increasedFileTransferBaudrate by string("")
    var webReplIp by string(DEFAULT_WEBREPL_IP)
    var webReplPort by property(DEFAULT_WEBREPL_PORT)
    var legacyVolumeSupportEnabled by property(false)
    var showUploadPreviewDialog by property(true)
    var areStubsEnabled by property(true)
}