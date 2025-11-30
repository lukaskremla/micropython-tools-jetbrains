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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.platform.util.progress.SequentialProgressReporter
import javax.swing.Icon

internal class ProFeatureUnavailable(message: String) : IllegalStateException(message)


internal interface MpyProServiceInterface {
    val hasProBits: Boolean
    val isLicensed: Boolean?
    val isActive: Boolean
    val lockIconToShow: Icon
    val lockIconToolTipText: String

    fun requestLicense(message: String? = null)

    suspend fun initializeDevice(project: Project)

    fun <T> performReplAction(
        project: Project,
        connectionRequired: Boolean,
        requiresRefreshAfter: Boolean,
        canRunInBackground: Boolean,
        @NlsContexts.DialogMessage description: String,
        cancelledMessage: String,
        timedOutMessage: String,
        action: suspend (RawProgressReporter) -> T,
        cleanUpAction: (suspend (RawProgressReporter) -> Unit)? = null,
        finalCheckAction: (() -> Unit)? = null
    ): T?

    fun getCompressUploadTotalSize(fileToTargetPath: MutableMap<VirtualFile, String>): Double

    suspend fun compressUpload(
        project: Project,
        fullName: String,
        rawContent: ByteArray,
        isCompressible: Boolean,
        progressCallback: (uploadedBytes: Double) -> Unit,
        freeMemBytes: Int,
        txtUpload: (
            fullName: String,
            content: ByteArray,
            openMode: String,
            index: Int,
            maxChunkSize: Int
        ) -> Pair<String, Int>,
        binUpload: (
            fullName: String,
            content: ByteArray,
            openMode: String
        ) -> String,
        doBlindExecute: suspend (
            command: String,
            progressCallback: ((uploadedBytes: Double) -> Unit),
            totalProgressCommandSize: Int,
            payloadSize: Int,
            redirectToRepl: Boolean,
            shouldStayDetached: Boolean
        ) -> String
    )

    fun checkNumberOfMissingProDependencies(project: Project): Int

    fun ensureProDependenciesInstalled(project: Project, reporter: SequentialProgressReporter)
}