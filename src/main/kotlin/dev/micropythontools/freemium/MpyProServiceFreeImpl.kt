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

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.platform.util.progress.RawProgressReporter

internal class MpyProServiceFreeImpl() : MpyProServiceInterface {
    override val hasProBits: Boolean = false
    override val isLicensed: Boolean = false

    private fun fail(): Nothing =
        throw ProFeatureUnavailable("Pro feature is unavailable (not installed or not licensed).")

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