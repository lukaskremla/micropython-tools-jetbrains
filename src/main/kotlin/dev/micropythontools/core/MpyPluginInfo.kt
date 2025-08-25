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

package dev.micropythontools.core

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import dev.micropythontools.i18n.MpyBundle

internal object MpyPluginInfo {
    const val PLUGIN_ID = "micropython-tools-jetbrains"

    val pluginDescriptor: IdeaPluginDescriptor
        get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
            ?: throw RuntimeException(MpyBundle.message("core.error.plugin.cannot.find.itself", PLUGIN_ID))

    val sandboxPath: String
        get() = pluginDescriptor.pluginPath.toString()
}