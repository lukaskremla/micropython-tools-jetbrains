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

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.net.URI
import java.net.URISyntaxException

/**
 * @author elmot
 */
private const val WIFI_CREDENTIALS_KEY = "MicroPython_Tools_wifi_key"

const val DEFAULT_WEBREPL_URL = "ws://192.168.4.1:8266"

@Service(Service.Level.PROJECT)
@State(
    name = "MicroPythonTools",
    storages = [Storage("micropython-tools-settings.xml")],
    category = SettingsCategory.PLUGINS
)
class MpySettingsService : SimplePersistentStateComponent<MpyState>(MpyState()) {
    companion object {
        fun getInstance(project: Project): MpySettingsService =
            project.getService(MpySettingsService::class.java)
    }

    private fun createCredentialAttributes(key: String): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("MySystem", key)
        )
    }

    private fun key(url: String) = "${this::class.java.name}/$url"

    suspend fun retrieveWebReplPassword(url: String): String {
        return withContext(Dispatchers.IO) {
            val attributes = createCredentialAttributes(key(url))
            val passwordSafe = PasswordSafe.instance
            passwordSafe.getPassword(attributes) ?: ""
        }
    }

    suspend fun saveWebReplPassword(url: String, password: String) {
        val attributes = createCredentialAttributes(key(url))
        val credentials = Credentials(null, password)
        withContext(Dispatchers.IO) {
            PasswordSafe.instance.set(attributes, credentials)
        }
    }

    suspend fun retrieveWifiPassword(): String {
        return withContext(Dispatchers.IO) {
            val attributes = createCredentialAttributes(WIFI_CREDENTIALS_KEY)
            PasswordSafe.instance.getPassword(attributes) ?: ""
        }
    }

    suspend fun saveWifiPassword(password: String) {
        val attributes = createCredentialAttributes(WIFI_CREDENTIALS_KEY)
        val credentials = Credentials("", password)
        withContext(Dispatchers.IO) {
            PasswordSafe.instance.set(attributes, credentials)
        }
    }
}

fun messageForBrokenUrl(url: String): @Nls String? {
    try {
        val uri = URI(url)
        if (uri.scheme !in arrayOf("ws", "wss")) {
            return "URL format has to be ws://host:port or wss://host:port\n but you have entered: $url"
        }
        return null
    } catch (_: URISyntaxException) {
        return "Malformed URL $url"
    }
}