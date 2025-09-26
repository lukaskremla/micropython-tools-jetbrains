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

package dev.micropythontools.run

import com.intellij.execution.configurations.ConfigurationTypeBase
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.icons.MpyIcons

internal class MpyRunConfType : ConfigurationTypeBase(
    ID,
    MpyBundle.message("run.conf.type.name"),
    MpyBundle.message("run.conf.type.description"),
    MpyIcons.plugin
) {
    init {
        // Multiple factories can be added here to achieve similar behavior to, for example docker, configurations
        addFactory(MpyRunConfUploadFactory(this))
        addFactory(MpyRunConfExecuteFactory(this))
        addFactory(MpyRunConfMpyCrossFactory(this))
    }

    companion object {
        const val ID = "micropython-tools-configuration-type"
    }
}