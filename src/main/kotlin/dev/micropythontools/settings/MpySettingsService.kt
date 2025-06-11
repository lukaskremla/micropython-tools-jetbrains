/*
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2024-2025 Lukas Kremla
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

package dev.micropythontools.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.*
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.IconLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.io.File

internal const val PLUGIN_ID = "micropython-tools-jetbrains"

private val pluginDescriptor: IdeaPluginDescriptor
    get() = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        ?: throw RuntimeException("The $PLUGIN_ID plugin cannot find itself")

private val sandboxPath: String
    get() = "${pluginDescriptor.pluginPath}"

internal val scriptsPath: String
    get() = "$sandboxPath/scripts"

internal val microPythonScriptsPath: String
    get() = "$scriptsPath/MicroPythonMinified"

internal val stubsPath: String
    get() = "$sandboxPath/stubs"

internal const val EMPTY_URL_TEXT = "No WebREPL URL Selected"
internal const val EMPTY_PORT_NAME_TEXT = "No Port Selected"

internal const val DEFAULT_WEBREPL_IP = "192.168.4.1"
internal const val DEFAULT_WEBREPL_PORT = 8266

internal const val DEFAULT_WEBREPL_URL = "ws://192.168.4.1:8266"

internal val WEBREPL_PASSWORD_LENGTH_RANGE = 4..9

internal val mpySourceIcon = IconLoader.getIcon(
    "/icons/MpySource.svg",
    MpySettingsService::class.java
)

internal val volumeIcon = IconLoader.getIcon(
    "/icons/volume.svg",
    MpySettingsService::class.java
)

internal val questionMarkIcon = IconLoader.getIcon(
    "/icons/questionMark.svg",
    MpySettingsService::class.java
)

private const val WIFI_KEY = "WiFi"
private const val WEBREPL_KEY = "WebREPL"

/**
 * @author Lukas Kremla, elmot
 */
@Service(Service.Level.PROJECT)
@State(
    name = "MicroPythonTools",
    storages = [Storage("micropython-tools-settings.xml")],
    category = SettingsCategory.PLUGINS
)
internal class MpySettingsService(private val project: Project) : SimplePersistentStateComponent<MpyState>(MpyState()) {
    companion object {
        fun getInstance(project: Project): MpySettingsService =
            project.getService(MpySettingsService::class.java)
    }

    val webReplUrl
        get() = "ws://${state.webReplIp}:${state.webReplPort}"

    private fun createCredentialAttributes(key: String): CredentialAttributes {
        val projectIdentifyingElement = project.guessProjectDir()?.path ?: project.name
        // The final key consists of this plugins namespace, some unique attribute of the project, and finally the
        // key of the stored credentials itself. The goal is to avoid conflicts with other plugins and to ensure
        // each project gets its separate MPY Tools credentials.
        val fullKey = "${this::class.java.name}/${projectIdentifyingElement}/$key"

        return CredentialAttributes(
            generateServiceName("MySystem", fullKey)
        )
    }

    suspend fun saveWebReplPassword(password: String) {
        val attributes = createCredentialAttributes(WEBREPL_KEY)
        val credentials = Credentials("", password)

        withContext(Dispatchers.IO) {
            PasswordSafe.instance.set(attributes, credentials)
        }
    }

    suspend fun retrieveWebReplPassword(): String {
        val attributes = createCredentialAttributes(WEBREPL_KEY)

        return withContext(Dispatchers.IO) {
            PasswordSafe.instance.getPassword(attributes) ?: ""
        }
    }

    suspend fun saveWifiCredentials(ssid: String, password: String) {
        val attributes = createCredentialAttributes(WIFI_KEY)
        val credentials = Credentials(ssid, password)

        withContext(Dispatchers.IO) {
            PasswordSafe.instance.set(attributes, credentials)
        }
    }

    suspend fun retrieveWifiCredentials(): Credentials {
        val attributes = createCredentialAttributes(WIFI_KEY)

        return withContext(Dispatchers.IO) {
            PasswordSafe.instance.get(attributes) ?: Credentials("", "")
        }
    }
}

internal fun retrieveMpyScriptAsString(scriptFileName: String): String {
    val scriptPath = "$microPythonScriptsPath/$scriptFileName"

    val retrievedScript = File(scriptPath).readText(Charsets.UTF_8)

    var i = 0
    val lines = retrievedScript.lines().toMutableList()

    while (i < 15) {
        lines.removeAt(0)
        i++
    }

    return lines.joinToString("\n")
}

internal fun messageForBrokenIp(ip: String): @Nls String? {
    if (ip.isEmpty()) {
        return "Host/IP address must not be empty."
    }

    val ipPattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}\$")

    val hostnamePattern = Regex("^[a-zA-Z0-9.-]+$")

    return when {
        ipPattern.matches(ip) -> null
        hostnamePattern.matches(ip) -> null
        else -> "Invalid Host or IP address: \"$ip\""
    }
}

internal fun messageForBrokenPort(port: String): @Nls String? {
    val portNumber = port.toIntOrNull()

    return when {
        port.isEmpty() -> "Port must not be empty."
        portNumber == null -> "Port must be a valid number."
        portNumber !in 1..65535 -> "Port number must be between 1 and 65535."
        else -> null
    }
}

internal fun messageForBrokenPassword(password: CharArray): @Nls String? {
    return if (password.size !in WEBREPL_PASSWORD_LENGTH_RANGE) {
        "Allowed password length is $WEBREPL_PASSWORD_LENGTH_RANGE"
    } else null
}

internal fun validateMpyPath(path: String, isEmptyPathValid: Boolean = false): String? {
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

internal fun normalizeMpyPath(path: String, isEmptyPathValid: Boolean = false): String {
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
        normalizedPath = "/$path"
    }

    if (endedWithSlash && !normalizedPath.endsWith("/")) {
        normalizedPath = "$normalizedPath/"
    }

    return normalizedPath
}

internal fun isRunConfTargetPathValid(targetPath: String): String? {
    val validationResult = validateMpyPath(targetPath, true)

    return validationResult
        ?: if (targetPath.contains(".")) {
            "The path before file cannot contain a \".\""
        } else {
            null
        }
}