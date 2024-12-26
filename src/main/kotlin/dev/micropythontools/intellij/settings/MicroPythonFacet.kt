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
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.Module
import com.jetbrains.python.facet.FacetLibraryConfigurator
import com.jetbrains.python.facet.LibraryContributingFacet
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.sdk.PySdkUtil
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.sdk.sdkSeemsValid

/**
 * @author vlan
 */
class MicroPythonFacet(
    facetType: FacetType<out Facet<*>, *>, module: Module, name: String,
    configuration: MicroPythonFacetConfiguration, underlyingFacet: Facet<*>?
) : LibraryContributingFacet<MicroPythonFacetConfiguration>(facetType, module, name, configuration, underlyingFacet) {

    companion object {
        private const val PLUGIN_ID = "micropython-tools-jetbrains"

        val scriptsPath: String
            get() = "${pluginDescriptor.pluginPath}/scripts"

        private val pluginDescriptor: IdeaPluginDescriptor
            get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: throw RuntimeException("The $PLUGIN_ID plugin cannot find itself")
    }

    override fun initFacet() {
        updateLibrary()
    }

    override fun updateLibrary() {
        //
    }

    override fun removeLibrary() {
        FacetLibraryConfigurator.detachPythonLibrary(module, "MicroPython Tools")
    }

    fun checkValid(): ValidationResult {
        val sdk = PythonSdkUtil.findPythonSdk(module)
        if (sdk == null || (!sdk.sdkSeemsValid) || PySdkUtil.getLanguageLevelForSdk(sdk).isOlderThan(LanguageLevel.PYTHON310)) {
            return ValidationResult("MicroPython tools plugin requires valid Python 3.10+ SDK")
        }
        return ValidationResult.OK
    }

    val pythonPath: String?
        get() = PythonSdkUtil.findPythonSdk(module)?.homePath

}

val Module.microPythonFacet: MicroPythonFacet?
    get() = FacetManager.getInstance(this).getFacetByType(MicroPythonFacetType.ID)
