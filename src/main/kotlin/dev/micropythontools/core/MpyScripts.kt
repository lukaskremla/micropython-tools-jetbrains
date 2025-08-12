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

import java.io.File

internal object MpyScripts {
    /**
     * Loads a MicroPython helper script from the plugin's bundled resources,
     * stripping the first 15 metadata/header lines.
     */
    fun retrieveMpyScriptAsString(scriptFileName: String): String {
        val scriptPath = "${MpyPaths.microPythonScriptsPath}/$scriptFileName"
        val retrievedScript = File(scriptPath).readText(Charsets.UTF_8)
        val lines = retrievedScript.lines().toMutableList()
        repeat(15) { if (lines.isNotEmpty()) lines.removeAt(0) }
        return lines.joinToString("\n")
    }
}