/*
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

package dev.micropythontools.communication

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.util.progress.RawProgressReporter
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.util.MpyPythonService
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.io.CopyStreamEvent
import org.apache.commons.net.io.CopyStreamListener
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * @author Lukas Kremla
 */
class MpyFTPClient(private val project: Project) {
    private val ftpClient: FTPClient = FTPClient()
    var isConnected: Boolean = false

    fun connect(ip: String, ftpUsername: String, ftpPassword: String) {
        ftpClient.connect(ip)
        ftpClient.login(ftpUsername, ftpPassword)
        ftpClient.enterLocalPassiveMode()
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
        isConnected = true
    }

    fun disconnect() {
        try {
            ftpClient.logout()
            ftpClient.disconnect()
            isConnected = false
        } catch (_: Throwable) {
            //
        }
    }

    suspend fun setupFtpServer(reporter: RawProgressReporter, hasUftpdCached: Boolean?): String {
        val settings = project.service<MpySettingsService>()
        val pythonService = project.service<MpyPythonService>()
        val deviceService = project.service<MpyDeviceService>()

        reporter.text("Retrieving FTP credentials...")

        val wifiCredentials = settings.retrieveWifiCredentials()
        val ssid = wifiCredentials.userName
        val password = wifiCredentials.getPasswordAsString()

        if (ssid.isNullOrBlank()) {
            throw IOException("Cannot upload over FTP, no SSID was provided in settings! Falling back to standard uploads.")
        }

        val cachedFtpScriptPath = "${settings.state.cachedFTPScriptPath ?: ""}/uftpd.py"
        val cachedFtpScriptImportPath = cachedFtpScriptPath
            .replace("/", ".")
            .removeSuffix(".py")
            .trim('.')

        if (settings.state.cacheFTPScript) {
            reporter.text("Validating the cached FTP script...")

            val cachedFtpScriptPath = "${settings.state.cachedFTPScriptPath ?: ""}/uftpd.py"
            val cachedFtpScriptImportPath = cachedFtpScriptPath
                .replace("/", ".")
                .removeSuffix(".py")
                .trim('.')

            val isUftpdAvailable: Boolean = when {
                hasUftpdCached != null -> hasUftpdCached
                else -> {
                    val commands = mutableListOf<String>(
                        "try:",
                        "   import $cachedFtpScriptImportPath",
                        "   print('valid')",
                        "except ImportError:",
                        "   pass"
                    )

                    deviceService.blindExecute(commands)
                        .extractSingleResponse() == "valid"
                }
            }

            if (!isUftpdAvailable) {
                val ftpFile = pythonService.retrieveMpyScriptAsVirtualFile("uftpd.py")
                val ftpTotalBytes = ftpFile.length.toDouble()
                var uploadedFTPKB = 0.0
                var uploadFtpProgress = 0.0

                fun ftpUploadProgressCallbackHandler(uploadedBytes: Int) {
                    uploadedFTPKB += (uploadedBytes.toDouble() / 1000)
                    // Convert to double for maximal accuracy
                    uploadFtpProgress += (uploadedBytes.toDouble() / ftpTotalBytes.toDouble())
                    // Ensure that uploadProgress never goes over 1.0
                    // as floating point arithmetic can have minor inaccuracies
                    uploadFtpProgress = uploadFtpProgress.coerceIn(0.0, 1.0)
                    reporter.text("Uploading ftp script... ${"%.2f".format(uploadedFTPKB)} KB of ${"%.2f".format(ftpTotalBytes / 1000)} KB")
                    reporter.fraction(uploadFtpProgress)
                }

                val scriptPath = settings.state.cachedFTPScriptPath

                val parentDirectories = when {
                    scriptPath == null -> setOf("")
                    else -> {
                        val parts = scriptPath.split("/")
                        buildSet {
                            var currentPath = ""
                            for (part in parts) {
                                if (part.isEmpty()) continue
                                currentPath += "/$part"
                                add(currentPath)
                            }
                        }
                    }
                }

                // Create the parent directories
                deviceService.safeCreateDirectories(parentDirectories)

                deviceService.upload(cachedFtpScriptPath, ftpFile.contentsToByteArray(), ::ftpUploadProgressCallbackHandler)
            }
        }

        reporter.text("Establishing an FTP server connection...")

        val commands = mutableListOf<String>()

        if (settings.state.usingUart) {
            val wifiConnectScriptName = "connect_to_wifi.py"
            val wifiConnectScript = pythonService.retrieveMpyScriptAsString(wifiConnectScriptName)
            val formattedWifiConnectScript = wifiConnectScript.format(
                """"$ssid"""",
                """"$password"""",
                20, // Wi-Fi connection timeout
            )
            commands.add(formattedWifiConnectScript)
        }

        if (settings.state.cacheFTPScript) {
            commands.add("import $cachedFtpScriptImportPath as uftpd")
            commands.add("uftpd.start()")
        } else {
            val miniUftpdScript = pythonService.retrieveMpyScriptAsString("mini_uftpd.py")

            commands.add(miniUftpdScript)
            commands.add(
                "___ftp().start()"
            )
        }

        // Catch all exceptions to avoid showing the wi-fi credentials as a notification
        val scriptResponse = try {
            deviceService.blindExecute(commands)
                .extractSingleResponse().trim()
        } catch (e: Throwable) {
            if (e.localizedMessage.contains("timed")) {
                throw IOException("ERROR: FTP Connection attempt timed out")
            } else {
                throw IOException("ERROR: There was a problem attempting to establish the FTP connection $e")
            }
        }

        if (scriptResponse.contains("ERROR")) {
            throw IOException("Ran into an error establishing an FTP connection, falling back to REPL uploads: $scriptResponse")
        } else {
            try {
                val ip = when {
                    settings.state.usingUart -> scriptResponse
                        .removePrefix("FTP server started on ")
                        .removeSuffix(":21")

                    else -> settings.state.webReplUrl
                        ?.removePrefix("ws://")
                        ?.removePrefix("wss://")
                        ?.split(":")[0]
                        ?: ""
                }

                return ip
            } catch (e: Exception) {
                throw IOException("Connecting to FTP server failed, falling back to REPL uploads: $e")
            }
        }
    }

