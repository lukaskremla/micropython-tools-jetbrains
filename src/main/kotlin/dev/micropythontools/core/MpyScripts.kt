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

internal object MpyScripts {
    fun retrieveMpyScriptAsString(scriptFileName: String): String {
        val resourcePath = "/scripts/MicroPythonMinified/$scriptFileName"
        val stream = MpyScripts::class.java.getResourceAsStream(resourcePath)
            ?: error("Script not found: $resourcePath")
        val lines = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.toMutableList()
        repeat(15) { if (lines.isNotEmpty()) lines.removeAt(0) }
        return lines.joinToString("\n")
    }
}