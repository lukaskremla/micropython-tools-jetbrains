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

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.io.CopyStreamEvent
import org.apache.commons.net.io.CopyStreamListener
import java.io.ByteArrayInputStream

/**
 * @author Lukas Kremla
 */
class MpyFTPClient {
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
        ftpClient.logout()
        ftpClient.disconnect()
        isConnected = false
    }

    fun uploadFile(path: String, bytes: ByteArray, progressCallback: (uploadedBytes: Int) -> Unit) {
        val unixPath = if (!path.startsWith("/")) {
            "/$path"
        } else {
            path
        }

        val fileName = unixPath.substringAfterLast("/")
        val filePath = unixPath.substringBeforeLast("/")

        if (filePath != "/") {
            val dirs = filePath.split("/").filter { it.isNotEmpty() }
            var currentPath = ""

            for (dir in dirs) {
                currentPath += "/$dir"
                try {
                    ftpClient.makeDirectory(currentPath)
                } catch (_: Exception) {
                    // Directory might already exist, continue
                }
            }
        }

        ftpClient.changeWorkingDirectory(filePath)

        ftpClient.copyStreamListener = object : CopyStreamListener {
            override fun bytesTransferred(event: CopyStreamEvent) {}

            override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) {
                progressCallback(bytesTransferred)
            }
        }

        ByteArrayInputStream(bytes).use { inputStream ->
            ftpClient.storeFile(fileName, inputStream)
        }
    }
}