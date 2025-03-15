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

package dev.micropythontools.sourceroots

import org.jdom.Element
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.serialization.module.JpsModuleSourceRootPropertiesSerializer

class MpySourceRootPropertiesSerializer :
    JpsModuleSourceRootPropertiesSerializer<JavaSourceRootProperties>(
        MpySourceRootType.SOURCE, "micropython-source"
    ) {

    override fun loadProperties(sourceRootTag: Element): JavaSourceRootProperties {
        return MpySourceRootType.SOURCE.createDefaultProperties()
    }

    override fun saveProperties(properties: JavaSourceRootProperties, sourceRootTag: Element) {
        // No additional properties to save
    }
}