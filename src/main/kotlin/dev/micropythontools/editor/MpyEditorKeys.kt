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

package dev.micropythontools.editor

import com.intellij.openapi.util.Key

internal val ORIGINAL_CONTENT_KEY = Key.create<ByteArray>("Mpy.OriginalContent")
internal val REMOTE_PATH_KEY = Key.create<String>("Mpy.RemotePath")
internal val LISTENER_ADDED_KEY = Key.create<Boolean>("Mpy.ListenerAdded")
internal val MPY_TOOLS_EDITABLE_FILE_SIGNATURE_KEY = Key.create<String>("Mpy.EditableFileSignature")

internal const val MPY_TOOLS_EDITABLE_FILE_SIGNATURE =
    "c3748df410294e7af89bab26035944a2e2dca9cbd007a75b04398f4c1850ba9f"