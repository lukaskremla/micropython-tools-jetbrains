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
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.net.URI
import java.net.URISyntaxException

const val EMPTY_URL_TEXT = "No WebREPL URL Selected"
const val EMPTY_PORT_NAME_TEXT = "No Port Selected"

const val DEFAULT_WEBREPL_URL = "ws://192.168.4.1:8266"

val WEBREPL_PASSWORD_LENGTH_RANGE = 4..9

private const val WIFI_KEY = "WiFi"
private const val WEBREPL_KEY = "WebREPL"

@Service(Service.Level.PROJECT)
@State(
    name = "MicroPythonTools",
    storages = [Storage("micropython-tools-settings.xml")],
    category = SettingsCategory.PLUGINS
)
/**
 * @author Lukas Kremla, elmot
 */
class MpySettingsService(private val project: Project) : SimplePersistentStateComponent<MpyState>(MpyState()) {
    companion object {
        fun getInstance(project: Project): MpySettingsService =
            project.getService(MpySettingsService::class.java)
    }

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

fun messageForBrokenUrl(url: String): @Nls String? {
    try {
        val uri = URI(url)
        if (uri.scheme !in arrayOf("ws", "wss")) {
            return "URL format has to be \"ws://host:port\" or \"wss://host:port\"\n but you have entered: \"$url\""
        }
        return null
    } catch (_: URISyntaxException) {
        return "Malformed URL $url"
    }
}