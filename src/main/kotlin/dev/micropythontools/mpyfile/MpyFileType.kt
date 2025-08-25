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

package dev.micropythontools.mpyfile

import com.intellij.openapi.fileTypes.UserBinaryFileType
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.icons.MpyIcons
import javax.swing.Icon

internal class MpyFileType : UserBinaryFileType() {
    override fun getName() = MpyBundle.message("mpy.file.type.name")
    override fun getDescription() = MpyBundle.message("mpy.file.type.description")
    override fun getDefaultExtension() = "mpy"
    override fun getIcon(): Icon = MpyIcons.plugin
}