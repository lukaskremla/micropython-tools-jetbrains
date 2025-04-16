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
class MpyState : BaseState() {
    var settingsVersion by property(0)
    var isPluginEnabled by property(false)
    var usingUart by property(true)
    var filterManufacturers by property(true)
    var portName by string("")
    var webReplUrl by string(DEFAULT_WEBREPL_URL)
    var compileToBytecode by property(false)
    var useSockets by property(false)
    var requireMinimumSocketTransferSize by property(false)
    var minimumSocketTransferSize by property(200)
    var useFTP by property(false) // TODO: Delete in 0.6.0
    var requireMinimumFTPUploadSize by property(false) // TODO: Delete in 0.6.0
    var minimumFTPUploadSize by property(200) // TODO: Delete in 0.6.0
    var areStubsEnabled by property(true)
    var activeStubsPackage by string("")
}