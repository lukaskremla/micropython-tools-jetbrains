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

import com.intellij.facet.FacetConfiguration
import com.intellij.facet.ui.FacetEditorContext
import com.intellij.facet.ui.FacetEditorTab
import com.intellij.facet.ui.FacetEditorValidator
import com.intellij.facet.ui.FacetValidatorsManager
import org.jdom.Element

/**
 * @author vlan, elmot
 */
const val DEFAULT_WEBREPL_URL = "ws://192.168.4.1:8266"


class MicroPythonFacetConfiguration : FacetConfiguration {
    var webReplUrl: String = DEFAULT_WEBREPL_URL
    var uart: Boolean = true
    var portName: String = "COM1"
    var ssid: String = ""
    var activeStubsPath: String = ""

    override fun createEditorTabs(editorContext: FacetEditorContext, validatorsManager: FacetValidatorsManager): Array<FacetEditorTab> {
        val facet = editorContext.facet as MicroPythonFacet
        validatorsManager.registerValidator(object : FacetEditorValidator() {
            override fun check() = facet.checkValid()
        })
        return arrayOf(MicroPythonFacetEditorTab(this, facet))
    }

    @Deprecated("Deprecated in Java")
    override fun readExternal(element: Element?) {
        val deviceElement = element?.getChild("device")
        webReplUrl = deviceElement?.getAttributeValue("web-repl-url") ?: DEFAULT_WEBREPL_URL
        uart = deviceElement?.getAttributeBooleanValue("uart-connection") ?: true
        portName = deviceElement?.getAttributeValue("port") ?: "COM1"
        ssid = deviceElement?.getAttributeValue("ssid") ?: ""
        activeStubsPath = deviceElement?.getAttributeValue("active-stubs-path") ?: ""
    }

    @Deprecated("Deprecated in Java")
    override fun writeExternal(element: Element?) {
        val deviceElement = Element("device")
        deviceElement.setAttribute("web-repl-url", webReplUrl)
        deviceElement.setAttribute("uart-connection", uart.toString())
        deviceElement.setAttribute("port", portName)
        deviceElement.setAttribute("ssid", ssid)
        deviceElement.setAttribute("active-stubs-path", activeStubsPath)
        element?.addContent(deviceElement)
    }
}
