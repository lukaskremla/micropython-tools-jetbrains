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

package dev.micropythontools.ui

import com.intellij.openapi.components.Service
import com.intellij.ui.content.Content


@Service(Service.Level.PROJECT)
class MpyComponentRegistryService {
    private var fileSystemWidget: FileSystemWidget? = null
    private var terminalContent: Content? = null

    fun registerFileSystem(widget: FileSystemWidget) {
        fileSystemWidget = widget
    }

    fun registerTerminalContent(content: Content) {
        terminalContent = content
    }

    fun getFileSystemWidget() = fileSystemWidget
    fun getTerminalContent() = terminalContent
}