    suspend fun teardownFtpServer() {
        val settings = project.service<MpySettingsService>()
        val deviceService = project.service<MpyDeviceService>()

        disconnect()

        val cachedFtpScriptPath = "${settings.state.cachedFTPScriptPath ?: ""}/uftpd.py"
        val cachedFtpScriptImportPath = cachedFtpScriptPath
            .replace("/", ".")
            .removeSuffix(".py")
            .trim('.')

        val cleanUpScript = mutableListOf(
            "import network, gc",
            "def ___m()",
            "   clean_wifi = ${if (settings.state.usingUart) "True" else "False"}",
            "   if clean_wifi:",
            "      for interface in [network.STA_IF, network.AP_IF]:",
            "          wlan = network.WLAN(interface)",
            "          if not wlan.active():",
            "              continue",
            "          try:",
            "              wlan.disconnect()",
            "          except:",
            "              pass",
            "          wlan.active(False)",
            "   try:",
            "       import $cachedFtpScriptImportPath as uftpd",
            "       uftpd.stop",
            "   except:",
            "       pass",
            "   try:",
            "       ___ftp().stop()",
            "       del ___ftp",
            "   except:",
            "       pass",
            "___m()",
            "del ___m",
            "gc.collect()"
        )

        deviceService.blindExecute(cleanUpScript)
    }

    fun uploadFile(path: String, bytes: ByteArray, progressCallback: (uploadedBytes: Int) -> Unit) {
        ftpClient.copyStreamListener = object : CopyStreamListener {
            override fun bytesTransferred(event: CopyStreamEvent) = Unit

            override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) =
                progressCallback(bytesTransferred)
        }

        ByteArrayInputStream(bytes).use { inputStream ->
            ftpClient.storeFile(path, inputStream)
        }
    }
}

fun shouldUseFTP(project: Project, uploadSizeBytes: Double? = null): Boolean {
    val settings = project.service<MpySettingsService>()

    with(settings.state) {
        return useFTP && (!requireMinimumFTPUploadSize || uploadSizeBytes == null || uploadSizeBytes > minimumFTPUploadSize * 1000)
    }
}