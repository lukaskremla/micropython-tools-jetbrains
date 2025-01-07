/*
 * Copyright 2000-2024 JetBrains s.r.o.
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

package dev.micropythontools.intellij.settings

import com.intellij.facet.Facet
import com.intellij.facet.FacetType
import com.intellij.facet.FacetTypeId
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import com.jetbrains.python.PythonModuleTypeBase
import javax.swing.Icon

/**
 * @author vlan
 */
class MpyFacetType : FacetType<MpyFacet, MpyFacetConfiguration>(ID, STRING_ID, PRESENTABLE_NAME), DumbAware {
    companion object {
        const val STRING_ID = "MicroPython Tools"
        const val PRESENTABLE_NAME = "MicroPython Tools"
    }

    override fun createDefaultConfiguration() = MpyFacetConfiguration()

    override fun createFacet(
        module: Module, name: String, configuration: MpyFacetConfiguration,
        underlyingFacet: Facet<*>?
    ) =
        MpyFacet(this, module, name, configuration, underlyingFacet)

    override fun isSuitableModuleType(moduleType: ModuleType<*>?) = moduleType is PythonModuleTypeBase

    override fun getIcon(): Icon = LOGO
}

val ID = FacetTypeId<MpyFacet>(MpyFacetType.STRING_ID)
val LOGO = IconLoader.getIcon("/icons/miiicropython.svg", MpyFacetType::class.java)
fun getInstance() = FacetType.findInstance(MpyFacetType::class.java)!!