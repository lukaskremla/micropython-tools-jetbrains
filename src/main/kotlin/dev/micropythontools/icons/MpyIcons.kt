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

package dev.micropythontools.icons

import com.intellij.openapi.util.IconLoader

internal object MpyIcons {
    val connectActive = IconLoader.getIcon("/icons/connectActive.svg", MpyIcons::class.java)
    val micropythonTw = IconLoader.getIcon("/icons/micropythonTw.svg", MpyIcons::class.java)
    val Source = IconLoader.getIcon("/icons/MpySource.svg", MpyIcons::class.java)
    val plugin = IconLoader.getIcon("/icons/pluginIcon.svg", MpyIcons::class.java)
    val Volume = IconLoader.getIcon("/icons/volume.svg", MpyIcons::class.java)
}
