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

import dev.micropythontools.i18n.MpyBundle
import org.jetbrains.annotations.Nls

internal object MpyValidators {
    val WEBREPL_PASSWORD_LENGTH_RANGE = 4..9

    fun messageForBrokenIp(ip: String): @Nls String? {
        if (ip.isEmpty()) {
            return MpyBundle.message("core.validation.address.empty")
        }

        val ipPattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        val hostnamePattern = Regex("^[a-zA-Z0-9.-]+$")

        return when {
            ipPattern.matches(ip) -> null
            hostnamePattern.matches(ip) -> null
            else -> MpyBundle.message("core.validation.address.invalid.host", ip)
        }
    }

    fun messageForBrokenPort(port: String): @Nls String? {
        val portNumber = port.toIntOrNull()

        return when {
            port.isEmpty() -> MpyBundle.message("core.validation.port.empty")
            portNumber == null -> MpyBundle.message("core.validation.port.invalid.number")
            portNumber !in 1..65535 -> MpyBundle.message("core.validation.port.invalid.range")
            else -> null
        }
    }

    fun messageForBrokenPassword(password: CharArray): @Nls String? {
        return if (password.size !in WEBREPL_PASSWORD_LENGTH_RANGE) {
            MpyBundle.message("core.validation.password.length", WEBREPL_PASSWORD_LENGTH_RANGE)
        } else null
    }

    fun validateMpyPath(path: String, isEmptyPathValid: Boolean = false): String? {
        val forbiddenCharacters = listOf("<", ">", ":", "\"", "|", "?", "*")
        val foundForbiddenCharacters = mutableListOf<String>()

        forbiddenCharacters.forEach {
            if (path.contains(it)) {
                foundForbiddenCharacters.add(it)
            }
        }

        if (foundForbiddenCharacters.isNotEmpty()) {
            return MpyBundle.message("core.validation.mpy.path.forbidden.characters", foundForbiddenCharacters)
        }

        // A just-in-case limit, to prevent over-inflating the synchronization script
        if (path.length > 256) {
            return MpyBundle.message("core.validation.path.too.long")
        }

        if (path.isEmpty() && !isEmptyPathValid) {
            return MpyBundle.message("core.validation.path.empty")
        }

        return null
    }

    fun normalizeMpyPath(path: String, isEmptyPathValid: Boolean = false): String {
        val endedWithSlash = path.endsWith("/")

        var normalizedPath = path

        // Replace slash format to fit MicroPython file system
        normalizedPath = normalizedPath.replace("\\", "/")

        // Normalize input to remove potential redundant path elements
        normalizedPath = java.nio.file.Paths.get(normalizedPath).normalize().toString()

        // Ensure correct slash format again
        normalizedPath = normalizedPath.replace("\\", "/")

        normalizedPath = normalizedPath.trim()

        if (path.isEmpty() && !isEmptyPathValid) {
            return normalizedPath
        }

        if (!path.startsWith("/")) {
            normalizedPath = "/$normalizedPath"
        }

        if (endedWithSlash && !normalizedPath.endsWith("/")) {
            normalizedPath = "$normalizedPath/"
        }

        return normalizedPath
    }

    fun isRunConfTargetPathValid(targetPath: String): String? {
        val validationResult = validateMpyPath(targetPath, true)

        return validationResult
            ?: if (targetPath.contains(".")) {
                MpyBundle.message("core.validation.normalize.path.leading.dot")
            } else {
                null
            }
    }
}