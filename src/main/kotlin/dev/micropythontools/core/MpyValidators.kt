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

import org.jetbrains.annotations.Nls

internal object MpyValidators {
    val WEBREPL_PASSWORD_LENGTH_RANGE = 4..9

    fun messageForBrokenIp(ip: String): @Nls String? {
        if (ip.isEmpty()) {
            return "Host/IP address must not be empty."
        }

        val ipPattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        val hostnamePattern = Regex("^[a-zA-Z0-9.-]+$")

        return when {
            ipPattern.matches(ip) -> null
            hostnamePattern.matches(ip) -> null
            else -> "Invalid Host or IP address: \"$ip\""
        }
    }

    fun messageForBrokenPort(port: String): @Nls String? {
        val portNumber = port.toIntOrNull()

        return when {
            port.isEmpty() -> "Port must not be empty."
            portNumber == null -> "Port must be a valid number."
            portNumber !in 1..65535 -> "Port number must be between 1 and 65535."
            else -> null
        }
    }

    fun messageForBrokenPassword(password: CharArray): @Nls String? {
        return if (password.size !in WEBREPL_PASSWORD_LENGTH_RANGE) {
            "Allowed password length is $WEBREPL_PASSWORD_LENGTH_RANGE"
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
            return "Found forbidden characters: $foundForbiddenCharacters"
        }

        // A just-in-case limit, to prevent over-inflating the synchronization script
        if (path.length > 256) {
            return "Path is too long (maximum 256 characters)"
        }

        if (path.isEmpty() && !isEmptyPathValid) {
            return "Path can't be empty!"
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
                "The path before file cannot contain a \".\""
            } else {
                null
            }
    }
}