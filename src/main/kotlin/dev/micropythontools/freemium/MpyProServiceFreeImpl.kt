/*
 * Copyright 2025 Lukas Kremla
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

package dev.micropythontools.freemium

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.NlsContexts
import com.intellij.platform.util.progress.RawProgressReporter
import dev.micropythontools.communication.DeviceInformation
import dev.micropythontools.communication.MpyDeviceService
import dev.micropythontools.core.MpyScripts
import dev.micropythontools.i18n.MpyBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class MpyProServiceFreeImpl() : MpyProServiceInterface {
    override val hasProBits: Boolean = false
    override val isLicensed: Boolean = false

    private fun fail(): Nothing =
        throw ProFeatureUnavailable("Pro feature is unavailable (not installed or not licensed).")

    override suspend fun initializeDevice(project: Project) {
        val deviceService = project.service<MpyDeviceService>()

        val scriptFileName = "initialize_device.py"
        val initializeDeviceScript = MpyScripts.retrieveMpyScriptAsString(scriptFileName)

        val scriptResponse = deviceService.blindExecute(initializeDeviceScript)

        if (!scriptResponse.contains("ERROR")) {
            val responseFields = scriptResponse.split("&")

            deviceService.deviceInformation = DeviceInformation(
                defaultFreeMem = responseFields.getOrNull(0)?.toIntOrNull()
                    ?: throw RuntimeException(MpyBundle.message("comm.error.initialization.freemem")),
                hasCRC32 = responseFields.getOrNull(1)?.toBoolean() == true,
                canEncodeBase64 = responseFields.getOrNull(2)?.toBoolean() == true,
                canDecodeBase64 = responseFields.getOrNull(3)?.toBoolean() == true
            )
        } else {
            deviceService.deviceInformation = DeviceInformation()
        }

        val messageKey: String? = if (!deviceService.deviceInformation.hasCRC32) {
            if (!deviceService.deviceInformation.canDecodeBase64) {
                "comm.error.initialization.dialog.message.missing.crc32.and.base64"
            } else {
                "comm.error.initialization.dialog.message.missing.crc32"
            }
        } else if (!deviceService.deviceInformation.canDecodeBase64) {
            "comm.error.initialization.dialog.message.missing.base64"
        } else {
            null
        }

        if (messageKey != null) {
            withContext(Dispatchers.EDT) {
                MessageDialogBuilder.Message(
                    MpyBundle.message("comm.error.initialization.dialog.title"),
                    MpyBundle.message(messageKey)
                ).asWarning()
                    .buttons(MpyBundle.message("comm.error.initialization.dialog.acknowledge.button"))
                    .show(project)
            }
        }
    }

    override fun <T> performReplAction(
        project: Project,
        connectionRequired: Boolean,
        requiresRefreshAfter: Boolean,
        canRunInBackground: Boolean,
        @NlsContexts.DialogMessage description: String,
        cancelledMessage: String,
        timedOutMessage: String,
        action: suspend (RawProgressReporter) -> T,
        cleanUpAction: (suspend (RawProgressReporter) -> Unit)?,
        finalCheckAction: (() -> Unit)?
    ): T = fail()

    override suspend fun upload(
        fullName: String,
        content: ByteArray,
        progressCallback: (uploadedBytes: Double) -> Unit,
        freeMemBytes: Int,
        canDecodeBase64: Boolean,
        doBlindExecute: suspend (
            command: String,
            progressCallback: ((uploadedBytes: Double) -> Unit),
            totalProgressCommandSize: Int,
            payloadSize: Int,
            redirectToRepl: Boolean,
            shouldStayDetached: Boolean
        ) -> String
    ) = fail()
}